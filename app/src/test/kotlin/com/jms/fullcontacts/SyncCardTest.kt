@file:Suppress("DEPRECATION")

package com.jms.fullcontacts

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCardTest {

    private val server = """
        BEGIN:VCARD
        VERSION:3.0
        UID:abc-123
        FN:Ada Lovelace
        N:Lovelace;Ada;;Countess;
        ORG:Analytical Engines;Research
        TITLE:Programmer
        TEL;TYPE=CELL:+1 555 0100
        TEL;TYPE=WORK,FAX:+1 555 0101
        item1.TEL:+1 555 0102
        item1.X-ABLabel:Lab
        EMAIL;TYPE=INTERNET,HOME:ada@example.com
        ADR;TYPE=HOME:;;12 St James\, Square;London;;SW1;UK
        BDAY:1815-12-10
        X-ANNIVERSARY:--07-08
        IMPP;X-SERVICE-TYPE=Signal:sgnl:+15550100
        RELATED;TYPE=spouse;VALUE=text:William
        NOTE:First line\nSecond\, with comma
        CATEGORIES:Friends,Math
        PHOTO;ENCODING=b;TYPE=JPEG:AAAABBBBCCCC
        X-CUSTOM-THING:keep me
        END:VCARD
    """.trimIndent().replace("\n", "\r\n")

    private fun doc() = VCardDoc.parseOne(server)!!
    private fun rows(mime: String, d: VCardDoc = doc()) = d.rows().filter { it.mime == mime }

    @Test
    fun readsFields() {
        val name = rows(StructuredName.CONTENT_ITEM_TYPE).single()
        assertEquals("Ada", name[StructuredName.GIVEN_NAME])
        assertEquals("Lovelace", name[StructuredName.FAMILY_NAME])
        assertEquals("Countess", name[StructuredName.PREFIX])

        val org = rows(Organization.CONTENT_ITEM_TYPE).single()
        assertEquals("Analytical Engines", org[Organization.COMPANY])
        assertEquals("Research", org[Organization.DEPARTMENT])
        assertEquals("Programmer", org[Organization.TITLE])

        val phones = rows(Phone.CONTENT_ITEM_TYPE)
        assertEquals(listOf(Phone.TYPE_MOBILE, Phone.TYPE_FAX_WORK, Phone.TYPE_CUSTOM).map { it.toString() }, phones.map { it[Phone.TYPE] })
        assertEquals("Lab", phones[2][Phone.LABEL])
        assertNull(phones[0][Phone.LABEL])

        assertEquals(Email.TYPE_HOME.toString(), rows(Email.CONTENT_ITEM_TYPE).single()[Email.TYPE])

        val adr = rows(StructuredPostal.CONTENT_ITEM_TYPE).single()
        assertEquals("12 St James, Square", adr[StructuredPostal.STREET])
        assertEquals("London", adr[StructuredPostal.CITY])

        val events = rows(Event.CONTENT_ITEM_TYPE)
        assertEquals("1815-12-10", events[0][Event.START_DATE])
        assertEquals("--07-08", events[1][Event.START_DATE])
        assertEquals(Event.TYPE_ANNIVERSARY.toString(), events[1][Event.TYPE])

        val im = rows(Im.CONTENT_ITEM_TYPE).single()
        assertEquals("+15550100", im[Im.DATA])
        assertEquals("Signal", im[Im.CUSTOM_PROTOCOL])

        assertEquals(Relation.TYPE_SPOUSE.toString(), rows(Relation.CONTENT_ITEM_TYPE).single()[Relation.TYPE])
        assertEquals("First line\nSecond, with comma", rows(Note.CONTENT_ITEM_TYPE).single()[Note.NOTE])
    }

    @Test
    fun renderedRowsReadBackTheSame() {
        val original = doc().rows()
        val again = VCardDoc.parseOne(VCardDoc.fromRows(original, "x").text())!!.rows()
        assertTrue(SyncFields.same(original, again))
    }

    @Test
    fun untouchedKindsStayByteForByte() {
        val d = doc()
        val newPhones = listOf(SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+1 555 0199", Phone.TYPE to Phone.TYPE_HOME))
        val text = d.patched(mapOf(Phone.CONTENT_ITEM_TYPE to newPhones)).text()
        for (line in listOf("CATEGORIES:Friends,Math", "PHOTO;ENCODING=b;TYPE=JPEG:AAAABBBBCCCC", "X-CUSTOM-THING:keep me",
            "EMAIL;TYPE=INTERNET,HOME:ada@example.com", "UID:abc-123", "FN:Ada Lovelace")) {
            assertTrue("missing $line", line in text)
        }
        // Old phones, and the label that only they used, are gone.
        assertFalse("0101" in text)
        assertFalse("X-ABLabel:Lab" in text)
        assertTrue("TEL;TYPE=HOME,VOICE:+1 555 0199" in text)
    }

    @Test
    fun phoneEditWinsServerEmailEditKept() {
        val base = doc()
        val snapshot = base.rows()
        // Phone changed on the phone.
        val local = snapshot.filter { it.mime != Phone.CONTENT_ITEM_TYPE } +
            SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+1 555 0123", Phone.TYPE to Phone.TYPE_MOBILE)
        // Email changed on the server.
        val serverNow = VCardDoc.parseOne(server.replace("ada@example.com", "ada@new.example"))!!

        val merged = SyncMerge.merge(serverNow, base, local, snapshot)
        val text = merged.card.text()
        assertTrue("ada@new.example" in text)
        assertTrue("+1 555 0123" in text)
        assertFalse("TEL;TYPE=CELL:+1 555 0100" in text)
        // The phone takes the new email.
        assertEquals("ada@new.example", merged.pullDown[Email.CONTENT_ITEM_TYPE]?.single()?.get(Email.ADDRESS))
        assertNull(merged.pullDown[Phone.CONTENT_ITEM_TYPE])
    }

    @Test
    fun nothingChangedSendsNothing() {
        val d = doc()
        val merged = SyncMerge.merge(d, d, d.rows(), d.rows())
        assertEquals(d.text(), merged.card.text())
        assertTrue(merged.pullDown.isEmpty())
    }

    @Test
    fun deletingAllPhonesRemovesThem() {
        val d = doc()
        val local = d.rows().filter { it.mime != Phone.CONTENT_ITEM_TYPE }
        val text = SyncMerge.merge(d, d, local, d.rows()).card.text()
        assertFalse("TEL" in text)
    }

    @Test
    fun noSnapshotNeverDeletesServerData() {
        val d = doc()
        val local = listOf(SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+1 555 0177", Phone.TYPE to Phone.TYPE_MOBILE))
        val text = SyncMerge.merge(d, null, local, null).card.text()
        assertTrue("ada@example.com" in text)
        assertTrue("+1 555 0177" in text)
    }

    @Test
    fun newCardHasRequiredLines() {
        val text = VCardDoc.fromRows(listOf(SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "123", Phone.TYPE to Phone.TYPE_MOBILE)), "u1").text()
        assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\nUID:u1\r\n"))
        assertTrue("\r\nFN:" in text)
        assertTrue("\r\nN:;;;;\r\n" in text)
        assertTrue(text.endsWith("END:VCARD\r\n"))
    }

    @Test
    fun groupsAreRecognized() {
        val g = VCardDoc.parseOne("BEGIN:VCARD\r\nVERSION:4.0\r\nKIND:group\r\nFN:Team\r\nEND:VCARD\r\n")!!
        assertTrue(g.isGroup)
        assertFalse(doc().isGroup)
    }

    @Test
    fun appleLabelsAndOmittedYear() {
        val d = VCardDoc.parseOne(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:X\r\nitem1.TEL:1\r\nitem1.X-ABLabel:_\$!<Main>!\$_\r\n" +
                "BDAY;X-APPLE-OMIT-YEAR=1604:1604-03-04\r\nitem2.X-ABRELATEDNAMES:Mum\r\nitem2.X-ABLabel:_\$!<Mother>!\$_\r\nEND:VCARD\r\n",
        )!!
        assertEquals(Phone.TYPE_MAIN.toString(), rows(Phone.CONTENT_ITEM_TYPE, d).single()[Phone.TYPE])
        assertEquals("--03-04", rows(Event.CONTENT_ITEM_TYPE, d).single()[Event.START_DATE])
        assertEquals(Relation.TYPE_MOTHER.toString(), rows(Relation.CONTENT_ITEM_TYPE, d).single()[Relation.TYPE])
    }

    @Test
    fun longLinesFoldWithoutSplittingCharacters() {
        val note = "é".repeat(100)
        val folded = VCardDoc.fold("NOTE:$note")
        folded.removeSuffix("\r\n").split("\r\n").forEach { assertTrue(it.toByteArray().size <= 75) }
        val back = VCardDoc.parseOne("BEGIN:VCARD\r\n${folded}END:VCARD\r\n")
        assertNotNull(back)
        assertEquals(note, back!!.first("NOTE")!!.text)
    }

    @Test
    fun phoneTypesUseWordsOxUnderstands() {
        val types = listOf(Phone.TYPE_MAIN, Phone.TYPE_OTHER, Phone.TYPE_WORK, Phone.TYPE_ASSISTANT, Phone.TYPE_OTHER_FAX)
        val rows = types.mapIndexed { i, t -> SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "$i", Phone.TYPE to t) }
        val text = VCardDoc.fromRows(rows, "u").text()
        assertTrue("TEL;TYPE=X-PRIMARY:0" in text)
        assertTrue("TEL;TYPE=X-OTHER:1" in text)
        assertTrue("TEL;TYPE=WORK,VOICE:2" in text)
        assertFalse("X-ABLabel" in text)
        val back = VCardDoc.parseOne(text)!!.rows().filter { it.mime == Phone.CONTENT_ITEM_TYPE }.map { it[Phone.TYPE] }
        assertEquals(types.map { it.toString() }, back)
    }

    @Test
    fun findsNumbersTheServerDropped() {
        val mobiles = (1..3).map { SyncRow.of(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+1 555 010$it", Phone.TYPE to Phone.TYPE_MOBILE) }
        val sent = VCardDoc.fromRows(mobiles, "u")
        // Server keeps two, reformatted.
        val stored = VCardDoc.parseOne("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:\r\nTEL;TYPE=cell:+15550101\r\nTEL;TYPE=cell:+15550102\r\nEND:VCARD\r\n")!!
        val lost = SyncMerge.lost(sent, stored, SyncFields.mimes)
        assertEquals(listOf("+1 555 0103"), lost.map { it[Phone.NUMBER] })
        assertTrue(SyncMerge.lost(sent, sent, SyncFields.mimes).isEmpty())
    }
}
