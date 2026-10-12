package com.jms.fullcontacts

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.runBlocking

/** Lets Android sync Rolodex's account on its own schedule, and after edits made in other apps. */
class SyncService : Service() {
    private val adapter by lazy { Adapter(applicationContext) }

    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder

    private class Adapter(ctx: Context) : AbstractThreadedSyncAdapter(ctx, true, false) {
        override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) {
            if (account != SyncSettings.account(context)) return
            val error = runBlocking { CardSync.sync(context) }
            if (error != null) result.stats.numIoExceptions++
        }
    }
}

/** The account type. Signing in happens in Rolodex, so adding an account from settings just opens it. */
class AuthenticatorService : Service() {
    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder

    private class Authenticator(private val ctx: Context) : AbstractAccountAuthenticator(ctx) {
        override fun addAccount(
            response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
            requiredFeatures: Array<out String>?, options: Bundle?,
        ): Bundle = Bundle().apply {
            putParcelable(AccountManager.KEY_INTENT, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SYNC, true))
        }

        override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
        override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null
        override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
        override fun getAuthTokenLabel(authTokenType: String?): String? = null
        override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
        override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
