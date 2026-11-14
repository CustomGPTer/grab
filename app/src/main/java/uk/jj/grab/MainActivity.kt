package uk.jj.grab

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.textclassifier.TextClassifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var normal: Browser

    /** The private-mode browser, while private mode is on. It has its own cookies, wiped when it is closed. */
    private var privateBrowser by mutableStateOf<Browser?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Browser.wipePrivateData()          // anything private mode left behind if Grab was closed abruptly

        // After Android has closed the app in the background, come back to the page that was open;
        // otherwise start where the setting says.
        val start = savedInstanceState?.getString(KEY_URL) ?: when (Prefs.startPage.value) {
            Prefs.START_GOOGLE -> Browser.GOOGLE
            Prefs.START_LAST -> Prefs.lastUrl.ifBlank { null }
            else -> null                    // Grab's own home page
        }
        normal = Browser(this, start)

        // A shared link is acted on once, not again each time Android rebuilds the screen.
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            GrabTheme {
                GrabApp(
                    browser = privateBrowser ?: normal,
                    privateOn = privateBrowser != null,
                    setPrivate = ::setPrivate,
                    onClearBrowserData = { normal.clearData() },
                    onThemeChanged = {
                        // Rebuild the screen so the window and the web pages pick the new light/dark choice up at once.
                        App.applyTheme(this)
                        recreate()
                    },
                )
            }
        }
    }

    /** Turns private mode on or off. Returns a short message to show. */
    private fun setPrivate(on: Boolean): String {
        if (on) {
            if (privateBrowser != null) return "Private mode is already on"
            if (!Browser.privateModeSupported()) return "Private mode needs a newer Android System WebView on this phone"
            return try {
                normal.pause()
                val b = Browser(this, null, Browser.PRIVATE_PROFILE)
                Cookies.privateJar = b.cookieJar
                privateBrowser = b
                "Private mode on"
            } catch (e: Throwable) {
                Browser.wipePrivateData()
                "Private mode could not start on this phone"
            }
        }
        val b = privateBrowser ?: return "Private mode is already off"
        privateBrowser = null
        Cookies.privateJar = null
        b.destroy()
        // Its data can only be deleted once its web view has fully gone, so try now and again shortly after.
        for (delay in longArrayOf(0, 400, 2000)) window.decorView.postDelayed({ if (privateBrowser == null) Browser.wipePrivateData() }, delay)
        return "Private mode off. Its cookies and logins have been wiped."
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The phone switched between light and dark without the screen being rebuilt: re-read the theme.
        theme.applyStyle(R.style.Theme_Grab, true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        (privateBrowser ?: normal).resume()
        Yt.checkDaily(applicationContext)
        // Carry on with anything that was queued or interrupted last time.
        if (DownloadStore.tasks.value.any { it.status == Status.QUEUED }) DownloadService.kick(this)
    }

    override fun onStop() {
        normal.pause()
        privateBrowser?.pause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!normal.home.value && normal.url.value.isNotBlank()) outState.putString(KEY_URL, normal.url.value)
    }

    override fun onDestroy() {
        privateBrowser?.destroy()
        privateBrowser = null
        Cookies.privateJar = null
        normal.destroy()
        Browser.wipePrivateData()
        super.onDestroy()
    }

    // The clipboard can only be looked at while Grab has the screen.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Prefs.offerCopied.value) checkClipboard()
    }

    /**
     * If a link was copied in another app since Grab last looked, offer to download it.
     * The clipboard's description is checked first, so Android's "pasted from clipboard" notice
     * only appears when there is a fresh entry that looks like a web address.
     */
    private fun checkClipboard() {
        try {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return
            val d = clipboard.primaryClipDescription ?: return
            if (d.label?.toString() == OWN_CLIP) return                                   // something Grab copied itself
            if (!d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return
            val stamp = d.timestamp
            if (stamp == 0L || stamp == Prefs.lastClipStamp) return           // already seen
            Prefs.lastClipStamp = stamp
            if (Build.VERSION.SDK_INT >= 31 && d.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
                d.getConfidenceScore(TextClassifier.TYPE_URL) < 0.5f) return   // Android says it is not a web address
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return
            val url = Regex("""https?://\S+""").find(text)?.value ?: return
            DownloadStore.copiedUrl.value = url
        } catch (_: Exception) { /* clipboard not readable right now */ }
    }

    /** Text shared to Grab from another app: take the first web address in it. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("""https?://\S+""").find(text)?.value ?: return
        DownloadStore.incomingUrl.value = url
    }

    private companion object {
        const val KEY_URL = "browser_url"
    }
}

@Composable
fun GrabTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val choice by Prefs.theme.flow.collectAsStateWithLifecycle()
    val dark = when (choice) {
        Prefs.THEME_DARK -> true
        Prefs.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String, val icon: Int?) {
    BROWSER("Browser", R.drawable.ic_globe),
    DOWNLOADS("Downloads", R.drawable.ic_download),
    FILES("Files", R.drawable.ic_folder),
    SETTINGS("Settings", null),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrabApp(
    browser: Browser,
    privateOn: Boolean,
    setPrivate: (Boolean) -> String,
    onClearBrowserData: () -> Unit,
    onThemeChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(Tab.BROWSER.ordinal) }
    var target by remember { mutableStateOf<Target?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val tasks by DownloadStore.tasks.collectAsStateWithLifecycle()
    val openCount = tasks.count { it.open }
    val home by browser.home.collectAsStateWithLifecycle()
    val pageUrl by browser.url.collectAsStateWithLifecycle()
    val bookmarks by Places.bookmarks.collectAsStateWithLifecycle()

    fun say(text: String) { scope.launch { snack.showSnackbar(text) } }

    // Notification permission (Android 13+) so download progress is visible.
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // A link arriving from the share sheet.
    val incoming by DownloadStore.incomingUrl.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        incoming?.let {
            target = Target(it, userAgent = browser.userAgent)
            tab = Tab.DOWNLOADS.ordinal
            DownloadStore.incomingUrl.value = null
        }
    }

    // A link copied in another app: offer it, once.
    val copied by DownloadStore.copiedUrl.collectAsStateWithLifecycle()
    LaunchedEffect(copied) {
        val url = copied ?: return@LaunchedEffect
        DownloadStore.copiedUrl.value = null
        if (target != null) return@LaunchedEffect
        val result = snack.showSnackbar("Download the link you copied? ${hostOf(url) ?: ""}", actionLabel = "Download", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) target = Target(url, userAgent = browser.userAgent)
    }

    // Pop-ups and redirects the browser stopped: say so, and let the user open one if it was wanted.
    LaunchedEffect(browser) {
        browser.blocked.collect { url ->
            snack.currentSnackbarData?.dismiss()
            scope.launch {
                val result = snack.showSnackbar("Pop-up blocked: ${hostOf(url) ?: "unknown site"}", actionLabel = "Open", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) browser.load(url)
            }
        }
    }

    // The menu on the browser's address bar.
    val browserMenu: @Composable () -> Unit = {
        var open by remember { mutableStateOf(false) }
        val onPage = !home && pageUrl.startsWith("http")
        val saved = bookmarks.any { it.url == pageUrl }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Back") }, enabled = !home, onClick = { open = false; browser.back() })
                DropdownMenuItem(text = { Text("Forward") }, onClick = { open = false; browser.forward() })
                if (onPage) DropdownMenuItem(
                    text = { Text(if (saved) "Remove from home page" else "Add to home page") },
                    onClick = {
                        open = false
                        if (saved) { Places.removeBookmark(pageUrl); say("Removed from home page") }
                        else { Places.addBookmark(browser.title.value, pageUrl); say("Added to home page") }
                    },
                )
                DropdownMenuItem(text = { Text("History") }, onClick = { open = false; showHistory = true })
                DropdownMenuItem(
                    text = { Text(if (privateOn) "Leave private mode" else "Private mode") },
                    onClick = { open = false; say(setPrivate(!privateOn)) },
                )
                HorizontalDivider()
                // For a page left dimmed and dead by one of its own pop-ups.
                DropdownMenuItem(text = { Text("Remove page overlay") }, enabled = onPage, onClick = {
                    open = false
                    browser.removeOverlay { n ->
                        say(if (n > 0) "Removed $n overlay${if (n == 1) "" else "s"}. Reloading the page brings them back." else "Nothing found covering this page")
                    }
                })
                DropdownMenuItem(text = { Text("Copy page report") }, enabled = onPage, onClick = {
                    open = false
                    browser.report { text ->
                        copyText(ctx, text)
                        say("Page report copied")
                    }
                })
            }
        }
    }

    Scaffold(
        topBar = {
            // The browser tab has its own address bar; the others get a plain title bar.
            if (tab != Tab.BROWSER.ordinal) TopAppBar(title = { Text(Tab.entries[tab].label) })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        label = { Text(if (t == Tab.DOWNLOADS && openCount > 0) "${t.label} ($openCount)" else t.label, maxLines = 1) },
                        icon = {
                            if (t.icon != null) Icon(painterResource(t.icon), contentDescription = null)
                            else Icon(Icons.Filled.Settings, contentDescription = null)
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        when (Tab.entries[tab]) {
            Tab.BROWSER -> BrowserScreen(padding, browser, browserMenu) { target = it }
            Tab.DOWNLOADS -> DownloadsScreen(padding) { target = Target(it, userAgent = browser.userAgent) }
            Tab.FILES -> FilesScreen(padding)
            Tab.SETTINGS -> SettingsScreen(padding, onClearBrowserData, onThemeChanged) { say(it) }
        }
    }

    target?.let { t ->
        DownloadSheet(
            target = t,
            onDismiss = { target = null },
            onQueued = { what ->
                target = null
                tab = Tab.DOWNLOADS.ordinal
                say("Queued: $what")
            },
            onOpenInBrowser = { url ->
                target = null
                browser.load(url)
                tab = Tab.BROWSER.ordinal
            },
        )
    }

    if (showHistory) HistoryDialog(onDismiss = { showHistory = false }) { url ->
        showHistory = false
        browser.load(url)
    }
}

/** Every page visited (outside private mode), newest first. */
@Composable
private fun HistoryDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val history by Places.history.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History") },
        text = {
            if (history.isEmpty()) Text("No pages visited yet. Pages opened in private mode are never listed here.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(history, key = { it.url }) { place ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(place.url) }.padding(vertical = 8.dp)) {
                        Text(place.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            hostOf(place.url) ?: place.url, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (history.isNotEmpty()) TextButton(onClick = { Places.clearHistory() }) { Text("Clear") } },
    )
}

/** Shown in place of an empty list. */
@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
}
