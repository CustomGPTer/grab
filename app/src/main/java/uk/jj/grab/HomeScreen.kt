package uk.jj.grab

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val TILE_COLOURS = listOf(
    Color(0xFF1E88E5), Color(0xFFD81B60), Color(0xFF43A047), Color(0xFFF4511E),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFF3949AB),
)

/**
 * Grab's own start page: a search box, your shortcut tiles (the bookmarks) and recently visited pages.
 * In private mode the recent list is not shown and nothing is added to it.
 */
@Composable
fun HomeScreen(private: Boolean, onOpen: (String) -> Unit) {
    val bookmarks by Places.bookmarks.collectAsStateWithLifecycle()
    val history by Places.history.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Place?>(null) }
    val focus = LocalFocusManager.current

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp)) {
                if (private) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    Icon(painterResource(R.drawable.ic_private), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        "Private mode. Nothing is added to history, and logins made here are wiped when you leave it.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search or enter address") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        focus.clearFocus()
                        if (query.isNotBlank()) onOpen(query)
                    }),
                )
            }
        }

        item { Heading("Shortcuts") }
        // Four tiles to a row; the last tile adds a new one.
        val tiles: List<Place?> = bookmarks + listOf(null)
        items(tiles.chunked(4)) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                row.forEach { place ->
                    Box(Modifier.weight(1f)) {
                        if (place == null) Tile("Add", null, onClick = { adding = true }, onLongClick = {})
                        else Tile(place.title, place, onClick = { onOpen(place.url) }, onLongClick = { removing = place })
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            Text(
                "Press and hold a shortcut to remove it. Use the menu on any page to add it here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (!private && history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Heading("Recent") }
                    TextButton(onClick = { Places.clearHistory() }) { Text("Clear history") }
                }
            }
            items(history.take(25), key = { it.url }) { place ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(place.url) }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(place.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        hostOf(place.url) ?: place.url, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (adding) AddShortcutDialog(onDismiss = { adding = false }) { title, url ->
        Places.addBookmark(title, Browser.normalise(url))
        adding = false
    }

    removing?.let { place ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove shortcut?") },
            text = { Text(place.title) },
            confirmButton = { TextButton(onClick = { Places.removeBookmark(place.url); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Heading(text: String) = Text(
    text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
)

/** A round coloured tile with the site's initial, and its name underneath. No site logos are used. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(label: String, place: Place?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val colour = if (place == null) MaterialTheme.colorScheme.surfaceVariant
        else TILE_COLOURS[(place.url.hashCode() and 0x7fffffff) % TILE_COLOURS.size]
        Box(Modifier.size(52.dp).background(colour, CircleShape), contentAlignment = Alignment.Center) {
            if (place == null) Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(label.trim().take(1).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AddShortcutDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true, label = { Text("Address") },
                    placeholder = { Text("example.com") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = address.contains('.'), onClick = { onAdd(name.trim(), address.trim()) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
