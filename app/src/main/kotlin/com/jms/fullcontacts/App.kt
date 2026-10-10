package com.jms.fullcontacts

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
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
    var editing by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }

    // Another app asked to show a specific contact.
    LaunchedEffect(openContactId) {
        if (openContactId != null) {
            selectedId = openContactId
            editing = false
            adding = false
            onOpenContactConsumed()
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var contacts by remember { mutableStateOf<List<ContactSummary>?>(null) }

    // Bumped after every save or delete so the list and detail screens reload.
    var dataVersion by remember { mutableIntStateOf(0) }
    val reloadKey = refreshKey + dataVersion

    LaunchedEffect(reloadKey) {
        contacts = withContext(Dispatchers.IO) {
            runCatching { activity.repository.loadSummaries() }.getOrDefault(emptyList())
        }
    }

    // Editing needs WRITE_CONTACTS, which older installs were never asked for.
    var afterWriteGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterWriteGranted
        afterWriteGranted = null
        when {
            granted -> action?.invoke()
            activity.canAskForWritePermission() -> activity.toast("Rolodex needs permission to edit contacts")
            else -> {
                activity.toast("Allow Contacts for Rolodex in settings to edit contacts")
                activity.openAppSettings()
            }
        }
    }
    fun withWritePermission(action: () -> Unit) {
        if (activity.canWrite()) {
            action()
        } else {
            afterWriteGranted = action
            writeLauncher.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

    BackHandler(enabled = selectedId != null || query.isNotEmpty()) {
        if (selectedId != null) selectedId = null else query = ""
    }

    val id = selectedId
    when {
        adding -> ContactEditorScreen(
            activity = activity,
            contactId = null,
            onCancel = { adding = false },
            onSaved = { newId ->
                adding = false
                dataVersion++
                if (newId != null) selectedId = newId
            },
            onDeleted = { adding = false },
        )
        id != null && editing -> ContactEditorScreen(
            activity = activity,
            contactId = id,
            onCancel = { editing = false },
            onSaved = { savedId ->
                editing = false
                dataVersion++
                selectedId = savedId ?: id
            },
            onDeleted = {
                editing = false
                selectedId = null
                dataVersion++
            },
        )
        id == null -> ContactListScreen(
            contacts = contacts,
            query = query,
            onQueryChange = { query = it },
            listState = listState,
            onOpen = { selectedId = it },
            onAdd = { withWritePermission { adding = true } },
        )
        else -> ContactDetailScreen(
            activity = activity,
            contactId = id,
            refreshKey = reloadKey,
            onBack = { selectedId = null },
            onEdit = { withWritePermission { editing = true } },
        )
    }
}

// ---- Permission ---------------------------------------------------------------------

@Composable
private fun PermissionScreen(activity: MainActivity, onResult: (Boolean) -> Unit) {
    var deniedForGood by remember { mutableStateOf(false) }
    val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.READ_CONTACTS] == true
        if (!granted && !activity.canAskForPermission()) deniedForGood = true
        onResult(granted)
    }
    LaunchedEffect(Unit) { launcher.launch(permissions) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = designDp(24f), vertical = designDp(32f)),
        verticalArrangement = Arrangement.spacedBy(designDp(20f)),
    ) {
        BasicText("Rolodex", style = headingStyle())
        BasicText(
            "This app needs permission to read and edit the contacts on your phone. Nothing leaves the device.",
            style = paragraphStyle(),
        )
        TextButton("ALLOW") { launcher.launch(permissions) }
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
    onAdd: () -> Unit,
) {
    val filtered = remember(contacts, query) {
        val q = query.trim()
        if (q.isEmpty()) contacts.orEmpty()
        else contacts.orEmpty().filter { it.name.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = designDp(24f))) {
        Spacer(Modifier.height(designDp(16f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SearchField(query, onQueryChange) }
            Spacer(Modifier.width(designDp(20f)))
            TextButton("ADD", onClick = onAdd)
        }
        Spacer(Modifier.height(physicalInches(0.1f)))

        when {
            contacts == null -> BasicText("Loading…", style = paragraphStyle().copy(color = LightStyle.secondary))
            contacts.isEmpty() -> BasicText("No contacts on this phone.", style = paragraphStyle())
            filtered.isEmpty() -> BasicText("No matches.", style = paragraphStyle().copy(color = LightStyle.secondary))
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { contact ->
                    BasicText(
                        text = contact.name,
                        style = copyStyle().forList(),
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
    val style = paragraphStyle().forList()
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
fun ClearButton(onClick: () -> Unit) {
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
    onEdit: () -> Unit,
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
        val d = detail
        Row(Modifier.fillMaxWidth().padding(horizontal = designDp(24f), vertical = designDp(12f))) {
            TextButton("BACK", onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (d != null) TextButton("EDIT", onClick = onEdit)
        }

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
            if (entry.label.isNotBlank() && entry.label != "NOTE" && entry.label != "WEBSITE" && entry.label != "NICKNAME" && entry.label != "SIP") {
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
fun TextButton(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = buttonStyle(),
        modifier = Modifier
            .flatClickable(onClick)
            .padding(vertical = designDp(6f)),
    )
}
