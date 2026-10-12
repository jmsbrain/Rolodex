package com.jms.fullcontacts

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sign in to a CardDAV server, pick address books, see how syncing is going. */
@Composable
fun SyncScreen(activity: RolodexActivity, onClose: () -> Unit) {
    // Bumped to re-read the saved settings.
    var version by remember { mutableIntStateOf(0) }
    val signedIn = remember(version) { SyncSettings.signedIn(activity) }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = designDp(24f), vertical = designDp(12f))) {
            TextButton("BACK", onClick = onClose)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = designDp(24f)),
        ) {
            BasicText("Sync", style = headingStyle())
            Spacer(Modifier.height(designDp(12f)))
            if (signedIn) SignedIn(activity, version) { version++ } else SignInForm(activity) { version++ }
            Spacer(Modifier.height(designDp(40f)))
        }
    }
}

@Composable
private fun SignInForm(activity: RolodexActivity, onSignedIn: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(SyncSettings.DEFAULT_SERVER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun signIn() {
        if (email.isBlank() || password.isEmpty() || server.isBlank()) {
            error = "Enter your email, password and server."
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dav = CardDav(email.trim(), password)
                    val home = dav.findHome(server)
                    val found = dav.addressBooks(home)
                    if (found.isEmpty()) throw DavError("Signed in, but this account has no address books.")
                    // Start with one book: the main one if it's obvious.
                    val first = found.firstOrNull { !it.readOnly && it.name.equals("Contacts", ignoreCase = true) }
                        ?: found.firstOrNull { !it.readOnly } ?: found.first()
                    val books = found.map { Book(it.url, it.name, it.readOnly, sync = it.url == first.url, ctag = null) }
                    SyncSettings.save(activity, server.trim(), email.trim(), home, password, books)
                    SyncSettings.ensureAccount(activity)
                }
            }
            busy = false
            result.onSuccess {
                CardSync.syncSoon(activity)
                onSignedIn()
            }.onFailure { error = it.message ?: "Couldn't sign in." }
        }
    }

    BasicText(
        "Sign in to your contacts (CardDAV) server, such as mailbox.org. Rolodex keeps your contacts in step with it, " +
            "in place of DAVx5. If you use two-factor sign-in, use an app password.",
        style = paragraphStyle(),
    )
    Spacer(Modifier.height(designDp(16f)))
    Field("EMAIL", email, { email = it; error = null }, "you@mailbox.org", KeyboardType.Email)
    Field("PASSWORD", password, { password = it; error = null }, "", KeyboardType.Password, secret = true)
    Field("SERVER", server, { server = it; error = null }, "", KeyboardType.Uri)
    Spacer(Modifier.height(designDp(20f)))
    if (busy) Dim("Signing in…") else TextButton("SIGN IN") { signIn() }
    error?.let {
        Spacer(Modifier.height(designDp(12f)))
        BasicText(it, style = paragraphStyle())
    }
    Spacer(Modifier.height(designDp(24f)))
    Dim("Your password stays on this phone, encrypted, and is only sent to the server above.")
}

private enum class Ask { None, SignOut, Password }

@Composable
private fun SignedIn(activity: RolodexActivity, version: Int, onChanged: () -> Unit) {
    val state by CardSync.state.collectAsState()
    var books by remember(version) { mutableStateOf(SyncSettings.books(activity)) }
    var newBook by remember(version) { mutableStateOf(SyncSettings.newBook(activity)) }
    var ask by remember { mutableStateOf(Ask.None) }
    var confirmOff by remember { mutableStateOf<Book?>(null) }
    var count by remember { mutableStateOf<Int?>(null) }
    var others by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.finished, version, tick) {
        withContext(Dispatchers.IO) {
            count = runCatching { countMine(activity) }.getOrNull()
            others = runCatching { otherSyncApps(activity) }.getOrDefault(emptyList())
        }
        books = SyncSettings.books(activity)
        newBook = SyncSettings.newBook(activity)
    }

    BasicText(SyncSettings.user(activity).orEmpty(), style = paragraphStyle())
    Dim(SyncSettings.server(activity).orEmpty())
    Spacer(Modifier.height(designDp(16f)))

    val last = SyncSettings.lastSync(activity)
    val error = SyncSettings.lastError(activity)
    when {
        state.running -> Dim("Syncing…")
        error != null -> BasicText(error, style = paragraphStyle())
        last != null -> Dim("Synced ${ago(last)}" + (count?.let { " · $it contacts" } ?: ""))
        else -> Dim("Not synced yet.")
    }
    if (!state.running) {
        Spacer(Modifier.height(designDp(8f)))
        TextButton("SYNC NOW") { CardSync.syncSoon(activity) }
    }

    var notes by remember(state.finished, version) { mutableStateOf(SyncSettings.notes(activity)) }
    if (notes.isNotEmpty()) {
        Spacer(Modifier.height(designDp(28f)))
        BasicText("ONLY ON THIS PHONE", style = labelStyle())
        Spacer(Modifier.height(designDp(4f)))
        Dim(
            "The server didn't keep these, probably for lack of room: mailbox.org holds two mobile, two work and two home numbers. " +
                "To get one onto the server, change its type, e.g. a third mobile to OTHER.",
        )
        Spacer(Modifier.height(designDp(8f)))
        notes.forEach { BasicText(it, style = paragraphStyle()) }
        TextButton("DISMISS") {
            SyncSettings.clearNotes(activity)
            notes = emptyList()
        }
    }

    Spacer(Modifier.height(designDp(28f)))
    BasicText("ADDRESS BOOKS", style = labelStyle())
    Spacer(Modifier.height(designDp(4f)))
    books.forEach { book ->
        Row(
            Modifier
                .fillMaxWidth()
                .flatClickable {
                    if (book.sync) {
                        confirmOff = book
                    } else {
                        books = setSync(activity, books, book, true)
                        newBook = SyncSettings.newBook(activity)
                    }
                }
                .padding(vertical = designDp(8f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(book.sync)
            Spacer(Modifier.width(designDp(16f)))
            Column {
                BasicText(book.name, style = paragraphStyle())
                if (book.readOnly) BasicText("READ ONLY", style = labelStyle())
            }
        }
    }
    val off = confirmOff
    if (off != null) {
        BasicText(
            "Stop syncing ${off.name}? Its contacts leave this phone but stay on the server. Changes not yet synced are lost.",
            style = paragraphStyle(),
        )
        Row {
            TextButton("STOP SYNCING") {
                books = setSync(activity, books, off, false)
                newBook = SyncSettings.newBook(activity)
                confirmOff = null
            }
            Spacer(Modifier.width(designDp(32f)))
            TextButton("KEEP") { confirmOff = null }
        }
    } else {
        Dim("Tap to turn syncing on or off.")
    }

    val writable = books.filter { it.sync && !it.readOnly }
    if (writable.size > 1) {
        Spacer(Modifier.height(designDp(28f)))
        BasicText("NEW CONTACTS GO TO", style = labelStyle())
        Spacer(Modifier.height(designDp(4f)))
        MenuButtonLeft(
            writable.firstOrNull { it.url == newBook }?.name?.uppercase() ?: "CHOOSE",
            writable.map { b -> MenuItem(b.name.uppercase(), selected = b.url == newBook) { SyncSettings.setNewBook(activity, b.url); newBook = b.url } },
        )
    }

    if (others.isNotEmpty()) {
        Spacer(Modifier.height(designDp(28f)))
        BasicText("ALSO SYNCING CONTACTS", style = labelStyle())
        Spacer(Modifier.height(designDp(4f)))
        others.forEach { (name, n) -> BasicText("$name · $n contacts", style = paragraphStyle()) }
        Spacer(Modifier.height(designDp(4f)))
        Dim(
            "Once Rolodex has synced, remove the address book from that app (or uninstall DAVx5), " +
                "so each contact isn't stored twice. Rolodex shows them as one contact in the meantime.",
        )
    }

    Spacer(Modifier.height(designDp(28f)))
    when (ask) {
        Ask.SignOut -> {
            BasicText(
                "Sign out? Rolodex's copy of these contacts is removed from this phone. They stay on the server. Changes not yet synced are lost.",
                style = paragraphStyle(),
            )
            Row {
                TextButton("SIGN OUT") {
                    scope.launch {
                        withContext(Dispatchers.IO) { SyncSettings.signOut(activity) }
                        ask = Ask.None
                        onChanged()
                    }
                }
                Spacer(Modifier.width(designDp(32f)))
                TextButton("KEEP") { ask = Ask.None }
            }
        }
        Ask.Password -> PasswordChange(activity, onDone = { ask = Ask.None; tick++ })
        Ask.None -> {
            TextButton("CHANGE PASSWORD") { ask = Ask.Password }
            TextButton("SIGN OUT") { ask = Ask.SignOut }
        }
    }
}

@Composable
private fun PasswordChange(activity: RolodexActivity, onDone: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Field("NEW PASSWORD", password, { password = it; error = null }, "", KeyboardType.Password, secret = true)
    if (busy) {
        Dim("Checking…")
    } else {
        Row {
            TextButton("SAVE") {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { CardDav(SyncSettings.user(activity)!!, password).addressBooks(SyncSettings.home(activity)!!) }
                    }
                    busy = false
                    result.onSuccess {
                        SyncSettings.setPassword(activity, password)
                        CardSync.syncSoon(activity)
                        onDone()
                    }.onFailure { error = it.message }
                }
            }
            Spacer(Modifier.width(designDp(32f)))
            TextButton("CANCEL", onClick = onDone)
        }
    }
    error?.let { BasicText(it, style = paragraphStyle()) }
}

/** Dropdown that opens under its left edge. */
@Composable
private fun MenuButtonLeft(label: String, items: List<MenuItem>) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TextButton(label) { expanded = true }
        if (expanded) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = DropdownPosition(alignEnd = false),
                onDismissRequest = { expanded = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                MenuList(items.map { MenuItem(it.text, it.selected) { expanded = false; it.onClick() } })
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    secret: Boolean = false,
) {
    val style = paragraphStyle()
    Column(Modifier.fillMaxWidth().padding(vertical = designDp(6f))) {
        BasicText(label, style = labelStyle())
        Spacer(Modifier.height(designDp(4f)))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(LightStyle.content),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box {
                    if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = LightStyle.secondary))
                    inner()
                }
            },
        )
        Spacer(Modifier.height(designDp(4f)))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(LightStyle.secondary))
    }
}

@Composable
private fun Dim(text: String) = BasicText(text, style = paragraphStyle().copy(color = LightStyle.secondary))

@Composable
private fun Dot(filled: Boolean) {
    Canvas(Modifier.size(designDp(22f))) {
        val stroke = 2.dp.toPx()
        if (filled) drawCircle(LightStyle.content)
        else drawCircle(LightStyle.content, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
    }
}

private fun ago(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
}

/** Contacts in Rolodex's account. */
private fun countMine(ctx: Context): Int {
    val account = SyncSettings.account(ctx) ?: return 0
    return ctx.contentResolver.query(
        RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
        "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.DELETED} = 0",
        arrayOf(account.type, account.name), null,
    )?.use { it.count } ?: 0
}

/** Other apps syncing contacts to a server (e.g. DAVx5), with how many contacts each holds. */
private fun otherSyncApps(ctx: Context): List<Pair<String, Int>> {
    val types = ContentResolver.getSyncAdapterTypes()
        .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() && it.accountType != SyncSettings.ACCOUNT_TYPE }
        .map { it.accountType }.toSet()
    if (types.isEmpty()) return emptyList()
    val counts = HashMap<String, Int>()
    ctx.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE), "${RawContacts.DELETED} = 0", null, null)?.use { c ->
        while (c.moveToNext()) {
            val t = c.getString(0) ?: continue
            if (t in types) counts.merge(t, 1, Int::plus)
        }
    }
    val authenticators = AccountManager.get(ctx).authenticatorTypes
    return counts.map { (type, n) ->
        val label = authenticators.firstOrNull { it.type == type }?.let { a ->
            runCatching { ctx.packageManager.getResourcesForApplication(a.packageName).getString(a.labelId) }.getOrNull()
        } ?: type
        label to n
    }.sortedByDescending { it.second }
}

private fun setSync(ctx: Context, books: List<Book>, book: Book, on: Boolean): List<Book> {
    val updated = books.map { if (it.url == book.url) it.copy(sync = on, ctag = null) else it }
    SyncSettings.saveBooks(ctx, updated)
    CardSync.syncSoon(ctx)
    return updated
}
