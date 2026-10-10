package com.jms.fullcontacts

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun App(
    activity: MainActivity,
    hasPermission: Boolean,
    refreshKey: Int,
    onPermissionResult: (Boolean) -> Unit,
    openContactId: Long? = null,
    onOpenContactConsumed: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(LightStyle.background)
            .systemBarsPadding()
            .imePadding()
    ) {
        if (hasPermission) {
            MainContent(activity, refreshKey, openContactId, onOpenContactConsumed)
        } else {
            PermissionScreen(activity, onPermissionResult)
        }
    }
}

@Composable
private fun MainContent(
    activity: MainActivity,
    refreshKey: Int,
    openContactId: Long?,
    onOpenContactConsumed: () -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Another app asked to show a specific contact.
    LaunchedEffect(openContactId) {
        if (openContactId != null) {
            selectedId = openContactId
            onOpenContactConsumed()
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var contacts by remember { mutableStateOf<List<ContactSummary>?>(null) }

    LaunchedEffect(refreshKey) {
        contacts = withContext(Dispatchers.IO) {
            runCatching { activity.repository.loadSummaries() }.getOrDefault(emptyList())
        }
    }

    BackHandler(enabled = selectedId != null || query.isNotEmpty()) {
        if (selectedId != null) selectedId = null else query = ""
    }

    val id = selectedId
    if (id == null) {
        ContactListScreen(
            contacts = contacts,
            query = query,
            onQueryChange = { query = it },
            listState = listState,
            onOpen = { selectedId = it },
        )
    } else {
        ContactDetailScreen(
            activity = activity,
            contactId = id,
            refreshKey = refreshKey,
            onBack = { selectedId = null },
        )
    }
}

// ---- Permission ---------------------------------------------------------------------

@Composable
private fun PermissionScreen(activity: MainActivity, onResult: (Boolean) -> Unit) {
    var deniedForGood by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !activity.canAskForPermission()) deniedForGood = true
        onResult(granted)
    }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.READ_CONTACTS) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = designDp(24f), vertical = designDp(32f)),
        verticalArrangement = Arrangement.spacedBy(designDp(20f)),
    ) {
        BasicText("Rolodex", style = headingStyle())
        BasicText(
            "This app needs permission to read the contacts on your phone. Nothing leaves the device.",
            style = paragraphStyle(),
        )
        TextButton("ALLOW") { launcher.launch(Manifest.permission.READ_CONTACTS) }
        if (deniedForGood) {
            BasicText(
                "If no prompt appears, grant it from app settings, or from a computer with:",
                style = paragraphStyle(),
            )
            BasicText(
                "adb shell pm grant com.jms.fullcontacts android.permission.READ_CONTACTS",
                style = labelStyle(),
            )
            TextButton("OPEN SETTINGS") { activity.openAppSettings() }
        }
    }
}

// ---- List ---------------------------------------------------------------------------

@Composable
private fun ContactListScreen(
    contacts: List<ContactSummary>?,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    onOpen: (Long) -> Unit,
) {
    val filtered = remember(contacts, query) {
        val q = query.trim()
        if (q.isEmpty()) contacts.orEmpty()
        else contacts.orEmpty().filter { it.name.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = designDp(24f))) {
        Spacer(Modifier.height(designDp(16f)))
        SearchField(query, onQueryChange)
        Spacer(Modifier.height(designDp(12f)))

        when {
            contacts == null -> BasicText("Loading…", style = paragraphStyle().copy(color = LightStyle.secondary))
            contacts.isEmpty() -> BasicText("No contacts on this phone.", style = paragraphStyle())
            filtered.isEmpty() -> BasicText("No matches.", style = paragraphStyle().copy(color = LightStyle.secondary))
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { contact ->
                    BasicText(
                        text = contact.name,
                        style = copyStyle(),
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .flatClickable { onOpen(contact.id) }
                            .padding(vertical = designDp(9f)),
                    )
                }
                item { Spacer(Modifier.height(designDp(40f))) }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val style = paragraphStyle()
    Column {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(LightStyle.content),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) BasicText("Search", style = style.copy(color = LightStyle.secondary))
                        inner()
                    }
                    if (query.isNotEmpty()) {
                        ClearButton(onClick = { onChange("") })
                    }
                }
            },
        )
        Spacer(Modifier.height(designDp(6f)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LightStyle.secondary))
    }
}

/** A drawn "X" (no font glyph needed) with a generous tap area. */
@Composable
private fun ClearButton(onClick: () -> Unit) {
    val glyph = designDp(18f)
    Box(
        Modifier
            .flatClickable(onClick)
            .padding(start = designDp(16f), top = designDp(8f), bottom = designDp(8f), end = designDp(4f)),
    ) {
        Canvas(Modifier.size(glyph)) {
            val w = 2.dp.toPx()
            drawLine(LightStyle.content, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(LightStyle.content, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

// ---- Detail -------------------------------------------------------------------------

@Composable
private fun ContactDetailScreen(
    activity: MainActivity,
    contactId: Long,
    refreshKey: Int,
    onBack: () -> Unit,
) {
    var detail by remember(contactId) { mutableStateOf<ContactDetail?>(null) }
    var loaded by remember(contactId) { mutableStateOf(false) }

    LaunchedEffect(contactId, refreshKey) {
        detail = withContext(Dispatchers.IO) {
            runCatching { activity.repository.loadDetail(contactId) }.getOrNull()
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = designDp(24f), vertical = designDp(12f))) {
            TextButton("BACK", onClick = onBack)
        }

        val d = detail
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = designDp(24f)),
        ) {
            when {
                d != null -> {
                    BasicText(d.name, style = headingStyle())
                    if (d.sections.isEmpty()) {
                        Spacer(Modifier.height(designDp(20f)))
                        BasicText("Nothing else saved for this contact.", style = paragraphStyle().copy(color = LightStyle.secondary))
                    }
                    d.sections.forEach { section ->
                        Spacer(Modifier.height(designDp(28f)))
                        BasicText(section.title, style = labelStyle())
                        Spacer(Modifier.height(designDp(4f)))
                        section.entries.forEach { EntryRow(it, activity) }
                    }
                    Spacer(Modifier.height(designDp(32f)))
                    BasicText("Long-press anything to copy it.", style = labelStyle())
                    Spacer(Modifier.height(designDp(40f)))
                }
                loaded -> BasicText("This contact no longer exists.", style = paragraphStyle())
                else -> BasicText("Loading…", style = paragraphStyle().copy(color = LightStyle.secondary))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: Entry, activity: MainActivity) {
    val onTap: (() -> Unit)? = when (entry.kind) {
        EntryKind.Phone -> { { activity.dial(entry.target) } }
        EntryKind.Email -> { { activity.copy(entry.value) } }
        EntryKind.Address -> { { activity.map(entry.value) } }
        EntryKind.Website -> { { activity.openWebsite(entry.value) } }
        EntryKind.Plain -> null
    }
    val linkish = entry.kind == EntryKind.Website

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = { activity.copy(entry.value) },
                onClick = { onTap?.invoke() },
            )
            .padding(vertical = designDp(8f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Skip the small label when it would just repeat the section title.
            if (entry.label.isNotBlank() && entry.label != "NOTE" && entry.label != "WEBSITE" && entry.label != "NICKNAME") {
                BasicText(entry.label, style = labelStyle())
            }
            BasicText(
                entry.value,
                style = paragraphStyle().copy(
                    textDecoration = if (linkish) TextDecoration.Underline else TextDecoration.None,
                ),
            )
        }
    }
}

// ---- Shared -------------------------------------------------------------------------

@Composable
private fun TextButton(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = buttonStyle(),
        modifier = Modifier
            .flatClickable(onClick)
            .padding(vertical = designDp(6f)),
    )
}
