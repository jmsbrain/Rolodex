package com.jms.fullcontacts

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What every Rolodex screen shares: contact access, permissions, and the actions
 * behind tapping an entry (dial, map, copy, share). [MainActivity] is the app
 * itself; [RequestActivity] answers other apps (add, edit, pick, import).
 */
abstract class RolodexActivity : ComponentActivity() {

    /** Bumped on every resume so screens re-read contacts edited elsewhere. */
    var resumeTick by mutableIntStateOf(0)
        private set
    var hasPermission by mutableStateOf(false)
        protected set
    var hasWritePermission by mutableStateOf(false)
        private set

    lateinit var repository: ContactsRepository
        private set
    lateinit var writer: ContactWriter
        private set

    private val scope = MainScope()
    private val lightOs = LightOsBridge(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ContactsRepository(contentResolver, resources)
        writer = ContactWriter(contentResolver, resources)
        hasPermission = checkPermission()
        hasWritePermission = canWrite()
    }

    override fun onStart() {
        super.onStart()
        lightOs.bind()
    }

    override fun onStop() {
        lightOs.unbind()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        hasPermission = checkPermission()
        hasWritePermission = canWrite()
        resumeTick++
    }

    fun checkPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canAskForPermission(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)

    fun canWrite(): Boolean =
        checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canAskForWritePermission(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.WRITE_CONTACTS)

    fun onPermissionResult(granted: Boolean) {
        hasPermission = granted || checkPermission()
        hasWritePermission = canWrite()
        onPermissionChanged()
    }

    /** Called after the contacts permission may have changed. */
    protected open fun onPermissionChanged() = Unit

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /**
     * Finds the contact a content://com.android.contacts/... link points to:
     * contacts/123, contacts/lookup/<key>/..., raw_contacts/123 or data/123.
     * Returns null if it can't be found (or needs a permission we don't have).
     */
    fun resolveContactId(uri: Uri): Long? {
        if (uri.authority != ContactsContract.AUTHORITY) return null
        val segs = uri.pathSegments
        if (segs.size == 2 && segs[0] == "contacts") segs[1].toLongOrNull()?.let { return it }
        if (!checkPermission()) return null
        return try {
            when (segs.firstOrNull()) {
                "contacts" -> ContactsContract.Contacts.lookupContact(contentResolver, uri)?.let { ContentUris.parseId(it) }
                "raw_contacts" -> contactIdFrom(RawContacts.CONTENT_URI, RawContacts.CONTACT_ID, ContentUris.parseId(uri))
                "data" -> contactIdFrom(Data.CONTENT_URI, Data.CONTACT_ID, ContentUris.parseId(uri))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun contactIdFrom(table: Uri, column: String, rowId: Long): Long? =
        contentResolver.query(ContentUris.withAppendedId(table, rowId), arrayOf(column), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    // ---- Actions on contact entries -------------------------------------------------

    /**
     * Tapping a phone number: first ask LightOS to open its own phone screen (works when the phone
     * allows "Any tools"). Otherwise send a dial intent, aimed at LightOS if it
     * accepts one, or at whatever dialer the phone has.
     */
    fun dial(number: String) {
        scope.launch {
            if (!lightOs.openDialer(number)) {
                launchPreferLightOs(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)), number)
            }
        }
    }

    private fun launchPreferLightOs(intent: Intent, fallback: String) {
        val lightOsIntent = Intent(intent).setPackage(LightOsBridge.LIGHTOS_PACKAGE)
        @Suppress("DEPRECATION")
        val lightOsHandles = packageManager.resolveActivity(lightOsIntent, 0) != null
        launch(if (lightOsHandles) lightOsIntent else intent, fallback)
    }

    fun map(address: String) =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))), address)

    fun openWebsite(url: String) {
        val full = if (url.contains("://")) url else "https://$url"
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(full)), url)
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Settings aren't available on this phone", Toast.LENGTH_LONG).show()
        }
    }

    fun copy(value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("contact", value))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Opens the share menu (SMS, Signal, WhatsApp, …) with the contact written out as text. */
    fun shareAsText(name: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .putExtra(Intent.EXTRA_TEXT, text)
        startShare(send)
    }

    /**
     * Opens the share menu with the contact as a .vcf file. Android's contacts store
     * writes the file itself, with every field; the receiving app is given read access.
     */
    fun shareAsVcf(contactId: Long) {
        val vcard = repository.vcardUri(contactId) ?: run {
            toast("Couldn't make a contact file for this contact")
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
            .putExtra(Intent.EXTRA_STREAM, vcard)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("contact", vcard)
        startShare(send)
    }

    private fun startShare(send: Intent) {
        try {
            startActivity(Intent.createChooser(send, "Share contact"))
        } catch (e: ActivityNotFoundException) {
            toast("No app on this phone can share that")
        }
    }

    /** Opens [intent]; if nothing on the phone can handle it, copies [fallback] instead. */
    private fun launch(intent: Intent, fallback: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("contact", fallback))
            Toast.makeText(this, "No app for that here, so it was copied instead", Toast.LENGTH_LONG).show()
        }
    }
}
