package com.jms.fullcontacts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.ContactsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** An address book on the server, and whether this phone syncs it. */
data class Book(val url: String, val name: String, val readOnly: Boolean, val sync: Boolean, val ctag: String?)

/**
 * The sign-in, kept by Rolodex itself (password encrypted with a key in the Android Keystore).
 * The Android account is only a handle for the contacts; if something removes it, Rolodex adds it back.
 */
object SyncSettings {
    const val ACCOUNT_TYPE = "com.jms.fullcontacts"
    const val DEFAULT_SERVER = "https://dav.mailbox.org/"
    private const val PERIODIC_SECONDS = 60L * 60

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun user(ctx: Context): String? = prefs(ctx).getString("user", null)
    fun server(ctx: Context): String? = prefs(ctx).getString("server", null)
    fun home(ctx: Context): String? = prefs(ctx).getString("home", null)
    fun signedIn(ctx: Context): Boolean = user(ctx) != null

    fun account(ctx: Context): Account? = user(ctx)?.let { Account(it, ACCOUNT_TYPE) }

    fun save(ctx: Context, server: String, user: String, home: String, password: String, books: List<Book>) {
        prefs(ctx).edit()
            .putString("server", server)
            .putString("user", user)
            .putString("home", home)
            .apply()
        savePassword(ctx, password)
        saveBooks(ctx, books)
        setNewBook(ctx, books.firstOrNull { it.sync && !it.readOnly }?.url)
    }

    fun setPassword(ctx: Context, password: String) = savePassword(ctx, password)

    fun books(ctx: Context): List<Book> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("books", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Book(o.getString("url"), o.optString("name"), o.optBoolean("ro"), o.optBoolean("sync"), o.optString("ctag").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    fun saveBooks(ctx: Context, books: List<Book>) {
        val arr = JSONArray()
        books.forEach {
            arr.put(JSONObject().put("url", it.url).put("name", it.name).put("ro", it.readOnly).put("sync", it.sync).put("ctag", it.ctag ?: ""))
        }
        prefs(ctx).edit().putString("books", arr.toString()).apply()
    }

    /** Where contacts made on the phone go. */
    fun newBook(ctx: Context): String? {
        val url = prefs(ctx).getString("newBook", null)
        val writable = books(ctx).filter { it.sync && !it.readOnly }
        return writable.firstOrNull { it.url == url }?.url ?: writable.firstOrNull()?.url
    }

    fun setNewBook(ctx: Context, url: String?) = prefs(ctx).edit().putString("newBook", url).apply()

    // ---- status ----

    fun lastSync(ctx: Context): Long? = prefs(ctx).getLong("lastSync", 0).takeIf { it > 0 }
    fun lastError(ctx: Context): String? = prefs(ctx).getString("lastError", null)

    fun recordResult(ctx: Context, error: String?) {
        val e = prefs(ctx).edit().putString("lastError", error)
        if (error == null) e.putLong("lastSync", System.currentTimeMillis())
        e.apply()
    }

    /** Values the server didn't keep, so they're only on this phone ("Name: value"). */
    fun notes(ctx: Context): List<String> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("notes", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    /** Replaces the notes for [name] (none, once everything reached the server). */
    fun setNotes(ctx: Context, name: String, notes: List<String>) {
        val all = (notes(ctx).filterNot { it.startsWith("$name: ") } + notes).distinct()
        prefs(ctx).edit().putString("notes", JSONArray(all).toString()).apply()
    }

    fun clearNotes(ctx: Context) = prefs(ctx).edit().remove("notes").apply()

    // ---- the Android account ----

    /** The account, added (back) if it's missing. Null when not signed in. */
    fun ensureAccount(ctx: Context): Account? {
        val account = account(ctx) ?: return null
        val am = AccountManager.get(ctx)
        if (am.getAccountsByType(ACCOUNT_TYPE).none { it.name == account.name }) {
            am.getAccountsByType(ACCOUNT_TYPE).forEach { am.removeAccountExplicitly(it) }
            am.addAccountExplicitly(account, null, null)
            // Every book downloads again.
            saveBooks(ctx, books(ctx).map { it.copy(ctag = null) })
        }
        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        ContentResolver.addPeriodicSync(account, ContactsContract.AUTHORITY, Bundle(), PERIODIC_SECONDS)
        showUngrouped(ctx, account)
        return account
    }

    /** Contacts without a group would otherwise be hidden in some apps. */
    private fun showUngrouped(ctx: Context, account: Account) {
        val uri = ContactsContract.Settings.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        val values = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            put(ContactsContract.Settings.SHOULD_SYNC, 1)
        }
        runCatching { ctx.contentResolver.insert(uri, values) }
    }

    /** Removes the account (and with it, its contacts on this phone) and forgets the sign-in. */
    fun signOut(ctx: Context) {
        val am = AccountManager.get(ctx)
        am.getAccountsByType(ACCOUNT_TYPE).forEach { am.removeAccountExplicitly(it) }
        prefs(ctx).edit().clear().apply()
    }

    // ---- password ----

    private const val KEY_ALIAS = "rolodex-sync-password"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun savePassword(ctx: Context, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(password.toByteArray())
        prefs(ctx).edit()
            .putString("pw", Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString("pwIv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun password(ctx: Context): String? = runCatching {
        val p = prefs(ctx)
        val ct = Base64.decode(p.getString("pw", null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(p.getString("pwIv", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(ct))
    }.getOrNull()
}
