package uk.jj.grab

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A saved download. [file] is set for one kept in Grab's private folder. */
data class MediaFile(val uri: Uri, val name: String, val size: Long, val added: Long, val mime: String, val isAudio: Boolean, val file: File? = null)

/** Everything Grab has saved to Movies/Grab and Music/Grab. No storage permission needed: the app owns these files. */
private fun queryGallery(ctx: Context): List<MediaFile> {
    val out = mutableListOf<MediaFile>()
    fun query(collection: Uri, path: String, isAudio: Boolean) {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE,
        )
        try {
            ctx.contentResolver.query(
                collection, columns, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$path%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += MediaFile(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = c.getString(1) ?: "",
                        size = c.getLong(2),
                        added = c.getLong(3) * 1000,
                        mime = c.getString(4) ?: "*/*",
                        isAudio = isAudio,
                    )
                }
            }
        } catch (_: Exception) { /* storage not ready; show what we have */ }
    }
    query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/Grab", false)
    query(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Music/Grab", true)
    return out.sortedByDescending { it.added }
}

/** Files kept inside Grab, which Gallery and other apps cannot see. */
private fun queryPrivate(ctx: Context): List<MediaFile> =
    Library.privateDir(ctx).listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }.map {
        val audio = Library.isAudio(it.name)
        MediaFile(Uri.fromFile(it), it.name, it.length(), it.lastModified(), if (audio) "audio/*" else "video/*", audio, it)
    }

@Composable
fun FilesScreen(padding: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by DownloadStore.tasks.collectAsStateWithLifecycle()
    val doneCount = tasks.count { it.status == Status.DONE }
    var showPrivate by rememberSaveable { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<MediaFile?>(null) }
    var renaming by remember { mutableStateOf<MediaFile?>(null) }

    LaunchedEffect(doneCount, refresh, showPrivate) {
        files = withContext(Dispatchers.IO) { if (showPrivate) queryPrivate(ctx) else queryGallery(ctx) }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showPrivate, onClick = { showPrivate = false }, label = { Text("Gallery") })
            FilterChip(selected = showPrivate, onClick = { showPrivate = true }, label = { Text("Private") })
        }
        if (files.isEmpty()) {
            if (showPrivate) EmptyState("No private files", "Tick 'Save privately' when downloading to keep a file here, hidden from Gallery and other apps. Private files are deleted if Grab is uninstalled.")
            else EmptyState("No files yet", "Finished downloads are saved to Movies/Grab (video) and Music/Grab (audio). They appear here and in Gallery.")
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(files, key = { it.uri.toString() }) { f ->
                    FileRow(
                        f,
                        onOpen = { PlayerActivity.open(ctx, f.uri, f.name) },
                        onDelete = { pendingDelete = f },
                        onRename = { renaming = f },
                        onMoveToGallery = {
                            scope.launch {
                                withContext(Dispatchers.IO) { try { f.file?.let { Library.moveToGallery(ctx, it) } } catch (_: Exception) { } }
                                refresh++
                            }
                        },
                    )
                }
            }
        }
    }

    renaming?.let { f ->
        var text by remember(f) { mutableStateOf(f.name.substringBeforeLast('.')) }
        var failed by remember(f) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename file") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it; failed = false }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    if (failed) Text(
                        "That name could not be used. Another file may already have it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = safeName(text) != null, onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { rename(ctx, f, text) }
                        if (ok) { renaming = null; refresh++ } else failed = true
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    pendingDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this file?") },
            text = { Text(f.name) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        if (f.file != null) f.file.delete() else ctx.contentResolver.delete(f.uri, null, null)
                        Prefs.setPlayPosition(f.uri.toString(), 0)
                    } catch (_: Exception) { }
                    pendingDelete = null
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(f: MediaFile, onOpen: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit, onMoveToGallery: () -> Unit) {
    val ctx = LocalContext.current
    var thumb by remember(f.uri) { mutableStateOf<ImageBitmap?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(f.uri) {
        if (!f.isAudio) thumb = withContext(Dispatchers.IO) {
            try {
                val size = Size(320, 180)
                (if (f.file != null) ThumbnailUtils.createVideoThumbnail(f.file, size, null) else ctx.contentResolver.loadThumbnail(f.uri, size, null)).asImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) Image(t, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(
                painterResource(if (f.isAudio) R.drawable.ic_audio else R.drawable.ic_movie),
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${humanSize(f.size)}  ·  ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.added))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (f.file == null) DropdownMenuItem(text = { Text("Open in another app") }, onClick = { menu = false; openElsewhere(ctx, f) })
                else DropdownMenuItem(text = { Text("Move to Gallery") }, onClick = { menu = false; onMoveToGallery() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Renames a saved file, keeping its extension. Works for Gallery files (which Grab owns) and private ones. */
private fun rename(ctx: Context, f: MediaFile, newName: String): Boolean {
    val base = safeName(newName) ?: return false
    val ext = f.name.substringAfterLast('.', "")
    val name = if (ext.isEmpty()) base else "$base.$ext"
    if (name == f.name) return true
    return try {
        if (f.file != null) {
            val dest = File(f.file.parentFile, name)
            if (dest.exists()) false
            else f.file.renameTo(dest).also { moved ->
                // The player remembers its place by the file's address, which has just changed.
                if (moved) {
                    Prefs.setPlayPosition(Uri.fromFile(dest).toString(), Prefs.playPosition(f.uri.toString()))
                    Prefs.setPlayPosition(f.uri.toString(), 0)
                }
            }
        } else {
            ctx.contentResolver.update(f.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0
        }
    } catch (_: Exception) { false }
}

private fun openElsewhere(ctx: Context, f: MediaFile) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { }
}
