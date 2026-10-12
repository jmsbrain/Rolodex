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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STOCK_SMS_PACKAGE = "com.android.mms"
/** Time for the stock app to save the conversation. */
private const val GROUP_THREAD_DELAY_MS = 700L

/** Shared by [MainActivity] and [RequestActivity]: permissions and entry actions. */
abstract class RolodexActivity : ComponentActivity() {

    /** Bumped on resume so screens reload. */
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
        writer = ContactWriter(contentResolver, resources) { SyncSettings.account(this) }
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

    protected open fun onPermissionChanged() = Unit

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** Contact id from a contacts, lookup, raw_contacts or data link; null if not found. */
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

    // ---- Entry actions ----

    /** LightOS phone screen if possible, otherwise a normal dial intent. */
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

    /**
     * Group text to [numbers]. LightOS ignores recipients in a text link, so the stock
     * messaging app opens first and creates the conversation, then LightOS messages takes over.
     */
    fun groupText(numbers: List<String>) {
        // Pauses (, ;) would split recipients.
        val recipients = numbers.map { dialableNumber(it).filter { ch -> ch.isDigit() || ch == '+' } }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty()) return
        val list = recipients.joinToString(";")
        val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$list"))
        val stock = Intent(sms).setPackage(STOCK_SMS_PACKAGE)
        @Suppress("DEPRECATION")
        if (packageManager.resolveActivity(stock, 0) == null) {
            launchPreferLightOs(sms, list)
            return
        }
        // Stays in our task so we may still open LightOS (Android blocks background starts),
        // and closes once LightOS covers it, so reopening Rolodex doesn't land there.
        stock.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        launch(stock, list)
        scope.launch {
            delay(GROUP_THREAD_DELAY_MS)
            // LightOS only reloads its list on a screen change, so settings first (links as Luma uses them).
            // Back to back: once LightOS is in front, we can't open anything.
            if (!openLightOs("settingsindex") || !openLightOs("recentsindex")) {
                runCatching { startActivity(Intent(sms).setPackage(LightOsBridge.LIGHTOS_PACKAGE)) }
            }
        }
    }

    private fun openLightOs(route: String): Boolean = runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("lightos://$route"))
                .setClassName(LightOsBridge.LIGHTOS_PACKAGE, "com.lightos.MainActivity"),
        )
    }.isSuccess

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

    /** Share the contact as plain text. */
    fun shareAsText(name: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .putExtra(Intent.EXTRA_TEXT, text)
        startShare(send)
    }

    /** Share the contact as a .vcf (built by Android). */
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

    /** Opens [intent], or copies [fallback] if nothing can. */
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
