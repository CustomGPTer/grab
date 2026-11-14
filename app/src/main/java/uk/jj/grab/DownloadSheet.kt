package uk.jj.grab

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID

private sealed interface SheetState {
    data class Loading(val text: String) : SheetState
    data class Video(val info: Lookup.Video, val src: Source, val viaStream: Boolean) : SheetState
    data class Playlist(val info: Lookup.Playlist, val src: Source) : SheetState
    data class PickStream(val reason: String) : SheetState
    data class Failed(val message: String, val log: String) : SheetState
}

/** The per-download choices made on the sheet. */
private class Options {
    var private by mutableStateOf(Prefs.privateByDefault.value)
    var sectionOn by mutableStateOf(false)
    var from by mutableStateOf("")
    var to by mutableStateOf("")
    var startAt by mutableLongStateOf(0L)
    /** File name typed with Rename, or null to use the video's own title. */
    var name by mutableStateOf<String?>(null)

    /** "start-end" in seconds, or null for the whole video. Null also if what was typed is not a valid range. */
    fun section(): String? {
        if (!sectionOn) return null
        val a = if (from.isBlank()) 0 else Yt.parseClock(from) ?: return null
        val b = Yt.parseClock(to) ?: return null
        return if (b > a) "$a-$b" else null
    }

    val sectionInvalid: Boolean get() = sectionOn && section() == null
}

/**
 * Works out what can be downloaded from [target] and lets the user choose.
 * Order of attempts: the download engine on the page address; the same again with the browser's login
 * for that site (only if the first try failed); then the streams the page was seen playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    target: Target,
    onDismiss: () -> Unit,
    onQueued: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val engineUpdating by Yt.updating.collectAsStateWithLifecycle()

    var state by remember(target) { mutableStateOf<SheetState>(SheetState.Loading("Reading page")) }
    var attempt by remember(target) { mutableIntStateOf(0) }
    var picked by remember(target) { mutableStateOf(target.startWith) }
    val options = remember(target) { Options() }

    // Each item is (address, title, item number within that address or 0).
    fun queue(items: List<Triple<String, String, Int>>, src: Source, choice: Choice) {
        val section = if (items.size == 1) options.section() else null
        // A name typed with Rename applies to a single video; several videos each keep their own title.
        val renamed = if (items.size == 1) safeName(options.name) else null
        DownloadStore.add(items.map { (url, title, item) ->
            Task(
                id = UUID.randomUUID().toString(),
                url = url,
                title = renamed ?: title,
                playlistItem = item,
                res = choice.res,
                audioFormat = choice.audioFormat,
                cookies = src.cookies,
                referer = src.referer,
                userAgent = src.userAgent,
                fixedName = renamed ?: src.fixedName,
                pageUrl = target.url,
                privateFile = options.private,
                privateSession = target.privateSession,
                section = section,
                startAt = options.startAt,
            )
        })
        if (options.startAt > System.currentTimeMillis()) Scheduler.arm(ctx) else DownloadService.kick(ctx)
        val what = if (items.size == 1) items[0].second else "${items.size} videos"       // .second = title
        onQueued(if (options.startAt > System.currentTimeMillis()) "$what, starting ${clock(options.startAt)}" else what)
    }

    // Leaving the sheet cancels this, which also stops the engine process doing the lookup.
    LaunchedEffect(target, attempt, picked) {
        state = SheetState.Loading("Reading page")
        val found = try {
            resolve(ctx, target, picked) { state = SheetState.Loading(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            SheetState.Failed(e.message ?: "Something went wrong.", e.log)
        } catch (e: Exception) {
            SheetState.Failed(e.message ?: "Something went wrong.", e.stackTraceToString().take(4000))
        }
        state = found
        // Setting "ask for quality every time" switched off: take best quality straight away.
        if (found is SheetState.Video && !Prefs.askQuality.value)
            queue(listOf(Triple(found.info.url ?: found.src.url, found.info.title, found.info.item)), found.src, found.info.choices.first())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            val heading = when (val s = state) {
                is SheetState.Video -> options.name ?: s.info.title
                is SheetState.Playlist -> s.info.title
                else -> target.title ?: target.url
            }
            Text(
                heading, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                target.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            when (val s = state) {
                is SheetState.Loading -> Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(if (engineUpdating) "Updating the download engine first" else s.text)
                }

                is SheetState.Video -> {
                    if (s.viaStream) Note("The engine did not recognise this page, so this is the stream the page itself was playing.")
                    OptionsBar(options, allowSection = true)
                    // Tap a tile to choose it, then Download. The quality picked last time starts highlighted.
                    var selected by remember(s.info) { mutableIntStateOf(preselect(s.info.choices)) }
                    var renaming by remember { mutableStateOf(false) }
                    QualityGrid(s.info.choices, selected) { selected = it }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(onClick = { renaming = true }) { Text("Rename") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = !options.sectionInvalid,
                            onClick = {
                                val c = s.info.choices[selected.coerceIn(0, s.info.choices.lastIndex)]
                                Prefs.lastAudio = c.audioFormat ?: ""
                                if (c.audioFormat == null) Prefs.lastRes = c.res
                                queue(listOf(Triple(s.info.url ?: s.src.url, s.info.title, s.info.item)), s.src, c)
                            },
                        ) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download")
                        }
                    }
                    if (renaming) RenameDialog(
                        current = options.name ?: s.info.title,
                        onDismiss = { renaming = false },
                    ) { options.name = it; renaming = false }
                    if (target.streams.size > 1 || (target.streams.isNotEmpty() && !s.viaStream)) {
                        TextButton(onClick = { state = SheetState.PickStream("Wrong video? These are the streams this page was seen playing.") },
                            modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("Not the right video? Show streams found on the page (${target.streams.size})")
                        }
                    }
                }

                is SheetState.Playlist -> {
                    OptionsBar(options, allowSection = false)
                    PlaylistPicker(s.info) { entries, choice ->
                        // Each video is its own download, saved under its own title.
                        queue(entries.map { Triple(if (it.item > 0) s.src.url else it.url, it.title, it.item) }, s.src, choice)
                    }
                }

                is SheetState.PickStream -> {
                    Note(s.reason)
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(target.streams) { i, st ->
                            ListItem(
                                headlineContent = { Text("Stream ${i + 1} · ${st.label}") },
                                supportingContent = { Text(st.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = {
                                    Icon(painterResource(if (st.kind == "Audio file") R.drawable.ic_audio else R.drawable.ic_movie), contentDescription = null)
                                },
                                modifier = Modifier.clickable { if (picked == st) attempt++ else picked = st },
                            )
                        }
                    }
                }

                is SheetState.Failed -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Couldn't get a video from this", style = MaterialTheme.typography.titleSmall)
                    Text(s.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    val hint = when {
                        !target.fromBrowser -> "Open it in the Browser tab, start the video playing, then tap Download. Grab can then save the stream the page plays."
                        target.streams.isEmpty() -> "Start the video playing on the page, then tap Download again so Grab can catch its stream."
                        else -> null
                    }
                    if (hint != null) Text(
                        hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { picked = target.startWith; attempt++ }) { Text("Retry") }
                        if (!target.fromBrowser) TextButton(onClick = { onOpenInBrowser(target.url) }) { Text("Open in Browser") }
                        if (target.streams.size > 1) TextButton(onClick = { state = SheetState.PickStream("Streams this page was seen playing:") }) { Text("Other streams") }
                        TextButton(onClick = { copyText(ctx, "${target.url}\n\n${s.log}") }) { Text("Copy error") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
)

private fun clock(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

/** Which tile starts highlighted: the quality picked last time if this video has it, otherwise the highest. */
private fun preselect(choices: List<Choice>): Int {
    val audio = Prefs.lastAudio
    if (audio.isNotEmpty()) choices.indexOfFirst { it.audioFormat == audio }.let { if (it >= 0) return it }
    val res = Prefs.lastRes
    if (res > 0) choices.indexOfFirst { it.audioFormat == null && it.res == res }.let { if (it >= 0) return it }
    return 0
}

/** The qualities as a three-across grid of tiles: resolution (or audio format) with the exact file size where known. */
@Composable
private fun QualityGrid(choices: List<Choice>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp)) {
        choices.withIndex().chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (i, c) ->
                    Column(Modifier.weight(1f).padding(5.dp)) { QualityTile(c, i == selected) { onSelect(i) } }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QualityTile(c: Choice, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val colours = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) colours.primaryContainer else colours.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colours.primary else colours.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            c.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (selected) colours.onPrimaryContainer else colours.onSurface,
        )
        // Second line: "60fps" or "Audio", and the file size when the site states one. Blank otherwise.
        val detail = listOfNotNull(c.detail.ifBlank { null }, if (c.size > 0) humanSize(c.size) else null).joinToString(" · ")
        Text(
            detail.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            color = if (selected) colours.onPrimaryContainer else colours.onSurfaceVariant,
        )
    }
}

/** Change the name the file will be saved under. The extension (.mp4, .mp3 ...) is added automatically. */
@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File name") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        },
        confirmButton = { TextButton(enabled = safeName(text) != null, onClick = { onRename(safeName(text)) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Save privately / just a section / start later. Shown above the quality list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsBar(o: Options, allowSection: Boolean) {
    var pickingTime by remember { mutableStateOf(false) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = o.private, onClick = { o.private = !o.private }, label = { Text("Save privately") })
        if (allowSection) FilterChip(selected = o.sectionOn, onClick = { o.sectionOn = !o.sectionOn }, label = { Text("Just a section") })
        FilterChip(
            selected = o.startAt > 0,
            onClick = { if (o.startAt > 0) o.startAt = 0 else pickingTime = true },
            label = { Text(if (o.startAt > 0) "Starts ${clock(o.startAt)}" else "Start later") },
        )
    }
    if (o.private) Note("Kept inside Grab, hidden from Gallery and other apps. Find it under Files, Private.")
    if (allowSection && o.sectionOn) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = o.from, onValueChange = { o.from = it }, singleLine = true, label = { Text("From") }, placeholder = { Text("0:00") },
                modifier = Modifier.width(120.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = o.to, onValueChange = { o.to = it }, singleLine = true, label = { Text("To") }, placeholder = { Text("1:30") },
                isError = o.sectionInvalid && o.to.isNotBlank(),
                modifier = Modifier.width(120.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        }
        Note(
            if (o.sectionInvalid) "Enter times as minutes:seconds, for example 1:30. 'To' must be later than 'From'."
            else "Only this part is saved. The cut can be a few seconds out either side."
        )
    }

    if (pickingTime) {
        val now = remember { Calendar.getInstance() }
        val picker = rememberTimePickerState(initialHour = (now.get(Calendar.HOUR_OF_DAY) + 1) % 24, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("Start downloading at") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = picker)
                    Text("If that time has already passed today, it starts tomorrow.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val at = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, picker.hour); set(Calendar.MINUTE, picker.minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis() + 30_000) add(Calendar.DAY_OF_YEAR, 1)
                    }
                    o.startAt = at.timeInMillis
                    pickingTime = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
        )
    }
}

/** Tick list for a link holding several videos. Each one is saved under its own title. */
@Composable
private fun PlaylistPicker(info: Lookup.Playlist, onDownload: (List<Entry>, Choice) -> Unit) {
    val ticked: SnapshotStateList<Boolean> = remember(info) { info.entries.map { true }.toMutableStateList() }
    var quality by remember { mutableIntStateOf(0) }
    val count = ticked.count { it }

    Row(
        Modifier.fillMaxWidth().clickable {
            val all = count < info.entries.size
            for (i in ticked.indices) ticked[i] = all
        }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = count == info.entries.size, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text("Select all (${info.entries.size} videos)", style = MaterialTheme.typography.bodyLarge)
    }
    LazyColumn(Modifier.heightIn(max = 260.dp)) {
        itemsIndexed(info.entries) { i, e ->
            Row(
                Modifier.fillMaxWidth().clickable { ticked[i] = !ticked[i] }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = ticked[i], onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (e.duration > 0) Text(
                        "%d:%02d".format(e.duration / 60, e.duration % 60),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Text("Quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Yt.batchChoices.forEachIndexed { i, c ->
            FilterChip(selected = quality == i, onClick = { quality = i }, label = { Text(c.label) })
        }
    }
    Button(
        enabled = count > 0,
        onClick = { onDownload(info.entries.filterIndexed { i, _ -> ticked[i] }, Yt.batchChoices[quality]) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    ) { Text(if (count == 1) "Download 1 video" else "Download $count videos") }
}

private fun toState(found: Lookup, src: Source, viaStream: Boolean, pageTitle: String?): SheetState = when (found) {
    // A caught stream has no title of its own, so it takes the page's.
    is Lookup.Video -> SheetState.Video(if (viaStream && pageTitle != null) found.copy(title = pageTitle) else found, src, viaStream)
    is Lookup.Playlist -> SheetState.Playlist(found, src)
}

private suspend fun resolve(ctx: Context, t: Target, picked: Stream?, stage: (String) -> Unit): SheetState {
    val log = StringBuilder()
    var reason = "No downloadable video was recognised at this address."
    val private = t.privateSession

    suspend fun attempt(src: Source): Lookup? = try {
        Yt.lookup(ctx, src)
    } catch (e: EngineException) {
        reason = e.message ?: reason
        log.append(e.log).append("\n\n")
        null
    }

    if (picked == null) {
        val plain = Source(t.url, privateSession = private)
        attempt(plain)?.let { return toState(it, plain, false, t.title) }
        if (Cookies.has(private, t.url)) {
            stage("Retrying with your login for this site")
            val withLogin = Source(t.url, cookies = true, userAgent = t.userAgent, privateSession = private)
            attempt(withLogin)?.let { return toState(it, withLogin, false, t.title) }
        }
        if (t.streams.isEmpty()) return SheetState.Failed(reason, log.toString())
    }

    val stream = picked ?: t.streams.first()
    stage("Reading the stream this page played")
    val plain = Source(stream.url, referer = stream.referer ?: t.url, userAgent = t.userAgent, fixedName = safeName(t.title), privateSession = private)
    attempt(plain)?.let { return toState(it, plain, true, t.title) }
    if (Cookies.has(private, stream.url, t.url)) {
        stage("Retrying the stream with your login")
        val withLogin = plain.copy(cookies = true)
        attempt(withLogin)?.let { return toState(it, withLogin, true, t.title) }
    }
    return if (picked == null && t.streams.size > 1) SheetState.PickStream("The first stream could not be read. Try another one this page played:")
    else SheetState.Failed(reason, log.toString())
}
