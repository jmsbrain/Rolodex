package com.jms.fullcontacts

import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Relation
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale

/** Reads vCard 2.1/3.0/4.0 files. Photos and unknown fields are skipped. */
object VCard {

    /** One decoded "NAME;PARAMS:value" line. */
    private class Property(val name: String, val params: Map<String, List<String>>, val rawValue: String) {
        val types: Set<String> = params["TYPE"].orEmpty().flatMap { it.split(',') }.map { it.uppercase(Locale.ROOT) }.toSet()

        /** Parts split on ';' (N, ADR, ORG). */
        val parts: List<String> get() = splitUnescaped(rawValue, ';').map(::unescape)
        val text: String get() = unescape(rawValue)
    }

    fun parse(content: String): List<Prefill> {
        val cards = ArrayList<Prefill>()
        var props: MutableList<Property>? = null
        for (line in logicalLines(content)) {
            val prop = parseLine(line) ?: continue
            when {
                prop.name == "BEGIN" && prop.rawValue.equals("VCARD", ignoreCase = true) -> props = ArrayList()
                prop.name == "END" && prop.rawValue.equals("VCARD", ignoreCase = true) -> {
                    props?.let { p -> toPrefill(p)?.let { cards += it } }
                    props = null
                }
                else -> props?.add(prop)
            }
        }
        return cards
    }

    // ---- Lines ----

    /** Joins folded lines and quoted-printable soft breaks. */
    private fun logicalLines(content: String): List<String> {
        val out = ArrayList<String>()
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (line in lines) {
            val last = out.lastOrNull()
            when {
                last != null && (line.startsWith(" ") || line.startsWith("\t")) ->
                    out[out.size - 1] = last + line.substring(1)
                last != null && last.endsWith("=") && isQuotedPrintable(last) ->
                    out[out.size - 1] = last.dropLast(1) + line
                line.isNotBlank() -> out += line
            }
        }
        return out
    }

    private fun isQuotedPrintable(line: String): Boolean {
        val head = line.substringBefore(':').uppercase(Locale.ROOT)
        return "QUOTED-PRINTABLE" in head
    }

    private fun parseLine(line: String): Property? {
        val colon = indexOutsideQuotes(line, ':')
        if (colon < 0) return null
        val head = line.substring(0, colon)
        var value = line.substring(colon + 1)
        val pieces = splitUnescaped(head, ';')
        // Drop "item1." style groups.
        val name = pieces[0].substringAfterLast('.').uppercase(Locale.ROOT)

        val params = HashMap<String, MutableList<String>>()
        for (p in pieces.drop(1)) {
            val eq = p.indexOf('=')
            if (eq < 0) {
                // vCard 2.1 bare params, e.g. TEL;CELL.
                val bare = p.uppercase(Locale.ROOT)
                if (bare == "QUOTED-PRINTABLE" || bare == "BASE64" || bare == "B") {
                    params.getOrPut("ENCODING") { ArrayList() } += bare
                } else {
                    params.getOrPut("TYPE") { ArrayList() } += p
                }
            } else {
                val key = p.substring(0, eq).uppercase(Locale.ROOT)
                params.getOrPut(key) { ArrayList() } += p.substring(eq + 1).trim('"')
            }
        }

        val encoding = params["ENCODING"]?.firstOrNull()?.uppercase(Locale.ROOT)
        if (encoding == "BASE64" || encoding == "B") return null  // Binary, e.g. photo.
        if (encoding == "QUOTED-PRINTABLE") {
            val charset = params["CHARSET"]?.firstOrNull()
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            value = decodeQuotedPrintable(value, charset)
        }
        return Property(name, params, value)
    }

    private fun indexOutsideQuotes(s: String, ch: Char): Int {
        var quoted = false
        s.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            if (c == ch && !quoted) return i
        }
        return -1
    }

    private fun splitUnescaped(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (c == sep) {
                out += sb.toString()
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                sb.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().trim()
    }

    private fun decodeQuotedPrintable(s: String, charset: Charset): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) {
                    bytes.write(b)
                    i += 3
                    continue
                }
            }
            bytes.write(c.code and 0xFF)
            i++
        }
        return String(bytes.toByteArray(), charset)
    }

    // ---- Contact ----

    private fun toPrefill(props: List<Property>): Prefill? {
        var p = Prefill()
        var fullName: String? = null
        val items = ArrayList<PrefillItem>()

        for (prop in props) {
            when (prop.name) {
                "FN" -> fullName = prop.text
                "N" -> {
                    val n = prop.parts + List(5) { "" }
                    p = p.copy(last = n[0], first = n[1], middle = n[2], prefix = n[3], suffix = n[4])
                }
                "ORG" -> {
                    val o = prop.parts + listOf("", "")
                    p = p.copy(company = o[0], department = o.drop(1).filter { it.isNotBlank() }.joinToString(", "))
                }
                "TITLE" -> p = p.copy(title = prop.text)
                "TEL" -> {
                    val (type, label) = Prefill.typeFromWords(EditKind.PHONES, prop.types)
                    items += PrefillItem(EditKind.PHONES, type, label, prop.text.removePrefix("tel:"))
                }
                "EMAIL" -> {
                    val (type, label) = Prefill.typeFromWords(EditKind.EMAILS, prop.types)
                    items += PrefillItem(EditKind.EMAILS, type, label, prop.text)
                }
                "ADR" -> {
                    // PO box; extended; street; city; region; postcode; country
                    val a = prop.parts + List(7) { "" }
                    val cityLine = listOf(a[3], a[4], a[5]).filter { it.isNotBlank() }.joinToString(" ")
                    val address = listOf(a[0], a[1], a[2], cityLine, a[6]).filter { it.isNotBlank() }.joinToString("\n")
                    val (type, label) = Prefill.typeFromWords(EditKind.ADDRESSES, prop.types)
                    items += PrefillItem(EditKind.ADDRESSES, type, label, address)
                }
                "URL" -> items += PrefillItem(EditKind.WEBSITES, EditKind.WEBSITES.defaultOption?.type ?: 0, null, prop.text)
                "NOTE" -> items += PrefillItem(EditKind.NOTES, -1, null, prop.text)
                "NICKNAME" -> splitUnescaped(prop.rawValue, ',').map(::unescape).forEach {
                    items += PrefillItem(EditKind.NICKNAMES, EditKind.NICKNAMES.defaultOption?.type ?: 0, null, it)
                }
                "BDAY" -> date(prop.text)?.let { items += PrefillItem(EditKind.DATES, Event.TYPE_BIRTHDAY, null, it) }
                "ANNIVERSARY", "X-ANNIVERSARY" ->
                    date(prop.text)?.let { items += PrefillItem(EditKind.DATES, Event.TYPE_ANNIVERSARY, null, it) }
                "IMPP" -> {
                    val value = prop.text
                    val scheme = value.substringBefore(':', "")
                    val service = prop.params["X-SERVICE-TYPE"]?.firstOrNull() ?: scheme
                    val t = Prefill.imProtocol(service.ifEmpty { "Other" })
                    items += PrefillItem(t.kind, t.type, t.label, if (scheme.isNotEmpty()) value.substringAfter(':') else value)
                }
                "X-SIP" -> items += PrefillItem(EditKind.SIP, EditKind.SIP.defaultOption?.type ?: 0, null, prop.text.removePrefix("sip:"))
                "X-JABBER", "X-SKYPE", "X-SKYPE-USERNAME", "X-AIM", "X-MSN", "X-YAHOO", "X-QQ", "X-GOOGLE-TALK", "X-ICQ" -> {
                    val t = Prefill.imProtocol(prop.name.removePrefix("X-"))
                    items += PrefillItem(t.kind, t.type, t.label, prop.text)
                }
                "RELATED" -> {
                    val (type, label) = relationType(prop.types)
                    items += PrefillItem(EditKind.RELATIONS, type, label, prop.text)
                }
                // Android export: <mimetype>;data1;data2;data3
                "X-ANDROID-CUSTOM" -> {
                    val d = prop.parts + List(4) { "" }
                    val kind = EditKind.entries.firstOrNull { it.mime == d[0] } ?: continue
                    if (d[1].isBlank()) continue
                    val value = if (kind == EditKind.DATES) date(d[1]) ?: continue else d[1]
                    val type = d[2].toIntOrNull() ?: kind.defaultOption?.type ?: 0
                    items += PrefillItem(kind, type, d[3].ifBlank { null }, value)
                }
            }
        }

        if (listOf(p.first, p.middle, p.last).all { it.isBlank() } && !fullName.isNullOrBlank()) {
            val (first, last) = Prefill.splitName(fullName)
            p = p.copy(first = first, last = last)
        }
        val result = p.copy(items = items.filter { it.value.isNotBlank() })
        return result.takeIf { it != Prefill() }
    }

    private fun relationType(types: Set<String>): Pair<Int, String?> {
        val t = types.firstOrNull { it != "PREF" }?.lowercase(Locale.ROOT) ?: return Relation.TYPE_RELATIVE to null
        return when (t) {
            "spouse" -> Relation.TYPE_SPOUSE to null
            "partner", "sweetheart" -> Relation.TYPE_PARTNER to null
            "child" -> Relation.TYPE_CHILD to null
            "parent" -> Relation.TYPE_PARENT to null
            "friend" -> Relation.TYPE_FRIEND to null
            "kin", "sibling" -> Relation.TYPE_RELATIVE to null
            else -> Relation.TYPE_CUSTOM to t.replaceFirstChar { it.uppercase() }
        }
    }

    /** vCard date → MM/DD/YYYY or MM/DD; null if unreadable. */
    private fun date(raw: String): String? {
        val s = raw.trim().substringBefore('T')
        val iso = when {
            Regex("""\d{4}-\d{2}-\d{2}""").matches(s) -> s
            Regex("""\d{8}""").matches(s) -> "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
            Regex("""--\d{2}-\d{2}""").matches(s) -> s
            Regex("""--\d{4}""").matches(s) -> "--${s.substring(2, 4)}-${s.substring(4, 6)}"
            else -> s
        }
        return ContactWriter.dateForStorage(iso)?.let { ContactWriter.dateForEditing(it) }
    }
}
