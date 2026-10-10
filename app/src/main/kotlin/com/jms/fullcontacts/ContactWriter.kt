package com.jms.fullcontacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.res.Resources
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.BaseTypes
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The repeatable fields the editor handles. Every one of these stores its value in
 * DATA1 and, when it has a type, the type in DATA2 and a custom label in DATA3.
 */
enum class EditKind(
    val title: String,
    val mime: String,
    /** Types the type button cycles through; empty when the field has no type. */
    val types: List<Pair<Int, String>>,
    /** Type written for new rows; -1 means the field has no type column. */
    val defaultType: Int,
    val placeholder: String,
    val addLabel: String,
) {
    PHONES(
        "PHONE", Phone.CONTENT_ITEM_TYPE,
        listOf(Phone.TYPE_MOBILE to "MOBILE", Phone.TYPE_HOME to "HOME", Phone.TYPE_WORK to "WORK", Phone.TYPE_OTHER to "OTHER"),
        Phone.TYPE_MOBILE, "Number", "ADD PHONE",
    ),
    ADDRESSES(
        "ADDRESS", StructuredPostal.CONTENT_ITEM_TYPE,
        listOf(StructuredPostal.TYPE_HOME to "HOME", StructuredPostal.TYPE_WORK to "WORK", StructuredPostal.TYPE_OTHER to "OTHER"),
        StructuredPostal.TYPE_HOME, "Address", "ADD ADDRESS",
    ),
    NICKNAMES("NICKNAME", Nickname.CONTENT_ITEM_TYPE, emptyList(), Nickname.TYPE_DEFAULT, "Nickname", "ADD NICKNAME"),
    DATES(
        "DATES", Event.CONTENT_ITEM_TYPE,
        listOf(Event.TYPE_BIRTHDAY to "BIRTHDAY", Event.TYPE_ANNIVERSARY to "ANNIVERSARY", Event.TYPE_OTHER to "OTHER"),
        Event.TYPE_BIRTHDAY, "MM/DD/YYYY or MM/DD", "ADD DATE",
    ),
    WEBSITES("WEBSITE", Website.CONTENT_ITEM_TYPE, emptyList(), Website.TYPE_OTHER, "Website", "ADD WEBSITE"),
    NOTES("NOTES", Note.CONTENT_ITEM_TYPE, emptyList(), -1, "Note", "ADD NOTE"),
}

/**
 * One value being edited. [rowIds] are the data rows it was loaded from (merged
 * contacts can hold the same number several times); empty for a newly added value.
 */
data class DraftItem(
    val key: Long,
    val rowIds: List<Long>,
    val type: Int,
    val label: String?,
    val value: String,
) {
    fun typeName(kind: EditKind): String =
        kind.types.firstOrNull { it.first == type && type != BaseTypes.TYPE_CUSTOM }?.second
            ?: label?.uppercase(Locale.getDefault())
            ?: "OTHER"

    /** The next type in [EditKind.types]; a custom label cycles to the first standard type. */
    fun nextType(kind: EditKind): DraftItem {
        val i = kind.types.indexOfFirst { it.first == type && type != BaseTypes.TYPE_CUSTOM }
        return copy(type = kind.types[(i + 1) % kind.types.size].first, label = null)
    }
}

/** Everything the editor shows, as loaded or as edited. Compared field by field when saving. */
data class ContactDraft(
    /** Null for a contact that doesn't exist yet. */
    val contactId: Long?,
    /** The raw contact new rows are added to; null for a new contact. */
    val rawContactId: Long?,
    val nameRowId: Long?,
    val prefix: String?,
    val suffix: String?,
    val first: String,
    val middle: String,
    val last: String,
    val orgRowId: Long?,
    val company: String,
    val title: String,
    val items: Map<EditKind, List<DraftItem>>,
) {
    companion object {
        fun blank() = ContactDraft(
            contactId = null, rawContactId = null, nameRowId = null, prefix = null, suffix = null,
            first = "", middle = "", last = "", orgRowId = null, company = "", title = "",
            items = mapOf(EditKind.PHONES to listOf(DraftItem(-1, emptyList(), EditKind.PHONES.defaultType, null, ""))),
        )
    }
}

class ContactWriter(
    private val resolver: ContentResolver,
    private val res: Resources,
) {

    fun loadDraft(contactId: Long): ContactDraft? {
        var exists = false
        var nameRawId: Long? = null
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.NAME_RAW_CONTACT_ID),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                exists = true
                if (!c.isNull(0)) nameRawId = c.getLong(0)
            }
        }
        if (!exists) return null

        var firstRawId: Long? = null
        var nameRowId: Long? = null
        var nameRowRaw: Long? = null
        var prefix: String? = null
        var suffix: String? = null
        var first = ""
        var middle = ""
        var last = ""
        var orgRowId: Long? = null
        var company = ""
        var title = ""
        val lists = EditKind.entries.associateWith { ArrayList<DraftItem>() }
        val seen = HashMap<Pair<EditKind, String>, Int>()

        resolver.query(
            Data.CONTENT_URI,
            null,
            "${Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            "${Data.IS_SUPER_PRIMARY} DESC, ${Data.IS_PRIMARY} DESC, ${Data._ID} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Data._ID)
            val rawCol = c.getColumnIndexOrThrow(Data.RAW_CONTACT_ID)
            while (c.moveToNext()) {
                val rowId = c.getLong(idCol)
                val rawId = c.getLong(rawCol)
                if (firstRawId == null) firstRawId = rawId
                val mime = c.str(Data.MIMETYPE)
                when (mime) {
                    // The name shown for the contact comes from NAME_RAW_CONTACT_ID; prefer that row.
                    StructuredName.CONTENT_ITEM_TYPE -> if (nameRowId == null || (rawId == nameRawId && nameRowRaw != nameRawId)) {
                        nameRowId = rowId
                        nameRowRaw = rawId
                        prefix = c.str(StructuredName.PREFIX)
                        suffix = c.str(StructuredName.SUFFIX)
                        first = c.str(StructuredName.GIVEN_NAME).orEmpty()
                        middle = c.str(StructuredName.MIDDLE_NAME).orEmpty()
                        last = c.str(StructuredName.FAMILY_NAME).orEmpty()
                    }

                    Organization.CONTENT_ITEM_TYPE -> if (orgRowId == null) {
                        orgRowId = rowId
                        company = c.str(Organization.COMPANY).orEmpty()
                        title = c.str(Organization.TITLE).orEmpty()
                    }

                    else -> {
                        val kind = EditKind.entries.firstOrNull { it.mime == mime } ?: continue
                        val raw = c.str(Data.DATA1) ?: continue
                        val dedupe = if (kind == EditKind.PHONES) dialableNumber(raw) else raw
                        val list = lists.getValue(kind)
                        val dup = seen[kind to dedupe]
                        if (dup != null) {
                            list[dup] = list[dup].copy(rowIds = list[dup].rowIds + rowId)
                        } else {
                            seen[kind to dedupe] = list.size
                            list += loadItem(kind, rowId, c.int(Data.DATA2), c.str(Data.DATA3), raw)
                        }
                    }
                }
            }
        }

        val rawId = nameRawId ?: firstRawId ?: firstRawContactOf(contactId) ?: return null
        return ContactDraft(
            contactId = contactId,
            rawContactId = rawId,
            nameRowId = nameRowId,
            prefix = prefix,
            suffix = suffix,
            first = first,
            middle = middle,
            last = last,
            orgRowId = orgRowId,
            company = company,
            title = title,
            items = lists.mapValues { it.value.toList() },
        )
    }

    private fun loadItem(kind: EditKind, rowId: Long, type: Int, label: String?, raw: String): DraftItem {
        val value = if (kind == EditKind.DATES) dateForEditing(raw) else raw
        // No type button for this field: keep whatever type it already has.
        if (kind.types.isEmpty()) return DraftItem(rowId, listOf(rowId), if (kind.defaultType < 0) -1 else type, label, value)
        // A standard type the cycle doesn't offer (e.g. "Work fax") keeps its name for display.
        val shownLabel = when {
            type == BaseTypes.TYPE_CUSTOM -> label
            kind.types.any { it.first == type } -> null
            kind == EditKind.PHONES -> Phone.getTypeLabel(res, type, label).toString()
            else -> null
        }
        return DraftItem(rowId, listOf(rowId), type, shownLabel, value)
    }

    private fun firstRawContactOf(contactId: Long): Long? =
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /**
     * Writes what changed between [original] and [edited] and returns the contact's id,
     * which is new for an added contact (and can change if Android merges contacts).
     */
    fun save(original: ContactDraft, edited: ContactDraft): Long? {
        val ops = ArrayList<ContentProviderOperation>()
        var rawId = original.rawContactId
        if (rawId == null) {
            // A new contact stored on the phone itself, not in any account.
            ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build()
        }

        fun insert(mime: String): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.MIMETYPE, mime)
            return if (rawId != null) b.withValue(Data.RAW_CONTACT_ID, rawId) else b.withValueBackReference(Data.RAW_CONTACT_ID, 0)
        }

        fun update(rowId: Long) =
            ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, rowId))

        fun delete(rowIds: List<Long>) = rowIds.forEach {
            ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, it)).build()
        }

        // Name
        val nameBefore = listOf(original.first, original.middle, original.last)
        val nameAfter = listOf(edited.first.trim(), edited.middle.trim(), edited.last.trim())
        if (nameAfter != nameBefore) {
            val display = listOf(edited.prefix.orEmpty(), nameAfter[0], nameAfter[1], nameAfter[2], edited.suffix.orEmpty())
                .filter { it.isNotEmpty() }.joinToString(" ")
            val nameRow = original.nameRowId
            if (nameAfter.all { it.isEmpty() }) {
                if (nameRow != null) delete(listOf(nameRow))
            } else {
                val b = if (nameRow != null) update(nameRow) else insert(StructuredName.CONTENT_ITEM_TYPE)
                ops += b.withValue(StructuredName.GIVEN_NAME, nameAfter[0].ifEmpty { null })
                    .withValue(StructuredName.MIDDLE_NAME, nameAfter[1].ifEmpty { null })
                    .withValue(StructuredName.FAMILY_NAME, nameAfter[2].ifEmpty { null })
                    .withValue(StructuredName.DISPLAY_NAME, display)
                    .build()
            }
        }

        // Work
        val company = edited.company.trim()
        val title = edited.title.trim()
        if (company != original.company || title != original.title) {
            val orgRow = original.orgRowId
            if (orgRow != null) {
                ops += update(orgRow)
                    .withValue(Organization.COMPANY, company.ifEmpty { null })
                    .withValue(Organization.TITLE, title.ifEmpty { null })
                    .build()
            } else if (company.isNotEmpty() || title.isNotEmpty()) {
                ops += insert(Organization.CONTENT_ITEM_TYPE)
                    .withValue(Organization.TYPE, Organization.TYPE_WORK)
                    .withValue(Organization.COMPANY, company.ifEmpty { null })
                    .withValue(Organization.TITLE, title.ifEmpty { null })
                    .build()
            }
        }

        // Repeatable fields
        for (kind in EditKind.entries) {
            val before = original.items[kind].orEmpty().associateBy { it.key }
            val after = edited.items[kind].orEmpty()
            val keptKeys = after.map { it.key }.toSet()
            before.values.filter { it.key !in keptKeys }.forEach { delete(it.rowIds) }

            for (item in after) {
                val old = before[item.key]
                val changed = old == null || old.value != item.value || old.type != item.type || old.label != item.label
                if (!changed) continue
                val value = storedValue(kind, item.value)
                    ?: throw IllegalArgumentException("Can't read \"${item.value}\" as a date")
                when {
                    item.rowIds.isEmpty() -> if (value.isNotEmpty()) {
                        ops += insert(kind.mime).withRowValues(kind, item, value).build()
                    }
                    value.isEmpty() -> delete(item.rowIds)
                    else -> item.rowIds.forEach { ops += update(it).withRowValues(kind, item, value).build() }
                }
            }
        }

        if (ops.isEmpty() || (original.rawContactId == null && ops.size == 1)) {
            // Nothing to write (a new contact with every field left blank isn't created).
            return original.contactId
        }

        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        if (rawId == null) rawId = results[0].uri?.let { ContentUris.parseId(it) } ?: return null
        return resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.CONTACT_ID),
            "${RawContacts._ID} = ?",
            arrayOf(rawId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null } ?: original.contactId
    }

    private fun ContentProviderOperation.Builder.withRowValues(kind: EditKind, item: DraftItem, value: String) = apply {
        withValue(Data.DATA1, value)
        if (kind == EditKind.ADDRESSES) {
            // Clear the parsed parts so Android rebuilds them from the new text.
            for (col in listOf(
                StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD, StructuredPostal.CITY,
                StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY,
            )) withValue(col, null)
        }
        if (kind.defaultType >= 0) {
            withValue(Data.DATA2, item.type)
            withValue(Data.DATA3, if (item.type == BaseTypes.TYPE_CUSTOM) item.label else null)
        }
    }

    /** Deletes the contact, including every account's copy of it. */
    fun delete(contactId: Long): Boolean =
        resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), null, null) > 0

    companion object {
        /** Dates in the editor that were changed and can't be read; the editor won't save until they're fixed. */
        fun unreadableDates(original: ContactDraft, edited: ContactDraft): List<String> {
            val before = original.items[EditKind.DATES].orEmpty().associateBy { it.key }
            return edited.items[EditKind.DATES].orEmpty()
                .filter { it.value != before[it.key]?.value && storedValue(EditKind.DATES, it.value) == null }
                .map { it.value.trim() }
        }

        private fun storedValue(kind: EditKind, input: String): String? {
            val s = input.trim()
            return if (kind == EditKind.DATES && s.isNotEmpty()) dateForStorage(s) else s
        }

        /** "yyyy-MM-dd" → "MM/dd/yyyy" and "--MM-dd" → "MM/dd"; anything else is shown as stored. */
        private fun dateForEditing(raw: String): String = try {
            if (raw.startsWith("--")) {
                MonthDay.parse(raw).format(DateTimeFormatter.ofPattern("MM/dd"))
            } else {
                LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofPattern("MM/dd/yyyy"))
            }
        } catch (e: Exception) {
            raw
        }

        /** Reads M/D/YYYY or M/D (also with - or .), or the stored forms; null if it isn't a real date. */
        private fun dateForStorage(input: String): String? {
            Regex("""(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{4}))?""").matchEntire(input)?.let { m ->
                val month = m.groupValues[1].toInt()
                val day = m.groupValues[2].toInt()
                val year = m.groupValues[3]
                return try {
                    if (year.isEmpty()) MonthDay.of(month, day).toString() else LocalDate.of(year.toInt(), month, day).toString()
                } catch (e: DateTimeException) {
                    null
                }
            }
            return runCatching { LocalDate.parse(input).toString() }.getOrNull()
                ?: runCatching { MonthDay.parse(input).toString() }.getOrNull()
        }
    }
}
