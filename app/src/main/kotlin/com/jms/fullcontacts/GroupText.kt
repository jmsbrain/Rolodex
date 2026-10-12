package com.jms.fullcontacts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A contact with at least one number. */
private class Textable(val id: Long, val name: String, val numbers: List<DataChoice>)

/** Pick people (and which number) for a group text. */
@Composable
fun GroupTextScreen(activity: RolodexActivity, onClose: () -> Unit) {
    var people by remember { mutableStateOf<List<Textable>?>(null) }
    var query by remember { mutableStateOf("") }
    // Contact id → chosen number.
    val picked = remember { mutableStateMapOf<Long, DataChoice>() }

    LaunchedEffect(Unit) {
        people = withContext(Dispatchers.IO) {
            runCatching {
                val numbers = activity.repository.loadChoices(EditKind.PHONES).groupBy { it.contactId }
                activity.repository.loadSummaries().mapNotNull { s -> numbers[s.id]?.let { Textable(s.id, s.name, it) } }
            }.getOrDefault(emptyList())
        }
    }

    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    val filtered = remember(people, query) {
        val q = query.trim()
        people.orEmpty().filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = designDp(24f))) {
        Row(Modifier.fillMaxWidth().padding(vertical = designDp(12f))) {
            TextButton("CANCEL", onClick = onClose)
            Spacer(Modifier.weight(1f))
            if (picked.isNotEmpty()) {
                TextButton("TEXT (${picked.size})") {
                    activity.groupText(picked.values.map { it.value })
                    onClose()
                }
            }
        }
        SearchField(query) { query = it }
        Spacer(Modifier.height(physicalInches(0.1f)))

        val list = people
        when {
            list == null -> BasicText("Loading…", style = paragraphStyle().copy(color = LightStyle.secondary))
            list.isEmpty() -> BasicText("No contacts with phone numbers.", style = paragraphStyle())
            filtered.isEmpty() -> BasicText("No matches.", style = paragraphStyle().copy(color = LightStyle.secondary))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { person ->
                    PersonRow(
                        person = person,
                        chosen = picked[person.id],
                        onPick = { if (it == null) picked.remove(person.id) else picked[person.id] = it },
                    )
                }
                item { Spacer(Modifier.height(designDp(40f))) }
            }
        }
    }
}

/** One number toggles; several open a menu to choose. */
@Composable
private fun PersonRow(person: Textable, chosen: DataChoice?, onPick: (DataChoice?) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .flatClickable {
                    when {
                        person.numbers.size > 1 -> menuOpen = true
                        chosen != null -> onPick(null)
                        else -> onPick(person.numbers[0])
                    }
                }
                .padding(vertical = designDp(9f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(person.name, style = copyStyle().forList(), maxLines = 1)
                if (chosen != null && person.numbers.size > 1) {
                    BasicText("${chosen.label}  ${chosen.value}", style = labelStyle(), maxLines = 1)
                }
            }
            Spacer(Modifier.width(designDp(16f)))
            PickedDot(chosen != null)
        }
        if (menuOpen) {
            Popup(
                popupPositionProvider = DropdownPosition(alignEnd = true),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                val items = person.numbers.map { n ->
                    MenuItem("${n.label}  ${n.value}", selected = chosen == null || chosen.dataId == n.dataId) {
                        menuOpen = false
                        onPick(n)
                    }
                }
                val remove = if (chosen != null) listOf(MenuItem("REMOVE", selected = true) { menuOpen = false; onPick(null) }) else emptyList()
                MenuList(items + remove)
            }
        }
    }
}

/** Filled when picked, outlined when not. */
@Composable
private fun PickedDot(filled: Boolean) {
    Canvas(Modifier.size(designDp(22f))) {
        val stroke = 2.dp.toPx()
        if (filled) drawCircle(LightStyle.content)
        else drawCircle(LightStyle.content, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
    }
}
