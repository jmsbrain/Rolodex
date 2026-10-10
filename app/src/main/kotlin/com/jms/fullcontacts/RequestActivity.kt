package com.jms.fullcontacts

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A request from another app. */
sealed interface Request {
    /** Needs WRITE_CONTACTS. */
    val writes: Boolean

    /** New contact, prefilled. */
    data class Insert(val prefill: Prefill?) : Request { override val writes = true }

    /** Edit a contact, optionally prefilled. */
    data class Edit(val uri: Uri, val prefill: Prefill?) : Request { override val writes = true }

    /** Pick existing or new, then edit. */
    data class InsertOrEdit(val prefill: Prefill?) : Request { override val writes = true }

    /** Pick a contact ([kind] null) or one value. */
    data class Pick(val kind: EditKind?) : Request { override val writes = false }

    /** Import a .vcf. */
    data class Import(val uri: Uri) : Request { override val writes = true }
}

/** Handles add, edit, pick and .vcf requests, then returns to the caller. */
class RequestActivity : RolodexActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = parse(intent)
        if (request == null) {
            finishCanceled()
            return
        }
        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LightStyle.background)
                    .systemBarsPadding()
                    .imePadding(),
            ) {
                if (hasPermission && (hasWritePermission || !request.writes)) {
                    RequestContent(this@RequestActivity, request)
                } else {
                    PermissionScreen(this@RequestActivity, ::onPermissionResult)
                }
            }
        }
    }

    private fun parse(intent: Intent?): Request? {
        intent ?: return null
        val prefill = Prefill.fromInsertExtras(intent.extras)
        return when (intent.action) {
            Intent.ACTION_INSERT -> Request.Insert(prefill)
            Intent.ACTION_EDIT -> intent.data?.let { Request.Edit(it, prefill) }
            Intent.ACTION_INSERT_OR_EDIT -> Request.InsertOrEdit(prefill)
            Intent.ACTION_PICK, Intent.ACTION_GET_CONTENT -> {
                val type = intent.type ?: intent.resolveType(this).orEmpty()
                Request.Pick(
                    when {
                        "phone_v2" in type -> EditKind.PHONES
                        "email_v2" in type -> EditKind.EMAILS
                        "postal-address_v2" in type -> EditKind.ADDRESSES
                        else -> null
                    },
                )
            }
            Intent.ACTION_VIEW -> intent.data?.let { Request.Import(it) }
            else -> null
        }
    }

    /** Returns [uri] with read access. */
    fun finishWith(uri: Uri?) {
        val result = Intent()
        if (uri != null) {
            result.data = uri
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    fun finishWithContact(contactId: Long?) = finishWith(contactId?.let { repository.lookupUri(it) })

    fun finishCanceled() {
        setResult(RESULT_CANCELED)
        finish()
    }
}

@Composable
private fun RequestContent(activity: RequestActivity, request: Request) {
    when (request) {
        is Request.Insert -> ContactEditorScreen(
            activity = activity,
            contactId = null,
            prefill = request.prefill,
            onCancel = activity::finishCanceled,
            onSaved = activity::finishWithContact,
            onDeleted = activity::finishCanceled,
        )

        is Request.Edit -> {
            var contactId by remember { mutableStateOf<Long?>(null) }
            var resolved by remember { mutableStateOf(false) }
            LaunchedEffect(request.uri) {
                contactId = withContext(Dispatchers.IO) { activity.resolveContactId(request.uri) }
                resolved = true
            }
            val id = contactId
            when {
                id != null -> ContactEditorScreen(
                    activity = activity,
                    contactId = id,
                    prefill = request.prefill,
                    onCancel = activity::finishCanceled,
                    onSaved = { activity.finishWithContact(it ?: id) },
                    onDeleted = activity::finishCanceled,
                )
                resolved -> Message("This contact no longer exists.", activity::finishCanceled)
            }
        }

        is Request.InsertOrEdit -> InsertOrEditFlow(activity, request.prefill)

        is Request.Pick -> {
            val kind = request.kind
            if (kind == null) {
                PickContact(activity, "Choose a contact", addLabel = null, onAdd = null) { id ->
                    activity.finishWithContact(id)
                }
            } else {
                PickValue(activity, kind)
            }
        }

        is Request.Import -> ImportFlow(activity, request.uri)
    }
}

/** Heading + contact list. */
@Composable
private fun PickContact(
    activity: RolodexActivity,
    heading: String,
    addLabel: String?,
    onAdd: (() -> Unit)?,
    onPick: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var contacts by remember { mutableStateOf<List<ContactSummary>?>(null) }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) {
            runCatching { activity.repository.loadSummaries() }.getOrDefault(emptyList())
        }
    }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    Column(Modifier.fillMaxSize()) {
        BasicText(
            heading,
            style = labelStyle(),
            modifier = Modifier.padding(start = designDp(24f), end = designDp(24f), top = designDp(16f)),
        )
        ContactListScreen(
            contacts = contacts,
            query = query,
            onQueryChange = { query = it },
            listState = listState,
            onOpen = onPick,
            onAdd = onAdd,
            addLabel = addLabel ?: "ADD",
        )
    }
}

/** Pick one phone, email or address. */
@Composable
private fun PickValue(activity: RequestActivity, kind: EditKind) {
    var query by rememberSaveable { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<DataChoice>?>(null) }
    LaunchedEffect(kind) {
        choices = withContext(Dispatchers.IO) {
            runCatching { activity.repository.loadChoices(kind) }.getOrDefault(emptyList())
        }
    }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    val q = query.trim()
    val filtered = remember(choices, q) {
        choices.orEmpty().filter {
            q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.value.contains(q, ignoreCase = true)
        }
    }
    val what = when (kind) {
        EditKind.PHONES -> "a phone number"
        EditKind.EMAILS -> "an email address"
        else -> "an address"
    }

    Column(Modifier.fillMaxSize().padding(horizontal = designDp(24f))) {
        Spacer(Modifier.height(designDp(16f)))
        BasicText("Choose $what", style = labelStyle())
        SearchField(query) { query = it }
        Spacer(Modifier.height(physicalInches(0.1f)))
        when {
            choices == null -> BasicText("Loading…", style = paragraphStyle().copy(color = LightStyle.secondary))
            filtered.isEmpty() -> BasicText("Nothing to choose.", style = paragraphStyle().copy(color = LightStyle.secondary))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.dataId }) { choice ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .flatClickable {
                                activity.finishWith(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, choice.dataId))
                            }
                            .padding(vertical = designDp(8f)),
                    ) {
                        BasicText(choice.name, style = copyStyle(), maxLines = 1)
                        if (choice.label.isNotBlank()) BasicText(choice.label, style = labelStyle())
                        BasicText(choice.value, style = paragraphStyle())
                    }
                }
                item { Spacer(Modifier.height(designDp(40f))) }
            }
        }
    }
}

/** Pick a contact (or NEW), then edit. */
@Composable
private fun InsertOrEditFlow(activity: RequestActivity, prefill: Prefill?) {
    // null = choosing, NEW_CONTACT = new.
    var target by rememberSaveable { mutableStateOf<Long?>(null) }
    val t = target
    if (t == null) {
        PickContact(activity, "Add to which contact?", addLabel = "NEW", onAdd = { target = NEW_CONTACT }) {
            target = it
        }
    } else {
        ContactEditorScreen(
            activity = activity,
            contactId = t.takeIf { it != NEW_CONTACT },
            prefill = prefill,
            onCancel = { target = null },
            onSaved = { activity.finishWithContact(it ?: t.takeIf { id -> id != NEW_CONTACT }) },
            onDeleted = activity::finishCanceled,
        )
    }
}

private const val NEW_CONTACT = -1L

/** One contact opens in the editor; several get IMPORT ALL. */
@Composable
private fun ImportFlow(activity: RequestActivity, uri: Uri) {
    var cards by remember { mutableStateOf<List<Prefill>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(uri) {
        cards = withContext(Dispatchers.IO) {
            runCatching {
                activity.contentResolver.openInputStream(uri)?.use { VCard.parse(it.readBytes().toString(Charsets.UTF_8)) }
            }.getOrNull().orEmpty()
        }
    }
    val list = cards
    when {
        list == null -> Message("Reading contact file…", null)
        list.isEmpty() -> Message("Couldn't find any contacts in this file.", activity::finishCanceled)
        list.size == 1 -> ContactEditorScreen(
            activity = activity,
            contactId = null,
            prefill = list[0],
            onCancel = activity::finishCanceled,
            onSaved = activity::finishWithContact,
            onDeleted = activity::finishCanceled,
        )
        else -> Column(Modifier.fillMaxSize().padding(horizontal = designDp(24f), vertical = designDp(12f))) {
            if (busy) {
                BasicText("Importing…", style = paragraphStyle().copy(color = LightStyle.secondary))
            } else {
                Row {
                    TextButton("CANCEL", onClick = activity::finishCanceled)
                    Spacer(Modifier.weight(1f))
                    TextButton("IMPORT ALL") {
                        busy = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                list.count { card ->
                                    val blank = ContactDraft.blank()
                                    runCatching { activity.writer.save(blank, card.applyTo(blank)) }.isSuccess
                                }
                            }
                            activity.toast("Imported $saved of ${list.size} contacts")
                            activity.finishWith(null)
                        }
                    }
                }
            }
            Spacer(Modifier.height(designDp(16f)))
            BasicText("${list.size} contacts in this file", style = labelStyle())
            LazyColumn(Modifier.fillMaxSize()) {
                items(list) { card ->
                    BasicText(
                        card.displayName.ifEmpty { "(no name)" },
                        style = copyStyle(),
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = designDp(6f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, onClose: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(horizontal = designDp(24f), vertical = designDp(16f))) {
        BasicText(text, style = paragraphStyle())
        if (onClose != null) {
            Spacer(Modifier.height(designDp(20f)))
            TextButton("CLOSE", onClick = onClose)
        }
    }
}
