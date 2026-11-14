package uk.jj.grab

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
fun BrowserScreen(
    padding: PaddingValues,
    browser: Browser,
    menu: @Composable () -> Unit,
    onDownload: (Target) -> Unit,
) {
    val url by browser.url.collectAsStateWithLifecycle()
    val home by browser.home.collectAsStateWithLifecycle()
    val progress by browser.progress.collectAsStateWithLifecycle()
    val fullscreen by browser.fullscreen.collectAsStateWithLifecycle()
    val adsBlocked by browser.adsBlocked.collectAsStateWithLifecycle()
    val streams by browser.sniffer.count.collectAsStateWithLifecycle()
    val players by browser.videos.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    // The address box follows the page unless the user is typing in it.
    var address by remember(browser) { mutableStateOf(url) }
    var editing by remember(browser) { mutableStateOf(false) }
    LaunchedEffect(url, editing, home) { if (!editing) address = if (home) "" else url }

    // Back leaves fullscreen first, then walks back through the page history, then to Grab's home page.
    BackHandler(enabled = !home && !fullscreen) { browser.back() }
    BackHandler(enabled = fullscreen) { browser.exitFullscreen() }

    // The page keeps living when this tab is hidden, but anything playing is paused.
    DisposableEffect(browser) {
        browser.resume()
        browser.onDirectDownload = onDownload
        onDispose {
            browser.onDirectDownload = null
            browser.pause()
        }
    }

    // Look for video players on the page while it is on screen (this is what spots YouTube-style players).
    LaunchedEffect(browser, home) {
        if (!home) while (true) {
            delay(1500)
            browser.probeVideos()
        }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        if (browser.isPrivate) Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_private), contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(16.dp))
            Text("Private mode", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            IconButton(onClick = { focus.clearFocus(); browser.goHome() }, enabled = !home) {
                Icon(Icons.Filled.Home, contentDescription = "Home page")
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                placeholder = { Text("Search or enter address") },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).onFocusChanged { editing = it.isFocused },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    focus.clearFocus()
                    if (address.isNotBlank()) browser.load(address)
                }),
                // The shield shows whether blocking is on for this site; tap it to switch.
                leadingIcon = if (home) null else {
                    {
                        IconButton(onClick = { browser.toggleAdBlock() }) {
                            Icon(
                                painterResource(if (adsBlocked) R.drawable.ic_shield else R.drawable.ic_shield_off),
                                contentDescription = if (adsBlocked) "Ad blocking is on for this site. Tap to switch off." else "Ad blocking is off for this site. Tap to switch on.",
                                tint = if (adsBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                trailingIcon = if (home) null else {
                    { IconButton(onClick = { browser.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") } }
                },
            )
            menu()
        }
        if (!home && progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // key: switching between the normal and the private browser must swap in the other web view.
            key(browser) { AndroidView(
                modifier = Modifier.fillMaxSize(),
                // Re-attach the one long-lived web view rather than building a new one each time.
                factory = { browser.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                // Keep the fixed layout size the web view needs (see Browser): never let it fall back to wrap-content.
                update = {
                    val lp = it.layoutParams
                    if (lp == null || lp.height != ViewGroup.LayoutParams.MATCH_PARENT || lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                        it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
            ) }
            if (home) {
                HomeScreen(private = browser.isPrivate) { browser.load(it) }
            } else {
                // Players seen on the page itself take priority; otherwise the streams it was seen loading.
                GrabButton(found = if (players > 0) players else streams) {
                    focus.clearFocus()
                    browser.snapshot(onDownload)
                }
            }
        }
    }
}
