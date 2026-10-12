package com.jms.fullcontacts

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.OperationApplicationException
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class SyncState(val running: Boolean = false, val finished: Int = 0)

/**
 * Two-way sync between the server and Rolodex's account in the phone's contacts.
 *
 * Each raw contact keeps: SOURCE_ID = the file's URL, SYNC1 = its ETag, SYNC2 = the server's card as last synced,
 * SYNC3 = the phone's rows right after that sync. Comparing against those tells which side changed what.
 */
object CardSync {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private const val FETCH_BATCH = 50
    private const val INSERT_BATCH = 40
    private const val AUTO_SYNC_MS = 10 * 60 * 1000L

    /** Runs a sync in the background (after an edit, or when the app opens). */
    fun syncSoon(ctx: Context) {
        val app = ctx.applicationContext
        if (!SyncSettings.signedIn(app)) return
        scope.launch { sync(app) }
    }

    /** Syncs if the last one was a while ago. */
    fun syncIfStale(ctx: Context) {
        val last = SyncSettings.lastSync(ctx) ?: 0
        if (System.currentTimeMillis() - last > AUTO_SYNC_MS) syncSoon(ctx)
    }

    /** Returns the error, or null if it worked. Waits if a sync is already running. */
    suspend fun sync(ctx: Context): String? = lock.withLock {
        if (!SyncSettings.signedIn(ctx)) return null
        _state.update { it.copy(running = true) }
        val error = try {
            Run(ctx).all()
        } catch (e: DavError) {
            e.message
        } catch (e: Exception) {
            "Sync stopped: ${e.message ?: e.javaClass.simpleName}"
        }
        SyncSettings.recordResult(ctx, error)
        _state.update { SyncState(running = false, finished = it.finished + 1) }
        error
    }

    private class Local(
        val id: Long,
        val href: String?,
        val etag: String?,
        val base: String?,
        val snapshot: String?,
        val dirty: Boolean,
        val deleted: Boolean,
        val version: Long,
    )

    private class Run(private val ctx: Context) {
        private val resolver: ContentResolver = ctx.contentResolver
        private lateinit var account: Account
        private lateinit var dav: CardDav
        private val problems = ArrayList<String>()

        fun all(): String? {
            account = SyncSettings.ensureAccount(ctx) ?: return null
            val user = SyncSettings.user(ctx)!!
            val password = SyncSettings.password(ctx) ?: return "Rolodex lost your password. Sign in again."
            dav = CardDav(user, password)

            // Refresh the list of address books, keeping choices and sync state.
            val home = SyncSettings.home(ctx)!!
            val saved = SyncSettings.books(ctx).associateBy { it.url }
            val remote = dav.addressBooks(home)
            val books = remote.map { r ->
                val old = saved[r.url]
                Book(r.url, r.name, r.readOnly, old?.sync ?: false, old?.ctag)
            }
            val synced = books.filter { it.sync }
            val newBook = SyncSettings.newBook(ctx).takeIf { url -> synced.any { it.url == url && !it.readOnly } }
                ?: synced.firstOrNull { !it.readOnly }?.url

            val locals = loadLocals()
            // Contacts from books no longer synced (or gone) leave the phone.
            for (l in locals) {
                val href = l.href ?: continue
                if (synced.none { inBook(href, it.url) }) deleteLocal(l.id)
            }

            // A book turned off forgets its state, so turning it back on downloads it all again.
            val updated = books.map { if (it.sync) it else it.copy(ctag = null) }.toMutableList()
            for (book in synced) {
                val mine = locals.filter { l -> l.href?.let { inBook(it, book.url) } ?: (book.url == newBook) }
                // Read-only: changes can't go up, so deletions just come back.
                if (book.readOnly) mine.filter { it.deleted }.forEach { deleteLocal(it.id) }
                val uploaded = if (book.readOnly) false else upload(book, mine)
                val ctag = remote.first { it.url == book.url }.ctag
                // Unchanged on the server since last time: skip (unless the phone has none of it yet).
                if (!uploaded && ctag != null && ctag == book.ctag && mine.isNotEmpty()) continue
                download(book)
                val i = updated.indexOfFirst { it.url == book.url }
                updated[i] = updated[i].copy(ctag = ctag)
            }
            // A book switched on or off during this sync keeps its new setting; the next sync applies it.
            val latest = SyncSettings.books(ctx).associateBy { it.url }
            SyncSettings.saveBooks(ctx, updated.map { u -> latest[u.url]?.takeIf { it.sync != u.sync } ?: u })
            return problems.firstOrNull()?.let { if (problems.size > 1) "$it (and ${problems.size - 1} more)" else it }
        }

        // ---- phone → server ----

        /** Sends the phone's changes. Returns true if anything was sent. */
        private fun upload(book: Book, mine: List<Local>): Boolean {
            var sent = false
            for (l in mine) {
                try {
                    when {
                        l.deleted -> {
                            if (l.href != null) {
                                try {
                                    dav.delete(l.href, l.etag)
                                } catch (e: DavError) {
                                    // 412: changed elsewhere since; keep it, the download brings it back.
                                    if (e.status != 412) throw e
                                }
                            }
                            deleteLocal(l.id)
                            sent = true
                        }
                        l.href == null -> {
                            create(book, l)
                            sent = true
                        }
                        l.dirty -> if (update(l)) sent = true
                    }
                } catch (e: DavError) {
                    if (e.status == 401) throw e
                    problems += "Couldn't send a change: ${e.message}"
                } catch (e: OperationApplicationException) {
                    // Edited again during the sync; next time.
                }
            }
            return sent
        }

        private fun create(book: Book, l: Local) {
            val rows = readRows(l.id)
            var uid = UUID.randomUUID().toString()
            var etag: String? = null
            var url = ""
            var card: VCardDoc? = null
            for (attempt in 0 until 3) {
                url = book.url.trimEnd('/') + "/" + uid + ".vcf"
                card = VCardDoc.fromRows(rows, uid)
                try {
                    etag = dav.put(url, card.text(), null, create = true)
                    break
                } catch (e: DavError) {
                    if (e.status != 412 || attempt == 2) throw e
                    uid = UUID.randomUUID().toString()
                }
            }
            val sent = card!!
            checkKept(l, url, etag, sent, SyncFields.mimes, emptyMap())
        }

        /** Merges with the server's copy and sends it. Returns true if anything was sent. */
        private fun update(l: Local): Boolean {
            val href = l.href!!
            val local = readRows(l.id)
            val snapshot = l.snapshot?.let(::decodeRows)
            val base = l.base?.let { VCardDoc.parseOne(it) }
            repeat(3) { attempt ->
                val server = dav.get(href)?.let { f -> VCardDoc.parseOne(f.text)?.let { f to it } }
                if (server == null) {
                    // Deleted elsewhere, but edited here: put it back.
                    val card = VCardDoc.fromRows(local, base?.uid ?: UUID.randomUUID().toString())
                    val etag = dav.put(href, card.text(), null, create = true)
                    checkKept(l, href, etag, card, SyncFields.mimes, emptyMap())
                    return true
                }
                val (file, doc) = server
                val merged = SyncMerge.merge(doc, base, local, snapshot)
                val text = merged.card.text()
                if (text == doc.text()) {
                    finish(l, href, file.etag, doc, merged.pullDown)
                    return false
                }
                try {
                    val etag = dav.put(href, text, file.etag, create = false)
                    checkKept(l, href, etag, merged.card, merged.replaced, merged.pullDown)
                    return true
                } catch (e: DavError) {
                    // Changed elsewhere in between: merge again.
                    if (e.status != 412 || attempt == 2) throw e
                }
            }
            return false
        }

        /**
         * Reads back what the server stored. Values it dropped (OX has room for only two mobile numbers, for example)
         * stay on the phone and are listed on the sync screen; kinds it kept in full take the server's version.
         */
        private fun checkKept(l: Local, href: String, etag: String?, sent: VCardDoc, sentKinds: Set<String>, pullDown: Map<String, List<SyncRow>>) {
            val file = dav.get(href)
            val stored = file?.let { VCardDoc.parseOne(it.text) }
            if (stored == null) {
                finish(l, href, etag, sent, pullDown)
                return
            }
            val lost = SyncMerge.lost(sent, stored, sentKinds)
            val lostKinds = lost.map { it.mime }.toSet()
            val storedRows = SyncFields.byMime(stored.rows())
            val sentRows = SyncFields.byMime(sent.rows())
            val take = sentKinds
                .filter { it !in lostKinds && !SyncFields.same(storedRows[it].orEmpty(), sentRows[it].orEmpty()) }
                .associateWith { storedRows[it].orEmpty() }
            finish(l, href, file.etag ?: etag, stored, pullDown + take)
            val name = stored.first("FN")?.text?.ifBlank { null } ?: sent.first("FN")?.text.orEmpty()
            SyncSettings.setNotes(ctx, name, lost.map { "$name: ${it["data1"] ?: it.values.values.first()}" })
        }

        // ---- server → phone ----

        private fun download(book: Book) {
            val remote = dav.list(book.url)
            val locals = loadLocals().filter { it.href != null && inBook(it.href, book.url) }.associateBy { key(it.href!!) }
            val wanted = remote.filter { (url, etag) ->
                val l = locals[key(url)]
                l == null || (!l.dirty && !l.deleted && (etag == null || etag != l.etag))
            }.keys.toList()

            val inserts = ArrayList<Pair<CardFile, VCardDoc>>()
            for (chunk in wanted.chunked(FETCH_BATCH)) {
                val files = try {
                    dav.multiget(book.url, chunk)
                } catch (e: DavError) {
                    if (e.status == 401) throw e
                    chunk.mapNotNull { dav.get(it) }
                }
                for (f in files) {
                    val doc = VCardDoc.parseOne(f.text) ?: continue
                    if (doc.isGroup) continue
                    val l = locals[key(f.url)]
                    if (l == null) {
                        inserts += f to doc
                        if (inserts.size >= INSERT_BATCH) insertAll(inserts).also { inserts.clear() }
                    } else {
                        applyServer(l, f, doc)
                    }
                }
            }
            insertAll(inserts)

            val remoteKeys = remote.keys.map(::key).toSet()
            for ((k, l) in locals) {
                if (k !in remoteKeys && !l.dirty && !l.deleted) deleteLocal(l.id)
            }
        }

        private fun applyServer(l: Local, f: CardFile, doc: VCardDoc) {
            val local = readRows(l.id)
            val merged = SyncMerge.merge(doc, l.base?.let { VCardDoc.parseOne(it) }, local, local)
            try {
                finish(l, l.href!!, f.etag, doc, merged.pullDown)
            } catch (e: OperationApplicationException) {
                // Edited during the sync; it uploads next time.
            }
        }

        private fun insertAll(items: List<Pair<CardFile, VCardDoc>>) {
            if (items.isEmpty()) return
            val ops = ArrayList<ContentProviderOperation>()
            val starts = ArrayList<Int>()
            for ((f, doc) in items) {
                val start = ops.size
                starts += start
                ops += ContentProviderOperation.newInsert(sa(RawContacts.CONTENT_URI))
                    .withValue(RawContacts.ACCOUNT_NAME, account.name)
                    .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                    .withValue(RawContacts.SOURCE_ID, f.url)
                    .withValue(RawContacts.SYNC1, f.etag)
                    .withValue(RawContacts.SYNC2, doc.withoutBinary().text())
                    .withYieldAllowed(true)
                    .build()
                for (row in doc.rows()) ops += insertRow(row, null, start)
            }
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            for (start in starts) {
                val id = results[start].uri?.let { ContentUris.parseId(it) } ?: continue
                saveSnapshot(id)
            }
        }

        // ---- phone database ----

        /**
         * Records a finished sync: replaces the [pullDown] kinds with the server's rows, then stores the URL, ETag,
         * the card and a snapshot. Fails if the contact was edited since it was read.
         */
        private fun finish(l: Local, href: String, etag: String?, card: VCardDoc, pullDown: Map<String, List<SyncRow>>) {
            val ops = ArrayList<ContentProviderOperation>()
            ops += ContentProviderOperation.newAssertQuery(sa(ContentUris.withAppendedId(RawContacts.CONTENT_URI, l.id)))
                .withValue(RawContacts.VERSION, l.version)
                .build()
            val current = if (pullDown.isEmpty()) emptyMap() else SyncFields.byMime(readRows(l.id))
            for ((mime, rows) in pullDown) {
                if (rows.isEmpty() && current[mime].isNullOrEmpty()) continue
                ops += ContentProviderOperation.newDelete(sa(Data.CONTENT_URI))
                    .withSelection("${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(l.id.toString(), mime))
                    .build()
                rows.forEach { ops += insertRow(it, l.id, null) }
            }
            ops += ContentProviderOperation.newUpdate(sa(ContentUris.withAppendedId(RawContacts.CONTENT_URI, l.id)))
                .withValue(RawContacts.SOURCE_ID, href)
                .withValue(RawContacts.SYNC1, etag)
                .withValue(RawContacts.SYNC2, card.withoutBinary().text())
                .withValue(RawContacts.DIRTY, 0)
                .build()
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            saveSnapshot(l.id)
        }

        private fun saveSnapshot(id: Long) {
            val values = android.content.ContentValues().apply { put(RawContacts.SYNC3, encodeRows(readRows(id))) }
            resolver.update(sa(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)), values, null, null)
        }

        private fun insertRow(row: SyncRow, rawId: Long?, backRef: Int?): ContentProviderOperation {
            val b = ContentProviderOperation.newInsert(sa(Data.CONTENT_URI)).withValue(Data.MIMETYPE, row.mime)
            if (rawId != null) b.withValue(Data.RAW_CONTACT_ID, rawId) else b.withValueBackReference(Data.RAW_CONTACT_ID, backRef!!)
            row.values.forEach { (k, v) -> b.withValue(k, v) }
            // Name with no parts (a company, an email address): keep it whole, or Android splits it into first and last.
            if (row.mime == StructuredName.CONTENT_ITEM_TYPE && row.values.keys == setOf(StructuredName.DISPLAY_NAME)) {
                b.withValue(StructuredName.GIVEN_NAME, row[StructuredName.DISPLAY_NAME])
            }
            return b.build()
        }

        private fun deleteLocal(id: Long) {
            resolver.delete(sa(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)), null, null)
        }

        private fun loadLocals(): List<Local> {
            val out = ArrayList<Local>()
            resolver.query(
                sa(RawContacts.CONTENT_URI),
                arrayOf(
                    RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2, RawContacts.SYNC3,
                    RawContacts.DIRTY, RawContacts.DELETED, RawContacts.VERSION,
                ),
                "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
                arrayOf(account.type, account.name),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Local(
                        id = c.getLong(0),
                        href = c.getString(1),
                        etag = c.getString(2),
                        base = c.getString(3),
                        snapshot = c.getString(4),
                        dirty = c.getInt(5) != 0,
                        deleted = c.getInt(6) != 0,
                        version = c.getLong(7),
                    )
                }
            }
            return out
        }

        private fun readRows(rawId: Long): List<SyncRow> {
            val out = ArrayList<SyncRow>()
            val mimes = SyncFields.mimes.toList()
            resolver.query(
                Data.CONTENT_URI,
                null,
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${mimes.joinToString { "?" }})",
                arrayOf(rawId.toString()) + mimes,
                "${Data._ID} ASC",
            )?.use { c ->
                val mimeCol = c.getColumnIndexOrThrow(Data.MIMETYPE)
                while (c.moveToNext()) {
                    val mime = c.getString(mimeCol)
                    val cols = SyncFields.columns[mime] ?: continue
                    val values = cols.mapNotNull { col ->
                        val i = c.getColumnIndex(col)
                        if (i < 0 || c.isNull(i)) null else c.getString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { col to it }
                    }.toMap()
                    if (values.isNotEmpty()) out += SyncRow(mime, values).normalized()
                }
            }
            return out
        }

        /** As the sync adapter: no DIRTY flag, deletes are final. */
        private fun sa(uri: Uri): Uri = uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .build()
    }

    // ---- helpers ----

    /** Compares URLs by decoded path, since servers encode hrefs differently. */
    private fun key(url: String): String = url.toHttpUrlOrNull()?.pathSegments?.joinToString("/") ?: url

    private fun inBook(href: String, book: String): Boolean {
        val b = key(book).trimEnd('/')
        val h = key(href)
        return h.startsWith("$b/")
    }

    private fun encodeRows(rows: List<SyncRow>): String {
        val arr = JSONArray()
        rows.forEach { r -> arr.put(JSONObject().put("m", r.mime).put("v", JSONObject(r.values))) }
        return arr.toString()
    }

    private fun decodeRows(text: String): List<SyncRow>? = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val v = o.getJSONObject("v")
            SyncRow(o.getString("m"), v.keys().asSequence().associateWith { v.getString(it) })
        }
    }.getOrNull()
}
