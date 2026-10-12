package com.jms.fullcontacts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The app: list, cards, editor. */
class MainActivity : RolodexActivity() {

    /** Contact another app asked to show. */
    private var openContactId by mutableStateOf<Long?>(null)

    /** Opened from the account settings: show sync. */
    private var openSync by mutableStateOf(false)

    /** Link waiting on the contacts permission. */
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
                openSync = openSync,
                onOpenSyncConsumed = { openSync = false },
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
        if (canWrite()) CardSync.syncIfStale(this)
    }

    override fun onPermissionChanged() = resolvePendingContact()

    /** VIEW / QUICK_CONTACT from another app: open that contact. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SYNC, false) == true) openSync = true
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW && intent.action != ContactsContract.QuickContact.ACTION_QUICK_CONTACT) return
        if (uri.authority != ContactsContract.AUTHORITY) return
        pendingContactUri = uri
        resolvePendingContact()
    }

    /** Opens [pendingContactUri] once it can be resolved. */
    private fun resolvePendingContact() {
        val uri = pendingContactUri ?: return
        val id = resolveContactId(uri)
        if (id == null && !checkPermission()) return  // Retry after permission.
        pendingContactUri = null
        if (id != null && id >= 0) openContactId = id
    }

    companion object {
        const val EXTRA_SYNC = "com.jms.fullcontacts.SYNC"
    }
}
