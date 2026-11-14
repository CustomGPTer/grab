package uk.jj.grab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Everything that can be personalised. Each change takes effect straight away and is remembered.
 *
 * @param onClearBrowserData wipes the browser's cookies, logins, cache and site data.
 * @param onThemeChanged     applies a new light/dark choice.
 * @param message            shows a short confirmation at the bottom of the screen.
 */
@Composable
fun SettingsScreen(padding: PaddingValues, onClearBrowserData: () -> Unit, onThemeChanged: () -> Unit, message: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var engineVersion by remember { mutableStateOf(Yt.version(ctx)) }
    val parallel by Prefs.parallel.flow.collectAsStateWithLifecycle()
    val startPage by Prefs.startPage.flow.collectAsStateWithLifecycle()
    val colour by Prefs.buttonColour.flow.collectAsStateWithLifecycle()
    val theme by Prefs.theme.flow.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Section("Downloads") }
        item { Pick("Downloads at once", "More at once finishes a queue sooner, but a site is more likely to slow you down.", listOf("1", "2", "3"), parallel - 1) { Prefs.parallel.value = it + 1 } }
        item { Toggle("Wi-Fi only", "Downloads wait in the queue while the phone is on mobile data.", Prefs.wifiOnly) }
        item { Toggle("Ask for quality every time", "Off: every download takes the best quality without asking.", Prefs.askQuality) }
        item { Toggle("Save privately by default", "Ticks 'Save privately' for you. Private files are kept inside Grab, hidden from Gallery, and are deleted if Grab is uninstalled.", Prefs.privateByDefault) }
        item { Toggle("Subtitles in the video", "When a site provides subtitles they are saved inside the MP4, to switch on or off in the player.", Prefs.subtitles) }
        item { Toggle("Cover art and tags on audio", "Audio files get the video's picture as album art, with title and artist filled in.", Prefs.coverArt) }
        item { Toggle("Offer links I've copied", "If you copied a link in another app, Grab offers to download it when opened.", Prefs.offerCopied) }

        item { Section("Browser") }
        item { Pick("Opens on", "What the Browser tab shows when Grab starts.", listOf("Home page", "Google", "Last page"), startPage) { Prefs.startPage.value = it } }
        item { Toggle("Block ads and pop-ups", "Master switch. The shield in the address bar still switches it off for one site.", Prefs.adBlock) }
        item { Toggle("Skip YouTube adverts", "Mutes and skips adverts inside YouTube's player. Switch off if YouTube objects.", Prefs.skipYouTubeAds) }
        item { Action("Clear history", "Removes the list of pages you have visited.", "Clear") { Places.clearHistory(); message("History cleared") } }
        item { Action("Clear browser data", "Removes cookies, logins, saved site data and cache.", "Clear") { confirmClear = true } }

        item { Section("Download button") }
        item { Pick("Colour when a video is found", "The button is grey until then.", listOf("Red", "Green", "Phone colour"), colour) { Prefs.buttonColour.value = it } }
        item { Action("Position", "Drag the button anywhere on the page. This puts it back in the bottom corner.", "Reset") { Prefs.resetButton(); message("Button moved back to the corner") } }

        item { Section("Appearance") }
        item {
            Pick("Light or dark", "Web pages follow the same choice.", listOf("Follow phone", "Dark", "Light"), theme) {
                Prefs.theme.value = it
                onThemeChanged()
            }
        }

        item { Section("Download engine") }
        item {
            val checked = Prefs.lastEngineCheck
            Action(
                "yt-dlp $engineVersion",
                "Updates itself once a day. Last checked: ${if (checked == 0L) "not yet" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(checked))}.",
                if (updating) "Updating" else "Update now",
                enabled = !updating,
            ) {
                updating = true
                scope.launch {
                    val result = Yt.updateNow(ctx)
                    engineVersion = Yt.version(ctx)
                    updating = false
                    message(result)
                }
            }
        }

        item { Section("About") }
        item {
            Text(
                "Grab ${BuildConfig.VERSION_NAME}\n\nNo ads, no analytics, no accounts. Grab only talks to the sites you visit or download from, " +
                    "and to GitHub once a day to update its download engine.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear browser data?") },
        text = { Text("Removes all cookies, logins, saved site data and cache from Grab's browser. Downloaded files, shortcuts and the download list are not touched.") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                onClearBrowserData()
                message("Browser data cleared")
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Label(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An on/off setting. Tapping anywhere on the row flips it. */
@Composable
private fun Toggle(title: String, detail: String, pref: Prefs.Bool) {
    val on by pref.flow.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().clickable { pref.value = !on }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, detail, Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = on, onCheckedChange = { pref.value = it })
    }
}

/** A pick-one setting shown as a row of chips. */
@Composable
private fun Pick(title: String, detail: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Label(title, detail)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, label ->
                FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(label) })
            }
        }
    }
}

/** A setting that is a one-off action, with a button. */
@Composable
private fun Action(title: String, detail: String, button: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title, detail, Modifier.weight(1f).padding(end = 16.dp))
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) }
    }
}
