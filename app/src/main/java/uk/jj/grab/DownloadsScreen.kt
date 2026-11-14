package uk.jj.grab

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun DownloadsScreen(padding: PaddingValues, onPaste: (String) -> Unit) {
    val ctx = LocalContext.current
    val tasks by DownloadStore.tasks.collectAsStateWithLifecycle()
    val wifi by Net.wifi.collectAsStateWithLifecycle()
    val wifiOnly by Prefs.wifiOnly.flow.collectAsStateWithLifecycle()
    var showPaste by remember { mutableStateOf(false) }
    val waitingForWifi = wifiOnly && !wifi

    Box(Modifier.fillMaxSize().padding(padding)) {
        if (tasks.isEmpty()) {
            EmptyState(
                "Nothing queued",
                "Share a link to Grab from any app, tap + to paste one, or open the page in the Browser tab and tap the Download button.",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "actions") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (tasks.any { it.active }) TextButton(onClick = { DownloadService.pauseAll(ctx) }) { Text("Pause all") }
                        if (tasks.any { it.status == Status.PAUSED }) TextButton(onClick = { DownloadService.resumeAll(ctx) }) { Text("Resume all") }
                        if (tasks.any { !it.open }) TextButton(onClick = { DownloadStore.clearFinished() }) { Text("Clear finished") }
                    }
                }
                if (waitingForWifi && tasks.any { it.status == Status.QUEUED }) item(key = "wifi") {
                    Text(
                        "Wi-Fi only is on and the phone is on mobile data. Downloads start when Wi-Fi is back.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                // Newest first; running, waiting and paused ones stay at the top.
                items(tasks.sortedWith(compareBy<Task> { !it.open }.thenByDescending { it.created }), key = { it.id }) {
                    TaskCard(it, ctx, waitingForWifi)
                }
            }
        }
        FloatingActionButton(onClick = { showPaste = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Paste a link")
        }
    }

    if (showPaste) PasteDialog(onDismiss = { showPaste = false }) { showPaste = false; onPaste(it) }
}

@Composable
private fun TaskCard(task: Task, ctx: Context, waitingForWifi: Boolean) {
    val failed = task.status == Status.FAILED
    val scheduled = task.status == Status.QUEUED && task.startAt > System.currentTimeMillis()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusLine(task, waitingForWifi),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                when {
                    task.active -> {
                        if (scheduled) IconButton(onClick = { DownloadService.resume(ctx, task.id) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Start now")
                        } else IconButton(onClick = { DownloadService.pause(ctx, task.id) }) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                        }
                        IconButton(onClick = { DownloadService.cancel(ctx, task.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    }
                    task.status == Status.PAUSED -> {
                        IconButton(onClick = { DownloadService.resume(ctx, task.id) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                        }
                        IconButton(onClick = { DownloadService.cancel(ctx, task.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    }
                    task.status == Status.DONE -> {
                        if (task.outputUri != null) IconButton(onClick = {
                            PlayerActivity.open(ctx, Uri.parse(task.outputUri), task.outputName ?: task.title)
                        }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") }
                        IconButton(onClick = { DownloadStore.remove(task.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove from list")
                        }
                    }
                    else -> {
                        IconButton(onClick = { DownloadService.resume(ctx, task.id) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Try again")
                        }
                        if (failed && task.log != null) IconButton(onClick = { copyText(ctx, "${task.pageUrl ?: task.url}\n\n${task.log}") }) {
                            Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy error details")
                        }
                        IconButton(onClick = { DownloadStore.remove(task.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove from list")
                        }
                    }
                }
            }
            val bar = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp)
            if (task.status == Status.RUNNING) {
                if (task.progress in 0f..99.9f) LinearProgressIndicator(progress = { task.progress / 100f }, modifier = bar)
                else LinearProgressIndicator(modifier = bar)
            } else if (task.status == Status.PAUSED && task.progress in 0f..99.9f) {
                LinearProgressIndicator(progress = { task.progress / 100f }, modifier = bar, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun quality(t: Task): String = buildString {
    append(
        when {
            t.audioFormat != null -> "Audio ${t.audioFormat.uppercase()}"
            t.res > 0 -> "${t.res}p"
            else -> "Best"
        }
    )
    if (t.section != null) append(" · section")
    if (t.privateFile) append(" · private")
}

private fun statusLine(t: Task, waitingForWifi: Boolean): String = when (t.status) {
    Status.QUEUED -> when {
        t.startAt > System.currentTimeMillis() -> "Starts ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.startAt))} · ${quality(t)}"
        waitingForWifi -> "Waiting for Wi-Fi · ${quality(t)}"
        else -> "Waiting · ${quality(t)}"
    }
    Status.RUNNING -> buildString {
        append(t.phase.ifBlank { "Starting" })
        if (t.progress in 0f..99.9f) append(" · ${t.progress.toInt()}%")
        fmtEta(t.eta).takeIf { it.isNotEmpty() && t.progress in 0f..99.9f }?.let { append(" · $it") }
        append(" · ${quality(t)}")
    }
    Status.PAUSED -> buildString {
        append("Paused")
        if (t.progress in 0f..99.9f) append(" · ${t.progress.toInt()}%")
        append(" · ${quality(t)}")
    }
    Status.DONE -> (t.outputName ?: "Done") + if (t.privateFile) " · private" else ""
    Status.FAILED -> t.error ?: "Failed"
    Status.CANCELLED -> "Cancelled"
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val clip = LocalClipboardManager.current
    var text by remember {
        val fromClip = clip.getText()?.text?.let { Regex("""https?://\S+""").find(it)?.value } ?: ""
        mutableStateOf(fromClip)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download from a link") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.trim().startsWith("http"), onClick = { onSubmit(text.trim()) }) { Text("Fetch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
