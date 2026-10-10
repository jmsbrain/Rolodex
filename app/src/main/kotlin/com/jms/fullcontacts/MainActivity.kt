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
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so screens re-read contacts edited elsewhere. */
    private var resumeTick by mutableIntStateOf(0)
    private var hasPermission by mutableStateOf(false)

    /** Set when another app asks us to show a specific contact. */
    private var openContactId by mutableStateOf<Long?>(null)

    /** A contact link that needs READ_CONTACTS to resolve; kept until permission is granted. */
    private var pendingContactUri: Uri? = null

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
        setContent {
            App(
                activity = this,
                hasPermission = hasPermission,
                refreshKey = resumeTick,
                onPermissionResult = { granted ->
                    hasPermission = granted || checkPermission()
                    resolvePendingContact()
                },
                openContactId = openContactId,
                onOpenContactConsumed = { openContactId = null },
            )
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * If we were opened as the phone's contacts app to view one contact
     * (content://com.android.contacts/contacts/...), jump straight to it.
     */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.authority != ContactsContract.AUTHORITY) return
        pendingContactUri = uri
        resolvePendingContact()
    }

    /**
     * Turns [pendingContactUri] into a contact id. Lookup-key links need the
     * contacts permission, so if it isn't granted yet the link is kept and this
     * runs again once it is.
     */
    private fun resolvePendingContact() {
        val uri = pendingContactUri ?: return
        val segs = uri.pathSegments
        val id = if (segs.size == 2 && segs[0] == "contacts" && segs[1].all { it.isDigit() }) {
            segs[1].toLongOrNull()  // content://com.android.contacts/contacts/123
        } else if (checkPermission()) {
            // content://com.android.contacts/contacts/lookup/<key>/...
            try {
                ContactsContract.Contacts.lookupContact(contentResolver, uri)?.let { ContentUris.parseId(it) }
            } catch (e: Exception) {
                null
            }
        } else {
            return  // Try again after permission is granted.
        }
        pendingContactUri = null
        if (id != null && id >= 0) openContactId = id
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
        resolvePendingContact()
        resumeTick++
    }

    private fun checkPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canAskForPermission(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)

    fun canWrite(): Boolean =
        checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canAskForWritePermission(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.WRITE_CONTACTS)

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

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
