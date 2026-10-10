package com.jms.fullcontacts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The contacts app itself: list, contact cards and the editor. */
class MainActivity : RolodexActivity() {

    /** Set when another app asks us to show a specific contact. */
    private var openContactId by mutableStateOf<Long?>(null)

    /** A contact link that needs READ_CONTACTS to resolve; kept until permission is granted. */
    private var pendingContactUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(
                activity = this,
                hasPermission = hasPermission,
                refreshKey = resumeTick,
                onPermissionResult = ::onPermissionResult,
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

    override fun onResume() {
        super.onResume()
        resolvePendingContact()
    }

    override fun onPermissionChanged() = resolvePendingContact()

    /**
     * If another app asked to show one contact (VIEW or QUICK_CONTACT with a
     * content://com.android.contacts/... link), jump straight to it.
     */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW && intent.action != ContactsContract.QuickContact.ACTION_QUICK_CONTACT) return
        if (uri.authority != ContactsContract.AUTHORITY) return
        pendingContactUri = uri
        resolvePendingContact()
    }

    /**
     * Turns [pendingContactUri] into a contact id. Most links need the contacts
     * permission, so if it isn't granted yet the link is kept and this runs
     * again once it is.
     */
    private fun resolvePendingContact() {
        val uri = pendingContactUri ?: return
        val id = resolveContactId(uri)
        if (id == null && !checkPermission()) return  // Try again after permission is granted.
        pendingContactUri = null
        if (id != null && id >= 0) openContactId = id
    }
}
