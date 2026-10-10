@file:Suppress("DEPRECATION") // Im

package com.jms.fullcontacts

import android.content.ContentValues
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Intents.Insert
import java.util.Locale

/** A value to add. Dates in editor form (MM/DD/YYYY). */
data class PrefillItem(val kind: EditKind, val type: Int, val label: String?, val value: String)

/** Unsaved details for the editor, from another app or a .vcf. */
data class Prefill(
    val prefix: String = "",
    val first: String = "",
    val middle: String = "",
    val last: String = "",
    val suffix: String = "",
    val company: String = "",
    val title: String = "",
    val department: String = "",
    val items: List<PrefillItem> = emptyList(),
) {
    val displayName: String
        get() = listOf(prefix, first, middle, last, suffix).filter { it.isNotBlank() }.joinToString(" ")
            .ifEmpty { items.firstOrNull { it.kind == EditKind.PHONES || it.kind == EditKind.EMAILS }?.value.orEmpty() }

    /** Fills empty name/work fields and adds values not already there. */
    fun applyTo(draft: ContactDraft): ContactDraft {
        var d = draft
        if (d.nameParts.all { it.isBlank() }) {
            d = d.copy(prefix = prefix, first = first, middle = middle, last = last, suffix = suffix)
        }
        if (d.company.isBlank() && d.title.isBlank() && d.department.isBlank()) {
            d = d.copy(company = company, title = title, department = department)
        }
        val lists = d.items.toMutableMap()
        var key = PREFILL_KEYS
        for (item in items) {
            if (item.value.isBlank()) continue
            val list = lists[item.kind].orEmpty()
            if (list.any { sameValue(item.kind, it.value, item.value) }) continue
            val blank = list.indexOfFirst { it.rowIds.isEmpty() && it.value.isBlank() }
            val added = DraftItem(if (blank >= 0) list[blank].key else key--, emptyList(), item.type, item.label, item.value.trim())
            lists[item.kind] = if (blank >= 0) list.toMutableList().also { it[blank] = added } else list + added
        }
        return d.copy(items = lists)
    }

    companion object {
        /** Far from the editor's own keys (-1, -2, …). */
        private const val PREFILL_KEYS = Long.MIN_VALUE / 2

        private fun sameValue(kind: EditKind, a: String, b: String) = when (kind) {
            EditKind.PHONES -> dialableNumber(a) == dialableNumber(b)
            EditKind.EMAILS -> a.trim().equals(b.trim(), ignoreCase = true)
            else -> a.trim() == b.trim()
        }

        /** "Jane Q. Public" → first "Jane Q.", last "Public". */
        fun splitName(full: String): Pair<String, String> {
            val words = full.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when (words.size) {
                0 -> "" to ""
                1 -> words[0] to ""
                else -> words.dropLast(1).joinToString(" ") to words.last()
            }
        }

        /** Reads Intents.Insert extras. */
        @Suppress("DEPRECATION")
        fun fromInsertExtras(extras: Bundle?): Prefill? {
            if (extras == null || extras.isEmpty) return null
            val items = ArrayList<PrefillItem>()

            /** Type extras are an Int or a custom label. */
            fun typed(kind: EditKind, valueKey: String, typeKey: String) {
                val value = extras.getCharSequence(valueKey)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
                val (type, label) = when (val t = extras.get(typeKey)) {
                    is Int -> t to null
                    is CharSequence -> t.toString().toIntOrNull()?.let { it to null } ?: (kind.customType to t.toString())
                    else -> (kind.defaultOption?.type ?: 0) to kind.defaultOption?.label
                }
                items += PrefillItem(kind, type, label, value)
            }
            typed(EditKind.PHONES, Insert.PHONE, Insert.PHONE_TYPE)
            typed(EditKind.PHONES, Insert.SECONDARY_PHONE, Insert.SECONDARY_PHONE_TYPE)
            typed(EditKind.PHONES, Insert.TERTIARY_PHONE, Insert.TERTIARY_PHONE_TYPE)
            typed(EditKind.EMAILS, Insert.EMAIL, Insert.EMAIL_TYPE)
            typed(EditKind.EMAILS, Insert.SECONDARY_EMAIL, Insert.SECONDARY_EMAIL_TYPE)
            typed(EditKind.EMAILS, Insert.TERTIARY_EMAIL, Insert.TERTIARY_EMAIL_TYPE)
            typed(EditKind.ADDRESSES, Insert.POSTAL, Insert.POSTAL_TYPE)
            typed(EditKind.MESSAGING, Insert.IM_HANDLE, Insert.IM_PROTOCOL)
            extras.getCharSequence(Insert.NOTES)?.toString()?.takeIf { it.isNotBlank() }?.let {
                items += PrefillItem(EditKind.NOTES, -1, null, it)
            }

            var prefill = Prefill(
                company = extras.getCharSequence(Insert.COMPANY)?.toString().orEmpty(),
                title = extras.getCharSequence(Insert.JOB_TITLE)?.toString().orEmpty(),
            )
            extras.getCharSequence(Insert.NAME)?.toString()?.let { name ->
                val (first, last) = splitName(name)
                prefill = prefill.copy(first = first, last = last)
            }

            // Raw rows (Insert.DATA).
            val rows = extras.getParcelableArrayList<ContentValues>(Insert.DATA).orEmpty()
            for (row in rows) {
                when (val mime = row.getAsString(Data.MIMETYPE)) {
                    StructuredName.CONTENT_ITEM_TYPE -> prefill = prefill.copy(
                        prefix = row.getAsString(StructuredName.PREFIX) ?: prefill.prefix,
                        first = row.getAsString(StructuredName.GIVEN_NAME) ?: prefill.first,
                        middle = row.getAsString(StructuredName.MIDDLE_NAME) ?: prefill.middle,
                        last = row.getAsString(StructuredName.FAMILY_NAME) ?: prefill.last,
                        suffix = row.getAsString(StructuredName.SUFFIX) ?: prefill.suffix,
                    ).let { p ->
                        // Display name only: split it.
                        val display = row.getAsString(StructuredName.DISPLAY_NAME)
                        if (display != null && listOf(p.first, p.last).all { it.isBlank() }) {
                            val (f, l) = splitName(display)
                            p.copy(first = f, last = l)
                        } else {
                            p
                        }
                    }

                    Organization.CONTENT_ITEM_TYPE -> prefill = prefill.copy(
                        company = row.getAsString(Organization.COMPANY) ?: prefill.company,
                        title = row.getAsString(Organization.TITLE) ?: prefill.title,
                        department = row.getAsString(Organization.DEPARTMENT) ?: prefill.department,
                    )

                    else -> {
                        val kind = EditKind.entries.firstOrNull { it.mime == mime } ?: continue
                        val raw = row.getAsString(Data.DATA1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        val value = if (kind == EditKind.DATES) ContactWriter.dateForEditing(raw) else raw
                        val type = kind.typeColumn?.let { row.getAsInteger(it) } ?: kind.defaultOption?.type ?: 0
                        val label = kind.labelColumn?.let { row.getAsString(it) }
                        items += PrefillItem(kind, type, label, value)
                    }
                }
            }

            val result = prefill.copy(items = prefill.items + items)
            return result.takeIf { it != Prefill() }
        }

        /** vCard type words → Android type. */
        fun typeFromWords(kind: EditKind, words: Set<String>): Pair<Int, String?> {
            val w = words.map { it.uppercase(Locale.ROOT) }.toSet()
            val custom = w.firstOrNull { it.startsWith("X-") }?.removePrefix("X-")
            return when (kind) {
                EditKind.PHONES -> when {
                    "FAX" in w && "WORK" in w -> Phone.TYPE_FAX_WORK to null
                    "FAX" in w -> Phone.TYPE_FAX_HOME to null
                    "PAGER" in w -> Phone.TYPE_PAGER to null
                    "CELL" in w || "MOBILE" in w || "IPHONE" in w -> Phone.TYPE_MOBILE to null
                    "WORK" in w -> Phone.TYPE_WORK to null
                    "HOME" in w -> Phone.TYPE_HOME to null
                    "MAIN" in w -> Phone.TYPE_MAIN to null
                    "OTHER" in w -> Phone.TYPE_OTHER to null
                    custom != null -> Phone.TYPE_CUSTOM to custom
                    else -> Phone.TYPE_MOBILE to null
                }
                EditKind.EMAILS -> when {
                    "WORK" in w -> Email.TYPE_WORK to null
                    "HOME" in w -> Email.TYPE_HOME to null
                    "CELL" in w || "MOBILE" in w -> Email.TYPE_MOBILE to null
                    "OTHER" in w -> Email.TYPE_OTHER to null
                    custom != null -> Email.TYPE_CUSTOM to custom
                    else -> Email.TYPE_HOME to null
                }
                EditKind.ADDRESSES -> when {
                    "WORK" in w -> StructuredPostal.TYPE_WORK to null
                    "HOME" in w -> StructuredPostal.TYPE_HOME to null
                    "OTHER" in w -> StructuredPostal.TYPE_OTHER to null
                    custom != null -> StructuredPostal.TYPE_CUSTOM to custom
                    else -> StructuredPostal.TYPE_HOME to null
                }
                else -> (kind.defaultOption?.type ?: 0) to kind.defaultOption?.label
            }
        }

        /** Messaging app name or scheme → field and protocol. */
        fun imProtocol(name: String): PrefillItemType {
            val n = name.lowercase(Locale.ROOT)
            return when (n) {
                "signal", "sgnl" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_CUSTOM, "Signal")
                "telegram", "tg" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_CUSTOM, "Telegram")
                "whatsapp" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_CUSTOM, "WhatsApp")
                "matrix" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_CUSTOM, "Matrix")
                "xmpp", "jabber" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_JABBER, null)
                "skype", "skype-username" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_SKYPE, null)
                "aim" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_AIM, null)
                "msn", "msnim" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_MSN, null)
                "yahoo", "ymsgr" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_YAHOO, null)
                "qq" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_QQ, null)
                "gtalk", "google-talk" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_GOOGLE_TALK, null)
                "icq" -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_ICQ, null)
                "sip" -> PrefillItemType(EditKind.SIP, EditKind.SIP.defaultOption?.type ?: 0, null)
                else -> PrefillItemType(EditKind.MESSAGING, Im.PROTOCOL_CUSTOM, name.replaceFirstChar { it.uppercase() })
            }
        }
    }
}

/** Field and type for a value. */
data class PrefillItemType(val kind: EditKind, val type: Int, val label: String?)
