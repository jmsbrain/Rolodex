package com.jms.fullcontacts

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.w3c.dom.Node

data class BookInfo(val url: String, val name: String, val ctag: String?, val readOnly: Boolean)

/** One contact file on the server. */
data class CardFile(val url: String, val etag: String?, val text: String)

class DavError(message: String, val status: Int = 0) : Exception(message)

/** Just enough CardDAV (RFC 6352) to list address books and read and write contacts. */
class CardDav(user: String, password: String) {

    private val auth = Credentials.basic(user, password, Charsets.UTF_8)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", auth).build()) }
        .build()

    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val vcard = "text/vcard; charset=utf-8".toMediaType()

    // ---- discovery ----

    /** Finds the address book home behind [server] (e.g. https://dav.mailbox.org/). */
    fun findHome(server: String): String {
        val base = server.trim().let { if ("://" in it) it else "https://$it" }.toHttpUrl()
        val candidates = listOf(base, base.resolve("/.well-known/carddav")!!, base.resolve("/carddav/")!!, base.resolve("/")!!).distinct()
        var lastError: Exception? = null
        for (url in candidates) {
            try {
                val principal = propfind(url, 0, PRINCIPAL_BODY).firstNotNullOfOrNull { r -> r.hrefIn("current-user-principal")?.let { r.base.resolve(it) } }
                    ?: continue
                val home = propfind(principal, 0, HOME_BODY).firstNotNullOfOrNull { r -> r.hrefIn("addressbook-home-set")?.let { r.base.resolve(it) } }
                    ?: continue
                return home.toString()
            } catch (e: DavError) {
                if (e.status == 401) throw e
                lastError = e
            }
        }
        throw lastError as? DavError ?: DavError("${base.host} doesn't look like a CardDAV server.")
    }

    fun addressBooks(home: String): List<BookInfo> =
        propfind(home.toHttpUrl(), 1, BOOKS_BODY).mapNotNull { r ->
            if (r.el("resourcetype")?.child("addressbook") == null) return@mapNotNull null
            val privs = r.el("current-user-privilege-set")
            val writable = privs == null || privs.children("privilege").any { p ->
                p.child("write") != null || p.child("write-content") != null || p.child("all") != null
            }
            BookInfo(
                url = r.url.toString(),
                name = r.el("displayname")?.textContent?.trim().orEmpty()
                    .ifBlank { r.url.pathSegments.lastOrNull { it.isNotBlank() } ?: "Contacts" },
                ctag = r.el("getctag")?.textContent?.trim(),
                readOnly = !writable,
            )
        }

    // ---- contacts ----

    /** Every contact file in [book], URL → ETag. */
    fun list(book: String): Map<String, String?> {
        val bookUrl = book.toHttpUrl()
        return propfind(bookUrl, 1, LIST_BODY)
            .filter { it.url != bookUrl && it.el("resourcetype")?.child("collection") == null }
            .associate { it.url.toString() to it.el("getetag")?.textContent?.trim() }
    }

    /** Downloads [urls] from [book], several per request. */
    fun multiget(book: String, urls: List<String>): List<CardFile> {
        if (urls.isEmpty()) return emptyList()
        val hrefs = urls.joinToString("") { "<d:href>${escapeXml(it.toHttpUrl().encodedPath)}</d:href>" }
        val body = MULTIGET_BODY.replace("{hrefs}", hrefs)
        return report(book.toHttpUrl(), body).mapNotNull { r ->
            val data = r.el("address-data")?.textContent ?: return@mapNotNull null
            CardFile(r.url.toString(), r.el("getetag")?.textContent?.trim(), data)
        }
    }

    /** Null if it's gone. */
    fun get(url: String): CardFile? {
        val req = Request.Builder().url(url).get().build()
        return try {
            call(req) { res -> CardFile(url, res.header("ETag"), res.body!!.string()) }
        } catch (e: DavError) {
            if (e.status == 404 || e.status == 410) null else throw e
        }
    }

    /**
     * Creates ([create]) or replaces a contact; [etag] guards against overwriting someone else's change.
     * Returns the new ETag if the server sent one.
     */
    fun put(url: String, text: String, etag: String?, create: Boolean): String? {
        val req = Request.Builder().url(url).put(text.toRequestBody(vcard)).apply {
            when {
                create -> header("If-None-Match", "*")
                etag != null -> header("If-Match", etag)
            }
        }.build()
        return call(req) { it.header("ETag") }
    }

    fun delete(url: String, etag: String?) {
        val req = Request.Builder().url(url).delete().apply { if (etag != null) header("If-Match", etag) }.build()
        try {
            call(req) { }
        } catch (e: DavError) {
            if (e.status != 404 && e.status != 410) throw e
        }
    }

    // ---- plumbing ----

    private fun propfind(url: HttpUrl, depth: Int, body: String) = multistatus(
        Request.Builder().url(url).method("PROPFIND", body.toRequestBody(xml)).header("Depth", depth.toString()).build(),
    )

    private fun report(url: HttpUrl, body: String) = multistatus(
        Request.Builder().url(url).method("REPORT", body.toRequestBody(xml)).header("Depth", "1").build(),
    )

    private fun <T> call(req: Request, read: (Response) -> T): T {
        val res = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw DavError(networkMessage(e, req.url.host))
        }
        res.use {
            if (!it.isSuccessful) throw statusError(it.code, req.url.host)
            return read(it)
        }
    }

    private fun multistatus(req: Request): List<DavResponse> = call(req) { res ->
        val text = res.body?.string().orEmpty()
        val doc = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(text.byteInputStream())
        } catch (e: Exception) {
            throw DavError("${req.url.host} sent an answer Rolodex can't read.")
        }
        val base = res.request.url
        doc.documentElement.children("response").mapNotNull { r ->
            val href = r.child("href")?.textContent?.trim() ?: return@mapNotNull null
            val url = base.resolve(href) ?: return@mapNotNull null
            // Only props from 200 propstats.
            val props = r.children("propstat").filter { ps ->
                ps.child("status")?.textContent?.contains(" 200") != false
            }.mapNotNull { it.child("prop") }
            DavResponse(base, url, props)
        }
    }

    private fun statusError(code: Int, host: String) = when (code) {
        401 -> DavError("$host didn't accept that email and password. If you use two-factor sign-in, make an app password in your mail settings.", code)
        403 -> DavError("$host says this account can't do that.", code)
        404 -> DavError("$host couldn't find that.", code)
        else -> DavError("$host said $code.", code)
    }

    private fun networkMessage(e: IOException, host: String) = when (e) {
        is javax.net.ssl.SSLException -> "The phone doesn't trust the security certificate of $host."
        is java.net.UnknownHostException -> "Couldn't reach $host. Check that the phone is online."
        is java.net.SocketTimeoutException -> "$host took too long to answer."
        else -> "Couldn't connect to $host."
    }

    private class DavResponse(val base: HttpUrl, val url: HttpUrl, val props: List<Element>) {
        fun el(local: String): Element? = props.firstNotNullOfOrNull { it.child(local) }
        fun hrefIn(local: String): String? = el(local)?.child("href")?.textContent?.trim()
    }

    companion object {
        private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private const val PRINCIPAL_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""

        private const val HOME_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav"><d:prop><c:addressbook-home-set/></d:prop></d:propfind>"""

        private const val BOOKS_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
<d:prop><d:resourcetype/><d:displayname/><cs:getctag/><d:current-user-privilege-set/></d:prop>
</d:propfind>"""

        private const val LIST_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/></d:prop></d:propfind>"""

        private const val MULTIGET_BODY = """<?xml version="1.0" encoding="utf-8"?>
<c:addressbook-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
<d:prop><d:getetag/><c:address-data/></d:prop>
{hrefs}
</c:addressbook-multiget>"""
    }
}

private fun Element.children(local: String): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n.nodeType == Node.ELEMENT_NODE && (n.localName ?: n.nodeName.substringAfter(':')) == local) out += n as Element
    }
    return out
}

private fun Element.child(local: String): Element? = children(local).firstOrNull()
