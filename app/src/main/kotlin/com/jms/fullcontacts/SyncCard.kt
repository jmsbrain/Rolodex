@file:Suppress("DEPRECATION") // Im, SipAddress

package com.jms.fullcontacts

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale

/** One contact data row: column → value. Blank values are left out so rows compare cleanly. */
data class SyncRow(val mime: String, val values: Map<String, String>) {
    operator fun get(column: String): String? = values[column]

    val sortKey: String get() = mime + values.toSortedMap()

    /** Drops labels that only matter for custom types. */
    fun normalized(): SyncRow {
        val v = values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }.toMutableMap()
        when (mime) {
            Im.CONTENT_ITEM_TYPE -> if (v[Im.PROTOCOL] != Im.PROTOCOL_CUSTOM.toString()) v.remove(Im.CUSTOM_PROTOCOL)
            Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE,
            Event.CONTENT_ITEM_TYPE, Relation.CONTENT_ITEM_TYPE,
            -> if (v["data2"] != "0") v.remove("data3")
        }
        return SyncRow(mime, v)
    }

    companion object {
        fun of(mime: String, vararg pairs: Pair<String, Any?>) = SyncRow(
            mime,
            pairs.mapNotNull { (k, v) -> v?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap(),
        ).normalized()
    }
}

/** What Rolodex syncs, per mimetype: the columns read and written. */
object SyncFields {
    val columns: Map<String, List<String>> = linkedMapOf(
        StructuredName.CONTENT_ITEM_TYPE to listOf(
            StructuredName.DISPLAY_NAME, StructuredName.PREFIX, StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME,
            StructuredName.FAMILY_NAME, StructuredName.SUFFIX, StructuredName.PHONETIC_GIVEN_NAME,
            StructuredName.PHONETIC_MIDDLE_NAME, StructuredName.PHONETIC_FAMILY_NAME,
        ),
        Organization.CONTENT_ITEM_TYPE to listOf(Organization.COMPANY, Organization.TITLE, Organization.DEPARTMENT, Organization.JOB_DESCRIPTION),
        Phone.CONTENT_ITEM_TYPE to listOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL),
        Email.CONTENT_ITEM_TYPE to listOf(Email.ADDRESS, Email.TYPE, Email.LABEL),
        StructuredPostal.CONTENT_ITEM_TYPE to listOf(
            StructuredPostal.FORMATTED_ADDRESS, StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD,
            StructuredPostal.CITY, StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY,
            StructuredPostal.TYPE, StructuredPostal.LABEL,
        ),
        Nickname.CONTENT_ITEM_TYPE to listOf(Nickname.NAME),
        Website.CONTENT_ITEM_TYPE to listOf(Website.URL),
        Event.CONTENT_ITEM_TYPE to listOf(Event.START_DATE, Event.TYPE, Event.LABEL),
        Im.CONTENT_ITEM_TYPE to listOf(Im.DATA, Im.PROTOCOL, Im.CUSTOM_PROTOCOL),
        SipAddress.CONTENT_ITEM_TYPE to listOf(SipAddress.SIP_ADDRESS),
        Relation.CONTENT_ITEM_TYPE to listOf(Relation.NAME, Relation.TYPE, Relation.LABEL),
        Note.CONTENT_ITEM_TYPE to listOf(Note.NOTE),
    )
    val mimes: Set<String> get() = columns.keys

    /** Same rows, in any order. */
    fun same(a: List<SyncRow>, b: List<SyncRow>): Boolean =
        a.size == b.size && a.map { it.sortKey }.sorted() == b.map { it.sortKey }.sorted()

    fun byMime(rows: List<SyncRow>): Map<String, List<SyncRow>> = rows.groupBy { it.mime }
}

/** One vCard line. [rawValue] is still escaped; [line] is the unfolded original, written back as is. */
class VProp(
    val group: String?,
    val name: String,
    val params: List<Pair<String, String>>,
    val rawValue: String,
    val line: String,
) {
    fun param(key: String): List<String> = params.filter { it.first == key }.map { it.second }

    val types: Set<String>
        get() = param("TYPE").flatMap { it.split(',') }.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()

    val text: String get() = vUnescape(rawValue)
    val parts: List<String> get() = vSplit(rawValue, ';').map(::vUnescape)

    companion object {
        fun build(group: String?, name: String, params: List<Pair<String, String>>, rawValue: String): VProp {
            val head = buildString {
                if (group != null) append(group).append('.')
                append(name)
                for ((k, v) in params) append(';').append(k).append('=').append(v)
            }
            return VProp(group, name, params, rawValue, "$head:$rawValue")
        }

        fun text(name: String, value: String, group: String? = null, params: List<Pair<String, String>> = emptyList()) =
            build(group, name, params, vEscape(value))

        fun structured(name: String, parts: List<String>) = build(null, name, emptyList(), parts.joinToString(";") { vEscape(it) })
    }
}

/** A vCard, kept line by line so whatever Rolodex doesn't edit goes back to the server unchanged. */
class VCardDoc(val props: List<VProp>) {

    fun first(name: String): VProp? = props.firstOrNull { it.name == name }

    val version: String get() = first("VERSION")?.text ?: "3.0"
    val uid: String? get() = first("UID")?.text?.takeIf { it.isNotBlank() }

    /** Contact groups are stored as cards too. */
    val isGroup: Boolean
        get() = props.any { (it.name == "KIND" || it.name == "X-ADDRESSBOOKSERVER-KIND") && it.text.equals("group", ignoreCase = true) }

    fun text(): String = buildString {
        append("BEGIN:VCARD\r\n")
        props.forEach { append(fold(it.line)) }
        append("END:VCARD\r\n")
    }

    /** Without photos and other binary data: small enough to keep as the last-synced copy. */
    fun withoutBinary(): VCardDoc = VCardDoc(props.filter { it.name !in BINARY })

    // ---- vCard → rows ----

    fun rows(): List<SyncRow> {
        val labels = props.filter { it.name == "X-ABLABEL" && it.group != null }
            .associate { it.group!!.uppercase(Locale.ROOT) to appleLabel(it.text) }
        fun label(p: VProp): String? = p.group?.let { labels[it.uppercase(Locale.ROOT)] }
        val out = ArrayList<SyncRow>()

        val n = first("N")?.parts.orEmpty() + List(5) { "" }
        val name = SyncRow.of(
            StructuredName.CONTENT_ITEM_TYPE,
            StructuredName.DISPLAY_NAME to first("FN")?.text,
            StructuredName.FAMILY_NAME to n[0], StructuredName.GIVEN_NAME to n[1], StructuredName.MIDDLE_NAME to n[2],
            StructuredName.PREFIX to n[3], StructuredName.SUFFIX to n[4],
            StructuredName.PHONETIC_GIVEN_NAME to first("X-PHONETIC-FIRST-NAME")?.text,
            StructuredName.PHONETIC_MIDDLE_NAME to first("X-PHONETIC-MIDDLE-NAME")?.text,
            StructuredName.PHONETIC_FAMILY_NAME to first("X-PHONETIC-LAST-NAME")?.text,
        )
        if (name.values.isNotEmpty()) out += name

        val org = first("ORG")?.parts.orEmpty()
        val work = SyncRow.of(
            Organization.CONTENT_ITEM_TYPE,
            Organization.COMPANY to org.firstOrNull(),
            Organization.DEPARTMENT to org.drop(1).filter { it.isNotBlank() }.joinToString(", "),
            Organization.TITLE to first("TITLE")?.text,
            Organization.JOB_DESCRIPTION to first("ROLE")?.text,
        )
        if (work.values.isNotEmpty()) out += work

        for (p in props) {
            when (p.name) {
                "TEL" -> {
                    val (type, custom) = phoneType(p.types, label(p))
                    out += SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to p.text.removePrefix("tel:"), Phone.TYPE to type, Phone.LABEL to custom)
                }
                "EMAIL" -> {
                    val (type, custom) = simpleType(p.types, label(p), EMAIL_TYPES, Email.TYPE_HOME)
                    out += SyncRow.of(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to p.text.removePrefix("mailto:"), Email.TYPE to type, Email.LABEL to custom)
                }
                "ADR" -> {
                    // PO box; extended; street; city; region; postcode; country
                    val a = p.parts + List(7) { "" }
                    val (type, custom) = simpleType(p.types, label(p), POSTAL_TYPES, StructuredPostal.TYPE_HOME)
                    out += SyncRow.of(
                        StructuredPostal.CONTENT_ITEM_TYPE,
                        StructuredPostal.POBOX to a[0], StructuredPostal.NEIGHBORHOOD to a[1], StructuredPostal.STREET to a[2],
                        StructuredPostal.CITY to a[3], StructuredPostal.REGION to a[4], StructuredPostal.POSTCODE to a[5],
                        StructuredPostal.COUNTRY to a[6], StructuredPostal.TYPE to type, StructuredPostal.LABEL to custom,
                    )
                }
                "NICKNAME" -> vSplit(p.rawValue, ',').map(::vUnescape).filter { it.isNotBlank() }.forEach {
                    out += SyncRow.of(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME to it)
                }
                "URL" -> out += SyncRow.of(Website.CONTENT_ITEM_TYPE, Website.URL to p.text)
                "NOTE" -> out += SyncRow.of(Note.CONTENT_ITEM_TYPE, Note.NOTE to p.text)
                "BDAY" -> out += eventRow(p.text, Event.TYPE_BIRTHDAY, null, p)
                "ANNIVERSARY", "X-ANNIVERSARY" -> out += eventRow(p.text, Event.TYPE_ANNIVERSARY, null, p)
                "X-ABDATE" -> {
                    val (type, custom) = eventType(label(p))
                    out += eventRow(p.text, type, custom, p)
                }
                "IMPP" -> {
                    val value = p.text
                    val scheme = value.substringBefore(':', "").lowercase(Locale.ROOT)
                    val handle = if (scheme.isNotEmpty()) value.substringAfter(':') else value
                    if (scheme == "sip") {
                        out += SyncRow.of(SipAddress.CONTENT_ITEM_TYPE, SipAddress.SIP_ADDRESS to handle)
                    } else {
                        val service = p.param("X-SERVICE-TYPE").firstOrNull() ?: label(p) ?: scheme.removePrefix("x-")
                        out += imRow(service.ifEmpty { "Other" }, handle)
                    }
                }
                "X-SIP" -> out += SyncRow.of(SipAddress.CONTENT_ITEM_TYPE, SipAddress.SIP_ADDRESS to p.text.removePrefix("sip:"))
                in IM_PROPS -> out += imRow(p.name.removePrefix("X-"), p.text)
                "RELATED", "X-ABRELATEDNAMES" -> {
                    val (type, custom) = relationType(p.types, label(p))
                    out += SyncRow.of(Relation.CONTENT_ITEM_TYPE, Relation.NAME to p.text, Relation.TYPE to type, Relation.LABEL to custom)
                }
                // Android export: <mimetype>;data1;data2;data3
                "X-ANDROID-CUSTOM" -> {
                    val d = p.parts + List(4) { "" }
                    when (d[0]) {
                        Nickname.CONTENT_ITEM_TYPE -> out += SyncRow.of(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME to d[1])
                        Relation.CONTENT_ITEM_TYPE ->
                            out += SyncRow.of(Relation.CONTENT_ITEM_TYPE, Relation.NAME to d[1], Relation.TYPE to d[2], Relation.LABEL to d[3])
                        Event.CONTENT_ITEM_TYPE ->
                            out += SyncRow.of(Event.CONTENT_ITEM_TYPE, Event.START_DATE to normalizeDate(d[1]), Event.TYPE to d[2], Event.LABEL to d[3])
                    }
                }
            }
        }
        // Drop lines with no value, e.g. an empty TEL.
        return out.filter { row ->
            when (row.mime) {
                StructuredName.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE -> true
                StructuredPostal.CONTENT_ITEM_TYPE -> row.values.keys.any { it != StructuredPostal.TYPE && it != StructuredPostal.LABEL }
                else -> row["data1"] != null
            }
        }
    }

    private fun eventRow(raw: String, type: Int, label: String?, p: VProp): SyncRow {
        // Apple marks "no year" with year 1604.
        val omitYear = p.param("X-APPLE-OMIT-YEAR").isNotEmpty()
        val date = normalizeDate(raw).let { if (omitYear && !it.startsWith("--") && it.length >= 10) "--" + it.substring(5, 10) else it }
        return SyncRow.of(Event.CONTENT_ITEM_TYPE, Event.START_DATE to date, Event.TYPE to type, Event.LABEL to label)
    }

    private fun imRow(service: String, handle: String): SyncRow {
        val t = Prefill.imProtocol(service)
        if (t.kind == EditKind.SIP) return SyncRow.of(SipAddress.CONTENT_ITEM_TYPE, SipAddress.SIP_ADDRESS to handle)
        return SyncRow.of(Im.CONTENT_ITEM_TYPE, Im.DATA to handle, Im.PROTOCOL to t.type, Im.CUSTOM_PROTOCOL to t.label)
    }

    // ---- rows → vCard ----

    /** This card with every line of the kinds in [replace] swapped for those rows. */
    fun patched(replace: Map<String, List<SyncRow>>): VCardDoc {
        if (replace.isEmpty()) return this
        val kept = props.filter { mimeOf(it) !in replace.keys }
        val usedGroups = kept.filter { it.name != "X-ABLABEL" }.mapNotNull { it.group?.uppercase(Locale.ROOT) }.toSet()
        val cleaned = kept.filter { it.name != "X-ABLABEL" || it.group?.uppercase(Locale.ROOT) in usedGroups }
        var nextGroup = (props.mapNotNull { it.group?.lowercase(Locale.ROOT)?.removePrefix("item")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val renderer = Renderer(version) { "item${nextGroup++}" }
        val added = replace.entries.sortedBy { SyncFields.mimes.indexOf(it.key) }.flatMap { (mime, rows) -> renderer.render(mime, rows) }
        val result = ArrayList<VProp>(cleaned.size + added.size + 2)
        result += cleaned
        result += added
        // FN and N are required.
        if (result.none { it.name == "FN" }) {
            val company = VCardDoc(result).rows().firstOrNull { it.mime == Organization.CONTENT_ITEM_TYPE }?.get(Organization.COMPANY)
            result += VProp.text("FN", company.orEmpty())
        }
        if (result.none { it.name == "N" }) result += VProp.structured("N", List(5) { "" })
        return VCardDoc(result)
    }

    private class Renderer(private val version: String, private val newGroup: () -> String) {

        fun render(mime: String, rows: List<SyncRow>): List<VProp> = when (mime) {
            StructuredName.CONTENT_ITEM_TYPE -> name(rows.firstOrNull())
            Organization.CONTENT_ITEM_TYPE -> org(rows.firstOrNull())
            else -> rows.flatMap { one(it) }
        }

        private fun name(r: SyncRow?): List<VProp> {
            val parts = listOf(StructuredName.FAMILY_NAME, StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME, StructuredName.PREFIX, StructuredName.SUFFIX)
                .map { r?.get(it).orEmpty() }
            val joined = listOf(parts[3], parts[1], parts[2], parts[0], parts[4]).filter { it.isNotBlank() }.joinToString(" ")
            val out = arrayListOf(
                VProp.text("FN", r?.get(StructuredName.DISPLAY_NAME) ?: joined),
                VProp.structured("N", parts),
            )
            r?.get(StructuredName.PHONETIC_GIVEN_NAME)?.let { out += VProp.text("X-PHONETIC-FIRST-NAME", it) }
            r?.get(StructuredName.PHONETIC_MIDDLE_NAME)?.let { out += VProp.text("X-PHONETIC-MIDDLE-NAME", it) }
            r?.get(StructuredName.PHONETIC_FAMILY_NAME)?.let { out += VProp.text("X-PHONETIC-LAST-NAME", it) }
            return out
        }

        private fun org(r: SyncRow?): List<VProp> {
            if (r == null) return emptyList()
            val out = ArrayList<VProp>()
            val company = r[Organization.COMPANY].orEmpty()
            val dept = r[Organization.DEPARTMENT].orEmpty()
            if (company.isNotEmpty() || dept.isNotEmpty()) out += VProp.structured("ORG", listOfNotNull(company, dept.ifEmpty { null }))
            r[Organization.TITLE]?.let { out += VProp.text("TITLE", it) }
            r[Organization.JOB_DESCRIPTION]?.let { out += VProp.text("ROLE", it) }
            return out
        }

        /** A property, plus an X-ABLabel line when the type is a custom name. */
        private fun labelled(name: String, value: String, types: List<String>, label: String?, extra: List<Pair<String, String>> = emptyList(), escape: Boolean = true): List<VProp> {
            val params = (if (types.isEmpty()) emptyList() else listOf("TYPE" to types.joinToString(","))) + extra
            val raw = if (escape) vEscape(value) else value
            if (label == null) return listOf(VProp.build(null, name, params, raw))
            val g = newGroup()
            return listOf(VProp.build(g, name, params, raw), VProp.text("X-ABLabel", label, group = g))
        }

        private fun typeOf(r: SyncRow) = r["data2"]?.toIntOrNull() ?: 0

        private fun one(r: SyncRow): List<VProp> = when (r.mime) {
            Phone.CONTENT_ITEM_TYPE -> {
                val type = typeOf(r)
                val words = PHONE_WORDS[type]
                if (words != null) labelled("TEL", r[Phone.NUMBER]!!, words, null)
                else labelled("TEL", r[Phone.NUMBER]!!, emptyList(), if (type == 0) r[Phone.LABEL] ?: "Other" else PHONE_LABELS[type] ?: "Other")
            }
            Email.CONTENT_ITEM_TYPE -> typed("EMAIL", r[Email.ADDRESS]!!, typeOf(r), r[Email.LABEL], EMAIL_TYPES, listOf("INTERNET"))
            StructuredPostal.CONTENT_ITEM_TYPE -> {
                val street = r[StructuredPostal.STREET]
                val anyPart = listOf(StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD, StructuredPostal.CITY, StructuredPostal.REGION,
                    StructuredPostal.POSTCODE, StructuredPostal.COUNTRY).any { r[it] != null }
                val parts = listOf(
                    r[StructuredPostal.POBOX], r[StructuredPostal.NEIGHBORHOOD],
                    street ?: if (anyPart) null else r[StructuredPostal.FORMATTED_ADDRESS],
                    r[StructuredPostal.CITY], r[StructuredPostal.REGION], r[StructuredPostal.POSTCODE], r[StructuredPostal.COUNTRY],
                ).map { vEscape(it.orEmpty()) }
                val (types, label) = typeWords(typeOf(r), r[StructuredPostal.LABEL], POSTAL_TYPES)
                labelled("ADR", parts.joinToString(";"), types, label, escape = false)
            }
            Nickname.CONTENT_ITEM_TYPE -> listOf(VProp.text("NICKNAME", r[Nickname.NAME]!!))
            Website.CONTENT_ITEM_TYPE -> listOf(VProp.build(null, "URL", emptyList(), vEscapeUri(r[Website.URL]!!)))
            Note.CONTENT_ITEM_TYPE -> listOf(VProp.text("NOTE", r[Note.NOTE]!!))
            Event.CONTENT_ITEM_TYPE -> {
                val date = r[Event.START_DATE]!!
                when (typeOf(r)) {
                    Event.TYPE_BIRTHDAY -> listOf(VProp.build(null, "BDAY", emptyList(), date))
                    Event.TYPE_ANNIVERSARY -> listOf(VProp.build(null, if (version.startsWith("4")) "ANNIVERSARY" else "X-ANNIVERSARY", emptyList(), date))
                    Event.TYPE_OTHER -> labelled("X-ABDATE", date, emptyList(), "Other", escape = false)
                    else -> labelled("X-ABDATE", date, emptyList(), r[Event.LABEL] ?: "Other", escape = false)
                }
            }
            Im.CONTENT_ITEM_TYPE -> {
                val protocol = r[Im.PROTOCOL]?.toIntOrNull() ?: Im.PROTOCOL_CUSTOM
                val (service, scheme) = IM_SERVICES[protocol] ?: (r[Im.CUSTOM_PROTOCOL] ?: "Other").let { it to customScheme(it) }
                listOf(VProp.build(null, "IMPP", listOf("X-SERVICE-TYPE" to service), "$scheme:${vEscapeUri(r[Im.DATA]!!)}"))
            }
            SipAddress.CONTENT_ITEM_TYPE -> listOf(VProp.build(null, "IMPP", emptyList(), "sip:${vEscapeUri(r[SipAddress.SIP_ADDRESS]!!)}"))
            Relation.CONTENT_ITEM_TYPE -> {
                val type = typeOf(r)
                val word = RELATION_WORDS[type]
                val text = listOf("VALUE" to "text")
                if (word != null) labelled("RELATED", r[Relation.NAME]!!, listOf(word), null, text)
                else labelled("RELATED", r[Relation.NAME]!!, emptyList(), if (type == 0) r[Relation.LABEL] ?: "Other" else RELATION_LABELS[type] ?: "Other", text)
            }
            else -> emptyList()
        }

        private fun typed(name: String, value: String, type: Int, label: String?, table: Map<Int, String>, always: List<String> = emptyList()): List<VProp> {
            val (types, custom) = typeWords(type, label, table)
            return labelled(name, value, always + types, custom)
        }

        private fun typeWords(type: Int, label: String?, table: Map<Int, String>): Pair<List<String>, String?> = when {
            type == 0 -> emptyList<String>() to (label ?: "Other")
            table[type] != null -> listOf(table.getValue(type)) to null
            else -> emptyList<String>() to "Other"
        }
    }

    companion object {
        private val BINARY = setOf("PHOTO", "LOGO", "SOUND", "KEY")
        private val IM_PROPS = setOf("X-JABBER", "X-SKYPE", "X-SKYPE-USERNAME", "X-AIM", "X-MSN", "X-YAHOO", "X-QQ", "X-GOOGLE-TALK", "X-ICQ")

        /** Which synced mimetype a line belongs to; null if Rolodex leaves it alone. */
        fun mimeOf(p: VProp): String? = when (p.name) {
            "N", "FN", "X-PHONETIC-FIRST-NAME", "X-PHONETIC-MIDDLE-NAME", "X-PHONETIC-LAST-NAME" -> StructuredName.CONTENT_ITEM_TYPE
            "ORG", "TITLE", "ROLE" -> Organization.CONTENT_ITEM_TYPE
            "TEL" -> Phone.CONTENT_ITEM_TYPE
            "EMAIL" -> Email.CONTENT_ITEM_TYPE
            "ADR" -> StructuredPostal.CONTENT_ITEM_TYPE
            "NICKNAME" -> Nickname.CONTENT_ITEM_TYPE
            "URL" -> Website.CONTENT_ITEM_TYPE
            "NOTE" -> Note.CONTENT_ITEM_TYPE
            "BDAY", "ANNIVERSARY", "X-ANNIVERSARY", "X-ABDATE" -> Event.CONTENT_ITEM_TYPE
            "IMPP" -> if (p.text.startsWith("sip:", ignoreCase = true)) SipAddress.CONTENT_ITEM_TYPE else Im.CONTENT_ITEM_TYPE
            "X-SIP" -> SipAddress.CONTENT_ITEM_TYPE
            in IM_PROPS -> Im.CONTENT_ITEM_TYPE
            "RELATED", "X-ABRELATEDNAMES" -> Relation.CONTENT_ITEM_TYPE
            "X-ANDROID-CUSTOM" -> p.parts.firstOrNull()?.takeIf { it in setOf(Nickname.CONTENT_ITEM_TYPE, Relation.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE) }
            else -> null
        }

        /** A new card (vCard 3.0) from the phone's rows. */
        fun fromRows(rows: List<SyncRow>, uid: String): VCardDoc {
            val base = VCardDoc(listOf(VProp.text("VERSION", "3.0"), VProp.text("UID", uid), VProp.text("PRODID", "-//Rolodex//EN")))
            val byMime = SyncFields.byMime(rows)
            return base.patched(SyncFields.mimes.associateWith { byMime[it].orEmpty() })
        }

        fun parse(content: String): List<VCardDoc> {
            val cards = ArrayList<VCardDoc>()
            var props: MutableList<VProp>? = null
            for (line in logicalLines(content)) {
                val prop = parseLine(line) ?: continue
                when {
                    prop.name == "BEGIN" && prop.rawValue.equals("VCARD", ignoreCase = true) -> props = ArrayList()
                    prop.name == "END" && prop.rawValue.equals("VCARD", ignoreCase = true) -> {
                        props?.let { cards += VCardDoc(it) }
                        props = null
                    }
                    else -> props?.add(prop)
                }
            }
            return cards
        }

        fun parseOne(content: String): VCardDoc? = parse(content).firstOrNull()

        // ---- types ----

        /** Phone types as vCard words. Uses the words OX (mailbox.org) maps to its fixed number fields. */
        private val PHONE_WORDS = mapOf(
            Phone.TYPE_MOBILE to listOf("CELL"), Phone.TYPE_HOME to listOf("HOME", "VOICE"), Phone.TYPE_WORK to listOf("WORK", "VOICE"),
            Phone.TYPE_FAX_WORK to listOf("WORK", "FAX"), Phone.TYPE_FAX_HOME to listOf("HOME", "FAX"), Phone.TYPE_OTHER_FAX to listOf("FAX", "X-OTHER"),
            Phone.TYPE_PAGER to listOf("PAGER"), Phone.TYPE_WORK_PAGER to listOf("WORK", "PAGER"), Phone.TYPE_WORK_MOBILE to listOf("WORK", "CELL"),
            Phone.TYPE_CAR to listOf("CAR"), Phone.TYPE_ISDN to listOf("ISDN"), Phone.TYPE_OTHER to listOf("X-OTHER"),
            Phone.TYPE_MAIN to listOf("X-PRIMARY"), Phone.TYPE_CALLBACK to listOf("X-CALLBACK"), Phone.TYPE_COMPANY_MAIN to listOf("X-COMPANY"),
            Phone.TYPE_RADIO to listOf("X-RADIO"), Phone.TYPE_ASSISTANT to listOf("X-ASSISTANT"), Phone.TYPE_TTY_TDD to listOf("TEXTPHONE"),
        )

        /** Phone types with no vCard word; written as a label and read back by name. */
        private val PHONE_LABELS = mapOf(
            Phone.TYPE_MAIN to "Main", Phone.TYPE_CALLBACK to "Callback", Phone.TYPE_COMPANY_MAIN to "Company main",
            Phone.TYPE_RADIO to "Radio", Phone.TYPE_TELEX to "Telex", Phone.TYPE_TTY_TDD to "TTY/TDD",
            Phone.TYPE_ASSISTANT to "Assistant", Phone.TYPE_MMS to "MMS",
        )

        private val EMAIL_TYPES = mapOf(Email.TYPE_HOME to "HOME", Email.TYPE_WORK to "WORK", Email.TYPE_OTHER to "OTHER", Email.TYPE_MOBILE to "CELL")
        private val POSTAL_TYPES = mapOf(StructuredPostal.TYPE_HOME to "HOME", StructuredPostal.TYPE_WORK to "WORK", StructuredPostal.TYPE_OTHER to "OTHER")

        private val RELATION_WORDS = mapOf(
            Relation.TYPE_SPOUSE to "spouse", Relation.TYPE_CHILD to "child", Relation.TYPE_PARENT to "parent",
            Relation.TYPE_FRIEND to "friend", Relation.TYPE_PARTNER to "sweetheart", Relation.TYPE_RELATIVE to "kin",
        )
        private val RELATION_LABELS = mapOf(
            Relation.TYPE_MOTHER to "Mother", Relation.TYPE_FATHER to "Father", Relation.TYPE_BROTHER to "Brother",
            Relation.TYPE_SISTER to "Sister", Relation.TYPE_ASSISTANT to "Assistant", Relation.TYPE_MANAGER to "Manager",
            Relation.TYPE_DOMESTIC_PARTNER to "Domestic partner", Relation.TYPE_REFERRED_BY to "Referred by",
        )

        private val IM_SERVICES = mapOf(
            Im.PROTOCOL_AIM to ("AIM" to "aim"), Im.PROTOCOL_MSN to ("MSN" to "msnim"), Im.PROTOCOL_YAHOO to ("Yahoo" to "ymsgr"),
            Im.PROTOCOL_SKYPE to ("Skype" to "skype"), Im.PROTOCOL_QQ to ("QQ" to "x-apple"), Im.PROTOCOL_GOOGLE_TALK to ("GoogleTalk" to "xmpp"),
            Im.PROTOCOL_ICQ to ("ICQ" to "aim"), Im.PROTOCOL_JABBER to ("Jabber" to "xmpp"),
        )

        private fun customScheme(service: String): String = when (service.lowercase(Locale.ROOT)) {
            "signal" -> "sgnl"
            "telegram" -> "tg"
            "whatsapp" -> "whatsapp"
            "matrix" -> "matrix"
            else -> "x-" + service.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.ifEmpty { "other" }
        }

        /** Apple writes built-in labels as _$!<Mobile>!$_. */
        private fun appleLabel(raw: String): String = raw.removePrefix("_\$!<").removeSuffix(">!\$_").trim()

        private fun phoneType(types: Set<String>, label: String?): Pair<Int, String?> {
            if (label != null) {
                val l = label.lowercase(Locale.ROOT)
                PHONE_LABELS.entries.firstOrNull { it.value.lowercase(Locale.ROOT) == l }?.let { return it.key to null }
                return when (l) {
                    "mobile", "cell" -> Phone.TYPE_MOBILE to null
                    "home" -> Phone.TYPE_HOME to null
                    "work" -> Phone.TYPE_WORK to null
                    "other" -> Phone.TYPE_OTHER to null
                    "pager" -> Phone.TYPE_PAGER to null
                    "homefax", "home fax" -> Phone.TYPE_FAX_HOME to null
                    "workfax", "work fax" -> Phone.TYPE_FAX_WORK to null
                    else -> Phone.TYPE_CUSTOM to label
                }
            }
            val w = types
            val custom = w.firstOrNull { it.startsWith("X-") }
            return when {
                "FAX" in w && "WORK" in w -> Phone.TYPE_FAX_WORK to null
                "FAX" in w && "HOME" in w -> Phone.TYPE_FAX_HOME to null
                "FAX" in w -> Phone.TYPE_OTHER_FAX to null
                "PAGER" in w && "WORK" in w -> Phone.TYPE_WORK_PAGER to null
                "PAGER" in w -> Phone.TYPE_PAGER to null
                ("CELL" in w || "MOBILE" in w) && "WORK" in w -> Phone.TYPE_WORK_MOBILE to null
                "CELL" in w || "MOBILE" in w || "IPHONE" in w -> Phone.TYPE_MOBILE to null
                "CAR" in w -> Phone.TYPE_CAR to null
                "ISDN" in w -> Phone.TYPE_ISDN to null
                "MAIN" in w || "X-MAIN" in w || "X-PRIMARY" in w -> Phone.TYPE_MAIN to null
                "X-CALLBACK" in w -> Phone.TYPE_CALLBACK to null
                "X-COMPANY" in w -> Phone.TYPE_COMPANY_MAIN to null
                "X-RADIO" in w -> Phone.TYPE_RADIO to null
                "X-ASSISTANT" in w -> Phone.TYPE_ASSISTANT to null
                "TEXTPHONE" in w -> Phone.TYPE_TTY_TDD to null
                "WORK" in w -> Phone.TYPE_WORK to null
                "HOME" in w -> Phone.TYPE_HOME to null
                "OTHER" in w || "X-OTHER" in w -> Phone.TYPE_OTHER to null
                custom != null -> Phone.TYPE_CUSTOM to custom.removePrefix("X-").lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
                else -> Phone.TYPE_MOBILE to null
            }
        }

        private fun simpleType(types: Set<String>, label: String?, table: Map<Int, String>, default: Int): Pair<Int, String?> {
            if (label != null) {
                table.entries.firstOrNull { it.value.equals(label, ignoreCase = true) }?.let { return it.key to null }
                if (label.equals("mobile", ignoreCase = true)) table.entries.firstOrNull { it.value == "CELL" }?.let { return it.key to null }
                return 0 to label
            }
            for (w in types) table.entries.firstOrNull { it.value == w }?.let { return it.key to null }
            if ("MOBILE" in types) table.entries.firstOrNull { it.value == "CELL" }?.let { return it.key to null }
            types.firstOrNull { it.startsWith("X-") }?.let { return 0 to it.removePrefix("X-").lowercase(Locale.ROOT).replaceFirstChar { c -> c.uppercase() } }
            return default to null
        }

        private fun eventType(label: String?): Pair<Int, String?> = when (label?.lowercase(Locale.ROOT)) {
            null, "other" -> Event.TYPE_OTHER to null
            "anniversary" -> Event.TYPE_ANNIVERSARY to null
            "birthday" -> Event.TYPE_BIRTHDAY to null
            else -> Event.TYPE_CUSTOM to label
        }

        private fun relationType(types: Set<String>, label: String?): Pair<Int, String?> {
            val l = label?.lowercase(Locale.ROOT)
            if (l != null) {
                RELATION_LABELS.entries.firstOrNull { it.value.lowercase(Locale.ROOT) == l }?.let { return it.key to null }
                RELATION_WORDS.entries.firstOrNull { it.value == l }?.let { return it.key to null }
                return when (l) {
                    "partner" -> Relation.TYPE_PARTNER to null
                    "relative" -> Relation.TYPE_RELATIVE to null
                    else -> Relation.TYPE_CUSTOM to label
                }
            }
            val t = types.map { it.lowercase(Locale.ROOT) }.firstOrNull { it != "pref" } ?: return Relation.TYPE_RELATIVE to null
            RELATION_WORDS.entries.firstOrNull { it.value == t }?.let { return it.key to null }
            return when (t) {
                "partner" -> Relation.TYPE_PARTNER to null
                else -> Relation.TYPE_CUSTOM to t.replaceFirstChar { it.uppercase() }
            }
        }

        /** vCard date → yyyy-MM-dd or --MM-dd, as Android stores them; anything else is kept. */
        fun normalizeDate(raw: String): String {
            val s = raw.trim().substringBefore('T')
            val iso = when {
                Regex("""\d{4}-\d{2}-\d{2}""").matches(s) -> s
                Regex("""\d{8}""").matches(s) -> "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
                Regex("""--\d{2}-\d{2}""").matches(s) -> s
                Regex("""--\d{4}""").matches(s) -> "--${s.substring(2, 4)}-${s.substring(4, 6)}"
                else -> return raw.trim()
            }
            return if (iso.startsWith("1604-")) "--" + iso.substring(5) else iso
        }

        // ---- lines ----

        /** Joins folded lines and quoted-printable soft breaks. */
        private fun logicalLines(content: String): List<String> {
            val out = ArrayList<String>()
            for (line in content.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val last = out.lastOrNull()
                when {
                    last != null && (line.startsWith(" ") || line.startsWith("\t")) -> out[out.size - 1] = last + line.substring(1)
                    last != null && last.endsWith("=") && "QUOTED-PRINTABLE" in last.substringBefore(':').uppercase(Locale.ROOT) ->
                        out[out.size - 1] = last.dropLast(1) + line
                    line.isNotBlank() -> out += line
                }
            }
            return out
        }

        private fun parseLine(line: String): VProp? {
            val colon = indexOutsideQuotes(line, ':')
            if (colon < 0) return null
            val pieces = vSplit(line.substring(0, colon), ';')
            val fullName = pieces[0]
            val group = fullName.substringBeforeLast('.', "").ifEmpty { null }
            val name = fullName.substringAfterLast('.').uppercase(Locale.ROOT)
            val params = ArrayList<Pair<String, String>>()
            for (p in pieces.drop(1)) {
                val eq = p.indexOf('=')
                if (eq < 0) {
                    // vCard 2.1 bare params, e.g. TEL;CELL.
                    val bare = p.uppercase(Locale.ROOT)
                    params += if (bare == "QUOTED-PRINTABLE" || bare == "BASE64" || bare == "B") "ENCODING" to bare else "TYPE" to p
                } else {
                    params += p.substring(0, eq).uppercase(Locale.ROOT) to p.substring(eq + 1).trim('"')
                }
            }
            var value = line.substring(colon + 1)
            if (params.any { it.first == "ENCODING" && it.second.equals("QUOTED-PRINTABLE", ignoreCase = true) }) {
                val charset = params.firstOrNull { it.first == "CHARSET" }?.second
                    ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                value = decodeQuotedPrintable(value, charset)
            }
            return VProp(group, name, params, value, line)
        }

        private fun indexOutsideQuotes(s: String, ch: Char): Int {
            var quoted = false
            s.forEachIndexed { i, c ->
                if (c == '"') quoted = !quoted
                if (c == ch && !quoted) return i
            }
            return -1
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

        /** Splits at 75 bytes without breaking a character. */
        fun fold(line: String): String {
            val sb = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < line.length) {
                val cp = line.codePointAt(i)
                val len = Character.charCount(cp)
                val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + size > 75) {
                    sb.append("\r\n ")
                    bytes = 1
                }
                sb.appendCodePoint(cp)
                bytes += size
                i += len
            }
            return sb.append("\r\n").toString()
        }
    }
}

internal fun vSplit(s: String, sep: Char): List<String> {
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

internal fun vUnescape(s: String): String {
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

internal fun vEscape(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')
    .replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

/** URIs keep their commas; only line breaks and semicolons are escaped. */
internal fun vEscapeUri(s: String): String = s.replace("\r\n", "\n").replace("\n", "\\n").replace(";", "\\;")

/** The card to send, and the kinds the phone should take from the server. */
/** The card to send, the kinds the phone should take from the server, and the kinds the phone changed. */
class Merged(val card: VCardDoc, val pullDown: Map<String, List<SyncRow>>, val replaced: Set<String>)

object SyncMerge {
    /**
     * Combines the server's card with the phone's rows, one kind (phones, emails…) at a time.
     * [local]: the phone's rows now. [snapshot]: the phone's rows right after the last sync, null if never synced.
     * [base]: the server's card at the last sync. A kind changed on the phone wins; otherwise the server's version is kept.
     */
    fun merge(server: VCardDoc, base: VCardDoc?, local: List<SyncRow>, snapshot: List<SyncRow>?): Merged {
        val l = SyncFields.byMime(local)
        val snap = snapshot?.let { SyncFields.byMime(it) }
        val s = SyncFields.byMime(server.rows())
        val b = base?.let { SyncFields.byMime(it.rows()) }
        val replace = LinkedHashMap<String, List<SyncRow>>()
        val pull = LinkedHashMap<String, List<SyncRow>>()
        for (mime in SyncFields.mimes) {
            val mine = l[mime].orEmpty()
            val localChanged = if (snap == null) mine.isNotEmpty() else !SyncFields.same(mine, snap[mime].orEmpty())
            when {
                localChanged -> replace[mime] = mine
                b == null || !SyncFields.same(s[mime].orEmpty(), b[mime].orEmpty()) -> pull[mime] = s[mime].orEmpty()
            }
        }
        return Merged(server.patched(replace), pull, replace.keys)
    }

    /**
     * Values in [sent] that the server didn't keep, for the [kinds] Rolodex sent.
     * Some servers (OX, used by mailbox.org) only have room for a fixed number of each, e.g. two mobile numbers.
     */
    fun lost(sent: VCardDoc, stored: VCardDoc, kinds: Set<String>): List<SyncRow> {
        val kept = stored.rows().map { key(it) }.toSet()
        return sent.rows().filter { it.mime in kinds && it.mime in CHECKED && key(it) !in kept }
    }

    /** Kinds that can hold several values. */
    private val CHECKED = SyncFields.mimes - setOf(StructuredName.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE)

    private fun key(r: SyncRow): String = r.mime + "|" + when (r.mime) {
        Phone.CONTENT_ITEM_TYPE -> dialableNumber(r[Phone.NUMBER].orEmpty())
        Email.CONTENT_ITEM_TYPE -> r[Email.ADDRESS].orEmpty().lowercase(Locale.ROOT)
        StructuredPostal.CONTENT_ITEM_TYPE -> listOf(
            StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD, StructuredPostal.CITY,
            StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY,
        ).joinToString("|") { r[it].orEmpty() }
        else -> r["data1"].orEmpty()
    }
}
