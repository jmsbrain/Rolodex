package com.jms.fullcontacts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditorMode { Editing, ConfirmDiscard, ConfirmDelete, Busy }

/**
 * Edits an existing contact, or creates one when [contactId] is null.
 * Nothing is written until SAVE.
 */
@Composable
fun ContactEditorScreen(
    activity: MainActivity,
    contactId: Long?,
    onCancel: () -> Unit,
    onSaved: (contactId: Long?) -> Unit,
    onDeleted: () -> Unit,
) {
    var original by remember(contactId) { mutableStateOf(if (contactId == null) ContactDraft.blank() else null) }
    var draft by remember(contactId) { mutableStateOf(original) }
    var missing by remember(contactId) { mutableStateOf(false) }
    var mode by remember { mutableStateOf(EditorMode.Editing) }
    var error by remember { mutableStateOf<String?>(null) }
    var nextKey by remember { mutableLongStateOf(-2L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(contactId) {
        if (contactId == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching { activity.writer.loadDraft(contactId) }.getOrNull()
        }
        original = loaded
        draft = loaded
        missing = loaded == null
    }

    val dirty = draft != original

    fun cancel() {
        if (dirty) mode = EditorMode.ConfirmDiscard else onCancel()
    }

    fun save() {
        val o = original ?: return
        val d = draft ?: return
        if (!dirty) {
            if (contactId == null) onCancel() else onSaved(contactId)
            return
        }
        ContactWriter.unreadableDates(o, d).firstOrNull()?.let {
            error = "Can't read the date \"$it\". Use MM/DD/YYYY, or MM/DD without a year."
            return
        }
        mode = EditorMode.Busy
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { activity.writer.save(o, d) } }
                .onSuccess { onSaved(it ?: contactId) }
                .onFailure {
                    mode = EditorMode.Editing
                    error = "Couldn't save this contact."
                }
        }
    }

    fun delete() {
        val id = contactId ?: return
        mode = EditorMode.Busy
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { activity.writer.delete(id) }.getOrDefault(false) }
            if (ok) {
                onDeleted()
            } else {
                mode = EditorMode.Editing
                error = "Couldn't delete this contact."
            }
        }
    }

    BackHandler {
        when (mode) {
            EditorMode.Editing -> cancel()
            EditorMode.ConfirmDiscard, EditorMode.ConfirmDelete -> mode = EditorMode.Editing
            EditorMode.Busy -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = designDp(24f), vertical = designDp(12f))) {
            when (mode) {
                EditorMode.ConfirmDiscard -> {
                    BasicText("Discard your changes?", style = paragraphStyle())
                    Row {
                        TextButton("DISCARD", onClick = onCancel)
                        Spacer(Modifier.width(designDp(32f)))
                        TextButton("KEEP EDITING") { mode = EditorMode.Editing }
                    }
                }
                EditorMode.Busy -> BasicText("Saving…", style = paragraphStyle().copy(color = LightStyle.secondary))
                else -> Row(Modifier.fillMaxWidth()) {
                    TextButton("CANCEL") { cancel() }
                    Spacer(Modifier.weight(1f))
                    if (draft != null) TextButton("SAVE") { save() }
                }
            }
            error?.let {
                Spacer(Modifier.height(designDp(8f)))
                BasicText(it, style = paragraphStyle())
            }
        }

        val d = draft
        when {
            d != null -> EditorFields(
                draft = d,
                onChange = {
                    draft = it
                    error = null
                },
                newKey = { nextKey.also { nextKey-- } },
            ) {
                if (contactId != null) {
                    Spacer(Modifier.height(designDp(40f)))
                    if (mode == EditorMode.ConfirmDelete) {
                        BasicText("Delete this contact from the phone? This can't be undone.", style = paragraphStyle())
                        Row {
                            TextButton("DELETE") { delete() }
                            Spacer(Modifier.width(designDp(32f)))
                            TextButton("KEEP") { mode = EditorMode.Editing }
                        }
                    } else {
                        TextButton("DELETE CONTACT") {
                            error = null
                            mode = EditorMode.ConfirmDelete
                        }
                    }
                }
            }
            missing -> BasicText(
                "This contact no longer exists.",
                style = paragraphStyle(),
                modifier = Modifier.padding(horizontal = designDp(24f)),
            )
            else -> BasicText(
                "Loading…",
                style = paragraphStyle().copy(color = LightStyle.secondary),
                modifier = Modifier.padding(horizontal = designDp(24f)),
            )
        }
    }
}

@Composable
private fun EditorFields(
    draft: ContactDraft,
    onChange: (ContactDraft) -> Unit,
    newKey: () -> Long,
    footer: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = designDp(24f)),
    ) {
        val words = KeyboardCapitalization.Words
        SectionTitle("NAME", first = true)
        EditField(draft.prefix, "Prefix (Dr., Rev.…)", capitalization = words) { onChange(draft.copy(prefix = it)) }
        EditField(draft.first, "First", capitalization = words) { onChange(draft.copy(first = it)) }
        EditField(draft.middle, "Middle", capitalization = words) { onChange(draft.copy(middle = it)) }
        EditField(draft.last, "Last", capitalization = words) { onChange(draft.copy(last = it)) }
        EditField(draft.suffix, "Suffix (Jr., PhD…)", capitalization = words) { onChange(draft.copy(suffix = it)) }

        ItemsSection(EditKind.PHONES, draft, onChange, newKey)
        ItemsSection(EditKind.EMAILS, draft, onChange, newKey)
        ItemsSection(EditKind.ADDRESSES, draft, onChange, newKey)

        SectionTitle("WORK")
        EditField(draft.company, "Company", capitalization = words) { onChange(draft.copy(company = it)) }
        EditField(draft.title, "Title", capitalization = words) { onChange(draft.copy(title = it)) }
        EditField(draft.department, "Department", capitalization = words) { onChange(draft.copy(department = it)) }

        ItemsSection(EditKind.NICKNAMES, draft, onChange, newKey)
        ItemsSection(EditKind.RELATIONS, draft, onChange, newKey)
        ItemsSection(EditKind.MESSAGING, draft, onChange, newKey)
        ItemsSection(EditKind.SIP, draft, onChange, newKey)
        ItemsSection(EditKind.WEBSITES, draft, onChange, newKey)
        ItemsSection(EditKind.DATES, draft, onChange, newKey)
        ItemsSection(EditKind.NOTES, draft, onChange, newKey)

        footer()
        Spacer(Modifier.height(designDp(40f)))
    }
}

@Composable
private fun SectionTitle(title: String, first: Boolean = false) {
    Spacer(Modifier.height(designDp(if (first) 4f else 28f)))
    BasicText(title, style = labelStyle())
}

/** A field that can hold several values (phones, addresses, …), each with a remove X. */
@Composable
private fun ItemsSection(
    kind: EditKind,
    draft: ContactDraft,
    onChange: (ContactDraft) -> Unit,
    newKey: () -> Long,
) {
    val items = draft.items[kind].orEmpty()
    fun set(list: List<DraftItem>) = onChange(draft.copy(items = draft.items + (kind to list)))
    fun replace(item: DraftItem) = set(items.map { if (it.key == item.key) item else it })

    SectionTitle(kind.title)
    items.forEach { item ->
        key(item.key) {
            Row(Modifier.fillMaxWidth().padding(top = designDp(6f)), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    if (kind.options.isNotEmpty()) {
                        TypeDropdown(kind, item) { replace(item.withOption(it)) }
                    }
                    EditField(
                        value = item.value,
                        placeholder = kind.placeholder,
                        singleLine = kind != EditKind.ADDRESSES && kind != EditKind.NOTES,
                        keyboardType = when (kind) {
                            EditKind.PHONES -> KeyboardType.Phone
                            EditKind.EMAILS, EditKind.SIP -> KeyboardType.Email
                            EditKind.WEBSITES -> KeyboardType.Uri
                            else -> KeyboardType.Text
                        },
                        capitalization = when (kind) {
                            EditKind.ADDRESSES, EditKind.NICKNAMES, EditKind.RELATIONS -> KeyboardCapitalization.Words
                            EditKind.NOTES -> KeyboardCapitalization.Sentences
                            else -> KeyboardCapitalization.None
                        },
                    ) { replace(item.copy(value = it)) }
                }
                ClearButton(onClick = { set(items.filter { it.key != item.key }) })
            }
        }
    }
    TextButton(kind.addLabel) {
        set(items + DraftItem.new(newKey(), kind))
    }
}

/** The type label (MOBILE, HOME, …) with a ▾; tapping it opens a list of choices. */
@Composable
private fun TypeDropdown(kind: EditKind, item: DraftItem, onSelect: (TypeOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var labelHeight by remember { mutableIntStateOf(0) }
    val style = labelStyle().copy(color = LightStyle.content)

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .onSizeChanged { labelHeight = it.height }
                .flatClickable { expanded = true }
                .padding(vertical = designDp(4f)),
        ) {
            BasicText(item.typeName(kind), style = style)
            Spacer(Modifier.width(designDp(8f)))
            // A drawn ▾ so it doesn't depend on the font having the glyph.
            Canvas(Modifier.size(width = designDp(10f), height = designDp(6f))) {
                drawPath(
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2f, size.height)
                        close()
                    },
                    LightStyle.content,
                )
            }
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, labelHeight),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .background(LightStyle.background)
                        .border(1.dp, LightStyle.content)
                        .heightIn(max = designDp(320f))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = designDp(16f), vertical = designDp(6f)),
                ) {
                    kind.options.forEach { option ->
                        val selected = item.isOption(kind, option)
                        BasicText(
                            option.name,
                            style = buttonStyle().copy(
                                color = if (selected) LightStyle.content else LightStyle.secondary,
                                textDecoration = if (selected) TextDecoration.Underline else TextDecoration.None,
                            ),
                            modifier = Modifier
                                .flatClickable {
                                    expanded = false
                                    onSelect(option)
                                }
                                .padding(vertical = designDp(8f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditField(
    value: String,
    placeholder: String,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    onValueChange: (String) -> Unit,
) {
    val style = paragraphStyle()
    Column(Modifier.fillMaxWidth().padding(vertical = designDp(4f))) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = style,
            cursorBrush = SolidColor(LightStyle.content),
            keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = LightStyle.secondary))
                    inner()
                }
            },
        )
        Spacer(Modifier.height(designDp(4f)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LightStyle.secondary))
    }
}
