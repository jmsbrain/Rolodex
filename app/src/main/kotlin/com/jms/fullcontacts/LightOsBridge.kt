package com.jms.fullcontacts

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Talks to LightOS the same way Light SDK tools do, so tapping a number can open the
 * native LightOS phone screen instead of a generic Android dialer.
 *
 * This mirrors the wire protocol in light-sdk's LightServiceConnection:
 * bind to LightOS's SDK service, ask for a token, then call "OpenDialer".
 *
 * LightOS only answers apps it trusts. For a sideloaded app like this one that
 * means the phone must be set to allow "Any tools". If LightOS says no, isn't
 * reachable, or doesn't support this yet, [openDialer] returns false and the
 * app falls back to a normal dial intent.
 */
class LightOsBridge(private val activity: Activity) : ServiceConnection {

    companion object {
        const val LIGHTOS_PACKAGE = "com.lightos"
        private const val ACTION_BIND = "com.thelightphone.sdk.action.BIND_SERVICE"
        private const val TRANSACTION_REQUEST = 1
        private const val SUCCESS = -1
        private const val ERROR_INVALID_TOKEN = 4
        private const val TAG = "LightOsBridge"
    }

    private var bound = false
    @Volatile private var binder: IBinder? = null
    @Volatile private var ready = CompletableDeferred<IBinder>()
    @Volatile private var token: String? = null
    private val dialLock = Mutex()

    fun bind() {
        if (bound) return
        bound = try {
            activity.bindService(
                Intent(ACTION_BIND).setPackage(LIGHTOS_PACKAGE),
                this,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: Exception) {
            Log.i(TAG, "LightOS service not available", e)
            false
        }
        if (!bound) {
            // bindService can register the connection even when it returns false.
            runCatching { activity.unbindService(this) }
        }
    }

    fun unbind() {
        if (bound) runCatching { activity.unbindService(this) }
        bound = false
        reset()
    }

    /** Returns true if LightOS opened its own dialer for [number]. */
    suspend fun openDialer(number: String): Boolean = withContext(Dispatchers.IO) {
        // One request at a time, so two quick taps don't race over the token.
        dialLock.withLock { openDialerLocked(number) }
    }

    private suspend fun openDialerLocked(number: String): Boolean {
        if (!bound) return false
        withTimeoutOrNull(2000) { ready.await() } ?: return false

        val body = JSONObject().put("phoneNumber", number).toString()
        if (token == null) token = fetchToken() ?: return false

        var (code, _) = request("OpenDialer", body)
        if (code == ERROR_INVALID_TOKEN) {
            token = fetchToken() ?: return false
            code = request("OpenDialer", body).first
        }
        if (code != SUCCESS) Log.i(TAG, "LightOS refused OpenDialer for $number, code=$code")
        return code == SUCCESS
    }

    private fun fetchToken(): String? {
        val (code, data) = request("GetToken", "{}")
        if (code != SUCCESS || data == null) {
            Log.i(TAG, "LightOS refused GetToken, code=$code ($data)")
            return null
        }
        return runCatching { JSONObject(data).getString("token") }.getOrNull()
    }

    /** Returns (resultCode, payload). resultCode -1 means success. */
    private fun request(method: String, payload: String): Pair<Int, String?> {
        val b = binder ?: return 0 to "not connected"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ACTION_BIND)
            data.writeString(method)
            data.writeString(payload)
            data.writeString(token ?: "no_auth")
            b.transact(TRANSACTION_REQUEST, data, reply, 0)
            reply.readException()
            val code = reply.readInt()
            code to reply.readString()
        } catch (e: Exception) {
            Log.w(TAG, "LightOS request failed: $method", e)
            0 to e.message
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun reset() {
        binder = null
        token = null
        if (ready.isCompleted) ready = CompletableDeferred()
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = service
        token = null
        if (service != null) ready.complete(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) = reset()

    override fun onBindingDied(name: ComponentName?) {
        unbind()
        bind()
    }
}

/**
 * Does nothing. Its presence (with the SDK marker action in the manifest) is how
 * LightOS discovers SDK tools, which is what lets LightOS trust and list this app.
 */
class LightMarkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = Unit
}
