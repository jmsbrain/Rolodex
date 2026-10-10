@file:Suppress("DEPRECATION") // Im and SipAddress are deprecated but still how contacts store these fields.

package com.jms.fullcontacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.res.Resources
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.BaseTypes
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
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One choice for the type button: a standard type, or a custom one when [label] is set. */
data class TypeOption(val type: Int, val label: String?, val name: String)

private fun std(type: Int, name: String) = TypeOption(type, null, name)
private fun custom(type: Int, label: String) = TypeOption(type, label, label.uppercase(Locale.ROOT))

/** The repeatable fields the editor handles. Every one of these stores its value in DATA1. */
enum class EditKind(
    val title: String,
    val mime: String,
    /** Column holding the type (or protocol, for messaging); null when the field has none. */
    val typeColumn: String?,
    val labelColumn: String?,
    /** The type value that means "use the label". */
    val customType: Int,
    /** What the type button cycles through; empty means no button and the type is left as is. */
    val options: List<TypeOption>,
    /** Type given to newly added values. */
    val defaultOption: TypeOption?,
    val placeholder: String,
    val addLabel: String,
) {
    PHONES(
        "PHONE", Phone.CONTENT_ITEM_TYPE, Phone.TYPE, Phone.LABEL, BaseTypes.TYPE_CUSTOM,
        listOf(std(Phone.TYPE_MOBILE, "MOBILE"), std(Phone.TYPE_HOME, "HOME"), std(Phone.TYPE_WORK, "WORK"), std(Phone.TYPE_OTHER, "OTHER")),
        std(Phone.TYPE_MOBILE, "MOBILE"), "Number", "ADD PHONE",
    ),
    ADDRESSES(
        "ADDRESS", StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.TYPE, StructuredPostal.LABEL, BaseTypes.TYPE_CUSTOM,
        listOf(std(StructuredPostal.TYPE_HOME, "HOME"), std(StructuredPostal.TYPE_WORK, "WORK"), std(StructuredPostal.TYPE_OTHER, "OTHER")),
        std(StructuredPostal.TYPE_HOME, "HOME"), "Address", "ADD ADDRESS",
    ),
    NICKNAMES(
        "NICKNAME", Nickname.CONTENT_ITEM_TYPE, Nickname.TYPE, Nickname.LABEL, BaseTypes.TYPE_CUSTOM,
        emptyList(), std(Nickname.TYPE_DEFAULT, ""), "Nickname", "ADD NICKNAME",
    ),
    RELATIONS(
        "RELATED", Relation.CONTENT_ITEM_TYPE, Relation.TYPE, Relation.LABEL, BaseTypes.TYPE_CUSTOM,
        listOf(
            std(Relation.TYPE_SPOUSE, "SPOUSE"), std(Relation.TYPE_PARTNER, "PARTNER"), std(Relation.TYPE_CHILD, "CHILD"),
            std(Relation.TYPE_PARENT, "PARENT"), std(Relation.TYPE_MOTHER, "MOTHER"), std(Relation.TYPE_FATHER, "FATHER"),
            std(Relation.TYPE_BROTHER, "BROTHER"), std(Relation.TYPE_SISTER, "SISTER"), std(Relation.TYPE_RELATIVE, "RELATIVE"),
            std(Relation.TYPE_FRIEND, "FRIEND"), std(Relation.TYPE_ASSISTANT, "ASSISTANT"), std(Relation.TYPE_MANAGER, "MANAGER"),
        ),
        std(Relation.TYPE_SPOUSE, "SPOUSE"), "Name", "ADD PERSON",
    ),
    MESSAGING(
        "MESSAGING", Im.CONTENT_ITEM_TYPE, Im.PROTOCOL, Im.CUSTOM_PROTOCOL, Im.PROTOCOL_CUSTOM,
        listOf(
            custom(Im.PROTOCOL_CUSTOM, "Signal"), custom(Im.PROTOCOL_CUSTOM, "Telegram"), custom(Im.PROTOCOL_CUSTOM, "WhatsApp"),
            custom(Im.PROTOCOL_CUSTOM, "Matrix"), std(Im.PROTOCOL_JABBER, "XMPP"), std(Im.PROTOCOL_SKYPE, "SKYPE"),
        ),
        custom(Im.PROTOCOL_CUSTOM, "Signal"), "Number or username", "ADD HANDLE",
    ),
    SIP(
        "SIP", SipAddress.CONTENT_ITEM_TYPE, SipAddress.TYPE, SipAddress.LABEL, BaseTypes.TYPE_CUSTOM,
        emptyList(), std(SipAddress.TYPE_OTHER, ""), "SIP address", "ADD SIP",
    ),
    EMAILS(
        "EMAIL", Email.CONTENT_ITEM_TYPE, Email.TYPE, Email.LABEL, BaseTypes.TYPE_CUSTOM,
        listOf(std(Email.TYPE_HOME, "HOME"), std(Email.TYPE_WORK, "WORK"), std(Email.TYPE_OTHER, "OTHER")),
        std(Email.TYPE_HOME, "HOME"), "Email", "ADD EMAIL",
    ),
    WEBSITES(
        "WEBSITE", Website.CONTENT_ITEM_TYPE, Website.TYPE, Website.LABEL, BaseTypes.TYPE_CUSTOM,
        emptyList(), std(Website.TYPE_OTHER, ""), "Website", "ADD WEBSITE",
    ),
    DATES(
        "DATES", Event.CONTENT_ITEM_TYPE, Event.TYPE, Event.LABEL, BaseTypes.TYPE_CUSTOM,
        listOf(std(Event.TYPE_BIRTHDAY, "BIRTHDAY"), std(Event.TYPE_ANNIVERSARY, "ANNIVERSARY"), std(Event.TYPE_OTHER, "OTHER")),
        std(Event.TYPE_BIRTHDAY, "BIRTHDAY"), "MM/DD/YYYY or MM/DD", "ADD DATE",
    ),
    NOTES("NOTES", Note.CONTENT_ITEM_TYPE, null, null, 0, emptyList(), null, "Note", "ADD NOTE"),
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
    private fun matches(kind: EditKind, o: TypeOption) =
        o.type == type && if (o.label == null) type != kind.customType else o.label.equals(label, ignoreCase = true)

    fun typeName(kind: EditKind): String =
        kind.options.firstOrNull { matches(kind, it) }?.name
            ?: label?.uppercase(Locale.getDefault())
            ?: "OTHER"

    /** The next option in [EditKind.options]; anything not in the list cycles to the first one. */
    fun nextType(kind: EditKind): DraftItem {
        val i = kind.options.indexOfFirst { matches(kind, it) }
        val next = kind.options[(i + 1) % kind.options.size]
        return copy(type = next.type, label = next.label)
    }

    companion object {
        fun new(key: Long, kind: EditKind) =
            DraftItem(key, emptyList(), kind.defaultOption?.type ?: 0, kind.defaultOption?.label, "")
    }
}

/** Everything the editor shows, as loaded or as edited. Compared field by field when saving. */
data class ContactDraft(
    /** Null for a contact that doesn't exist yet. */
    val contactId: Long?,
    /** The raw contact new rows are added to; null for a new contact. */
    val rawContactId: Long?,
    val nameRowId: Long?,
    val prefix: String,
    val first: String,
    val middle: String,
    val last: String,
    val suffix: String,
    val orgRowId: Long?,
    val company: String,
    val title: String,
    val department: String,
    val items: Map<EditKind, List<DraftItem>>,
) {
    val nameParts get() = listOf(prefix, first, middle, last, suffix)

    companion object {
        fun blank() = ContactDraft(
            contactId = null, rawContactId = null, nameRowId = null,
            prefix = "", first = "", middle = "", last = "", suffix = "",
            orgRowId = null, company = "", title = "", department = "",
            items = mapOf(EditKind.PHONES to listOf(DraftItem.new(-1, EditKind.PHONES))),
        )
    }
}

class ContactWriter(
    private val resolver: ContentResolver,
    private val res: Resources,
) {

    private data class Account(val type: String?, val name: String?)

    /**
     * The account that holds most contacts among those that sync changes back to a
     * server (here, the DAVx5 mailbox.org address book). New contacts and new values
     * go there so they sync. Falls back to the phone itself when there's no such account.
     */
    private val mainAccount: Account by lazy {
        val uploading = ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }
            .toSet()
        val counts = HashMap<Account, Int>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.DELETED} = 0",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0) ?: continue
                if (type in uploading) counts.merge(Account(type, c.getString(1)), 1, Int::plus)
            }
        }
        counts.maxByOrNull { it.value }?.key ?: Account(null, null)
    }

    /** Picks the copy of a merged contact that edits should go to: the main account's if there is one. */
    private fun targetRawContact(contactId: Long, nameRawId: Long?): Long? {
        val raws = ArrayList<Pair<Long, Account>>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) raws += c.getLong(0) to Account(c.getString(1), c.getString(2))
        }
        val main = raws.filter { it.second == mainAccount }
        return (main.firstOrNull { it.first == nameRawId } ?: main.firstOrNull()
            ?: raws.firstOrNull { it.second.type == null }
            ?: raws.firstOrNull { it.first == nameRawId }
            ?: raws.firstOrNull())?.first
    }

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
        val target = targetRawContact(contactId, nameRawId) ?: return null

        var nameRowId: Long? = null
        var nameRowRaw: Long? = null
        var prefix = ""
        var first = ""
        var middle = ""
        var last = ""
        var suffix = ""
        var orgRowId: Long? = null
        var orgRowRaw: Long? = null
        var company = ""
        var title = ""
        var department = ""
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
                val mime = c.str(Data.MIMETYPE)
                when (mime) {
                    // Prefer the name and work rows of the copy edits go to, so changes sync.
                    StructuredName.CONTENT_ITEM_TYPE -> if (nameRowId == null || (rawId == target && nameRowRaw != target)) {
                        nameRowId = rowId
                        nameRowRaw = rawId
                        prefix = c.str(StructuredName.PREFIX).orEmpty()
                        first = c.str(StructuredName.GIVEN_NAME).orEmpty()
                        middle = c.str(StructuredName.MIDDLE_NAME).orEmpty()
                        last = c.str(StructuredName.FAMILY_NAME).orEmpty()
                        suffix = c.str(StructuredName.SUFFIX).orEmpty()
                    }

                    Organization.CONTENT_ITEM_TYPE -> if (orgRowId == null || (rawId == target && orgRowRaw != target)) {
                        orgRowId = rowId
                        orgRowRaw = rawId
                        company = c.str(Organization.COMPANY).orEmpty()
                        title = c.str(Organization.TITLE).orEmpty()
                        department = c.str(Organization.DEPARTMENT).orEmpty()
                    }

                    else -> {
                        val kind = EditKind.entries.firstOrNull { it.mime == mime } ?: continue
                        val raw = c.str(Data.DATA1) ?: continue
                        val dedupe = when (kind) {
                            EditKind.PHONES -> dialableNumber(raw)
                            EditKind.EMAILS -> raw.lowercase(Locale.ROOT)
                            else -> raw
                        }
                        val list = lists.getValue(kind)
                        val dup = seen[kind to dedupe]
                        if (dup != null) {
                            list[dup] = list[dup].copy(rowIds = list[dup].rowIds + rowId)
                        } else {
                            seen[kind to dedupe] = list.size
                            list += loadItem(kind, rowId, c, raw)
                        }
                    }
                }
            }
        }

        return ContactDraft(
            contactId = contactId,
            rawContactId = target,
            nameRowId = nameRowId,
            prefix = prefix,
            first = first,
            middle = middle,
            last = last,
            suffix = suffix,
            orgRowId = orgRowId,
            company = company,
            title = title,
            department = department,
            items = lists.mapValues { it.value.toList() },
        )
    }

    private fun loadItem(kind: EditKind, rowId: Long, c: android.database.Cursor, raw: String): DraftItem {
        val value = if (kind == EditKind.DATES) dateForEditing(raw) else raw
        val type = kind.typeColumn?.let { c.str(it)?.toIntOrNull() } ?: kind.customType
        var label = kind.labelColumn?.let { c.str(it) }
        if (type != kind.customType && kind.options.none { it.type == type && it.label == null }) {
            // A standard type the button doesn't offer (e.g. "Work fax"): keep its name for display.
            label = standardTypeName(kind, type) ?: label
        }
        return DraftItem(rowId, listOf(rowId), type, label, value)
    }

    private fun standardTypeName(kind: EditKind, type: Int): String? = when (kind) {
        EditKind.PHONES -> Phone.getTypeLabel(res, type, null)
        EditKind.EMAILS -> Email.getTypeLabel(res, type, null)
        EditKind.ADDRESSES -> StructuredPostal.getTypeLabel(res, type, null)
        EditKind.RELATIONS -> Relation.getTypeLabel(res, type, null)
        EditKind.MESSAGING -> Im.getProtocolLabel(res, type, null)
        else -> null
    }?.toString()

    /**
     * Writes what changed between [original] and [edited] and returns the contact's id,
     * which is new for an added contact (and can change if Android merges contacts).
     */
    fun save(original: ContactDraft, edited: ContactDraft): Long? {
        val ops = ArrayList<ContentProviderOperation>()
        var rawId = original.rawContactId
        if (rawId == null) {
            val account = mainAccount
            ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(RawContacts.ACCOUNT_NAME, account.name)
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
        val name = edited.nameParts.map { it.trim() }
        if (name != original.nameParts) {
            val nameRow = original.nameRowId
            if (name.all { it.isEmpty() }) {
                if (nameRow != null) delete(listOf(nameRow))
            } else {
                val b = if (nameRow != null) update(nameRow) else insert(StructuredName.CONTENT_ITEM_TYPE)
                ops += b.withValue(StructuredName.PREFIX, name[0].ifEmpty { null })
                    .withValue(StructuredName.GIVEN_NAME, name[1].ifEmpty { null })
                    .withValue(StructuredName.MIDDLE_NAME, name[2].ifEmpty { null })
                    .withValue(StructuredName.FAMILY_NAME, name[3].ifEmpty { null })
                    .withValue(StructuredName.SUFFIX, name[4].ifEmpty { null })
                    .withValue(StructuredName.DISPLAY_NAME, name.filter { it.isNotEmpty() }.joinToString(" "))
                    .build()
            }
        }

        // Work
        val work = listOf(edited.company.trim(), edited.title.trim(), edited.department.trim())
        if (work != listOf(original.company, original.title, original.department)) {
            val orgRow = original.orgRowId
            val b = when {
                orgRow != null -> update(orgRow)
                work.any { it.isNotEmpty() } -> insert(Organization.CONTENT_ITEM_TYPE).withValue(Organization.TYPE, Organization.TYPE_WORK)
                else -> null
            }
            if (b != null) {
                ops += b.withValue(Organization.COMPANY, work[0].ifEmpty { null })
                    .withValue(Organization.TITLE, work[1].ifEmpty { null })
                    .withValue(Organization.DEPARTMENT, work[2].ifEmpty { null })
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
                        val b = insert(kind.mime).withRowValues(kind, item, value, writeType = true)
                        // Messaging rows also carry a home/work type, separate from the app (protocol).
                        if (kind == EditKind.MESSAGING) b.withValue(Im.TYPE, Im.TYPE_OTHER)
                        ops += b.build()
                    }
                    value.isEmpty() -> delete(item.rowIds)
                    else -> item.rowIds.forEach {
                        ops += update(it).withRowValues(kind, item, value, writeType = kind.options.isNotEmpty()).build()
                    }
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

    private fun ContentProviderOperation.Builder.withRowValues(
        kind: EditKind,
        item: DraftItem,
        value: String,
        writeType: Boolean,
    ) = apply {
        withValue(Data.DATA1, value)
        if (kind == EditKind.ADDRESSES) {
            // Clear the parsed parts so Android rebuilds them from the new text.
            for (col in listOf(
                StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD, StructuredPostal.CITY,
                StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY,
            )) withValue(col, null)
        }
        if (writeType && kind.typeColumn != null) {
            withValue(kind.typeColumn, item.type)
            kind.labelColumn?.let { withValue(it, if (item.type == kind.customType) item.label else null) }
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
