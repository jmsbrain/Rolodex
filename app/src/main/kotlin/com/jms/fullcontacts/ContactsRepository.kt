package com.jms.fullcontacts

import android.content.ContentResolver
import android.content.res.Resources
import android.database.Cursor
import android.provider.ContactsContract
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
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row in the contact list. */
data class ContactSummary(val id: Long, val name: String)

/** What tapping an entry does. */
enum class EntryKind { Phone, Email, Address, Website, Plain }

/**
 * One labelled value on the detail screen, e.g. "MOBILE" / "+1 555 0100".
 * [target] is what tapping acts on; for phones it's the number with formatting stripped.
 */
data class Entry(
    val label: String,
    val value: String,
    val kind: EntryKind = EntryKind.Plain,
    val target: String = value,
)

/** A titled group of entries, e.g. "PHONE" with all of the contact's numbers. */
data class Section(val title: String, val entries: List<Entry>)

data class ContactDetail(val id: Long, val name: String, val sections: List<Section>)

class ContactsRepository(
    private val resolver: ContentResolver,
    private val res: Resources,
) {

    fun loadSummaries(): List<ContactSummary> {
        val out = ArrayList<ContactSummary>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            "${ContactsContract.Contacts.SORT_KEY_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no name)"
                out += ContactSummary(id, name)
            }
        }
        return out
    }

    fun loadDetail(contactId: Long): ContactDetail? {
        var exists = false
        var displayName: String? = null
        val names = Bucket()
        val nicknames = Bucket()
        val phones = Bucket()
        val emails = Bucket()
        val addresses = Bucket()
        val work = Bucket()
        val websites = Bucket()
        val dates = Bucket()
        val im = Bucket()
        val sip = Bucket()
        val relations = Bucket()
        val notes = Bucket()

        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                exists = true
                displayName = c.getString(0)
            }
        }
        if (!exists) return null

        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            // Primary values first, then in the order they were added.
            "${ContactsContract.Data.IS_SUPER_PRIMARY} DESC, ${ContactsContract.Data.IS_PRIMARY} DESC, ${ContactsContract.Data._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                when (c.str(ContactsContract.Data.MIMETYPE)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        // Only show name parts when they add something beyond the display name.
                        c.str(StructuredName.PREFIX)?.let { names.add("PREFIX", it) }
                        c.str(StructuredName.GIVEN_NAME)?.let { names.add("FIRST", it) }
                        c.str(StructuredName.MIDDLE_NAME)?.let { names.add("MIDDLE", it) }
                        c.str(StructuredName.FAMILY_NAME)?.let { names.add("LAST", it) }
                        c.str(StructuredName.SUFFIX)?.let { names.add("SUFFIX", it) }
                        val phonetic = listOfNotNull(
                            c.str(StructuredName.PHONETIC_GIVEN_NAME),
                            c.str(StructuredName.PHONETIC_MIDDLE_NAME),
                            c.str(StructuredName.PHONETIC_FAMILY_NAME),
                        ).joinToString(" ")
                        if (phonetic.isNotBlank()) names.add("PHONETIC", phonetic)
                    }

                    Phone.CONTENT_ITEM_TYPE -> c.str(Phone.NUMBER)?.let { number ->
                        val label = Phone.getTypeLabel(res, c.int(Phone.TYPE), c.str(Phone.LABEL))
                        val dialable = dialableNumber(number)
                        if (dialable.isNotEmpty()) {
                            phones.add(label.toString(), number, EntryKind.Phone, dedupeKey = dialable, target = dialable)
                        }
                    }

                    Email.CONTENT_ITEM_TYPE -> c.str(Email.ADDRESS)?.let { addr ->
                        val label = Email.getTypeLabel(res, c.int(Email.TYPE), c.str(Email.LABEL))
                        emails.add(label.toString(), addr, EntryKind.Email, dedupeKey = addr.lowercase())
                    }

                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val formatted = c.str(StructuredPostal.FORMATTED_ADDRESS) ?: listOfNotNull(
                            c.str(StructuredPostal.STREET),
                            c.str(StructuredPostal.POBOX),
                            c.str(StructuredPostal.NEIGHBORHOOD),
                            listOfNotNull(
                                c.str(StructuredPostal.CITY),
                                c.str(StructuredPostal.REGION),
                                c.str(StructuredPostal.POSTCODE),
                            ).joinToString(" ").ifBlank { null },
                            c.str(StructuredPostal.COUNTRY),
                        ).joinToString("\n").ifBlank { null }
                        if (formatted != null) {
                            val label = StructuredPostal.getTypeLabel(res, c.int(StructuredPostal.TYPE), c.str(StructuredPostal.LABEL))
                            addresses.add(label.toString(), formatted, EntryKind.Address)
                        }
                    }

                    Organization.CONTENT_ITEM_TYPE -> {
                        c.str(Organization.COMPANY)?.let { work.add("COMPANY", it) }
                        c.str(Organization.TITLE)?.let { work.add("TITLE", it) }
                        c.str(Organization.DEPARTMENT)?.let { work.add("DEPARTMENT", it) }
                        c.str(Organization.JOB_DESCRIPTION)?.let { work.add("ROLE", it) }
                        c.str(Organization.OFFICE_LOCATION)?.let { work.add("OFFICE", it) }
                    }

                    Nickname.CONTENT_ITEM_TYPE -> c.str(Nickname.NAME)?.let { nicknames.add("NICKNAME", it) }

                    Website.CONTENT_ITEM_TYPE -> c.str(Website.URL)?.let { websites.add("WEBSITE", it, EntryKind.Website) }

                    Event.CONTENT_ITEM_TYPE -> c.str(Event.START_DATE)?.let { date ->
                        val label = when (c.int(Event.TYPE)) {
                            Event.TYPE_BIRTHDAY -> "BIRTHDAY"
                            Event.TYPE_ANNIVERSARY -> "ANNIVERSARY"
                            Event.TYPE_CUSTOM -> c.str(Event.LABEL) ?: "DATE"
                            else -> c.str(Event.LABEL) ?: "DATE"
                        }
                        dates.add(label, formatDate(date))
                    }

                    Im.CONTENT_ITEM_TYPE -> c.str(Im.DATA)?.let { handle ->
                        @Suppress("DEPRECATION")
                        val label = Im.getProtocolLabel(res, c.int(Im.PROTOCOL), c.str(Im.CUSTOM_PROTOCOL))
                        im.add(label.toString(), handle)
                    }

                    SipAddress.CONTENT_ITEM_TYPE -> c.str(SipAddress.SIP_ADDRESS)?.let { sip.add("SIP", it) }

                    Relation.CONTENT_ITEM_TYPE -> c.str(Relation.NAME)?.let { name ->
                        relations.add(relationLabel(c.int(Relation.TYPE), c.str(Relation.LABEL)), name)
                    }

                    Note.CONTENT_ITEM_TYPE -> c.str(Note.NOTE)?.let { notes.add("NOTE", it) }
                }
            }
        }

        val name = displayName?.takeIf { it.isNotBlank() } ?: "(no name)"
        // Hide the name breakdown if it's just the display name split up.
        val nameSection = names.entries.takeIf { list ->
            list.any { it.label == "PREFIX" || it.label == "MIDDLE" || it.label == "SUFFIX" || it.label == "PHONETIC" }
        }

        return ContactDetail(
            id = contactId,
            name = name,
            // Same order as the editor.
            sections = listOfNotNull(
                nameSection?.let { Section("FULL NAME", it) },
                section("PHONE", phones),
                section("EMAIL", emails),
                section("ADDRESS", addresses),
                section("WORK", work),
                section("NICKNAME", nicknames),
                section("RELATED", relations),
                section("MESSAGING", im),
                section("SIP", sip),
                section("WEBSITE", websites),
                section("DATES", dates),
                section("NOTES", notes),
            ),
        )
    }

    private fun section(title: String, bucket: Bucket): Section? =
        bucket.entries.takeIf { it.isNotEmpty() }?.let { Section(title, it) }

    /** Collects entries, dropping duplicates (merged contacts often repeat the same number). */
    private class Bucket {
        val entries = ArrayList<Entry>()
        private val seen = HashSet<String>()

        fun add(
            label: String,
            value: String,
            kind: EntryKind = EntryKind.Plain,
            dedupeKey: String = value.trim(),
            target: String = value.trim(),
        ) {
            val v = value.trim()
            if (v.isEmpty() || !seen.add(dedupeKey)) return
            entries += Entry(label.uppercase(Locale.getDefault()), v, kind, target)
        }
    }

    private fun relationLabel(type: Int, custom: String?): String = when (type) {
        Relation.TYPE_ASSISTANT -> "ASSISTANT"
        Relation.TYPE_BROTHER -> "BROTHER"
        Relation.TYPE_CHILD -> "CHILD"
        Relation.TYPE_DOMESTIC_PARTNER -> "DOMESTIC PARTNER"
        Relation.TYPE_FATHER -> "FATHER"
        Relation.TYPE_FRIEND -> "FRIEND"
        Relation.TYPE_MANAGER -> "MANAGER"
        Relation.TYPE_MOTHER -> "MOTHER"
        Relation.TYPE_PARENT -> "PARENT"
        Relation.TYPE_PARTNER -> "PARTNER"
        Relation.TYPE_REFERRED_BY -> "REFERRED BY"
        Relation.TYPE_RELATIVE -> "RELATIVE"
        Relation.TYPE_SISTER -> "SISTER"
        Relation.TYPE_SPOUSE -> "SPOUSE"
        else -> custom ?: "RELATED"
    }

    /** Contacts store dates as "yyyy-MM-dd" or, without a year, "--MM-dd". */
    private fun formatDate(raw: String): String = try {
        if (raw.startsWith("--")) {
            MonthDay.parse(raw).format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
        } else {
            LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()))
        }
    } catch (e: Exception) {
        raw
    }
}

/**
 * Strips a stored number down to what a dialer understands. Imported contacts
 * often carry invisible characters (direction marks, non-breaking spaces) or
 * odd formatting that the LightOS phone screen silently rejects.
 * Keeps digits (converted to ASCII), a leading +, and * # , ; for pauses/extensions.
 */
internal fun dialableNumber(raw: String): String {
    val sb = StringBuilder()
    for (ch in raw) {
        val d = Character.digit(ch, 10)
        when {
            d >= 0 -> sb.append('0' + d)
            ch == '+' && sb.isEmpty() -> sb.append(ch)
            ch == '*' || ch == '#' || ch == ',' || ch == ';' -> sb.append(ch)
        }
    }
    return sb.toString()
}

internal fun Cursor.str(column: String): String? {
    val i = getColumnIndex(column)
    if (i < 0 || isNull(i)) return null
    return getString(i)?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun Cursor.int(column: String): Int {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) 0 else getInt(i)
}
