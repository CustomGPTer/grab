package uk.jj.grab

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/** What the download sheet is asked to fetch. */
data class Target(
    val url: String,
    val title: String? = null,
    val streams: List<Stream> = emptyList(),
    val fromBrowser: Boolean = false,
    val userAgent: String? = null,
    /** Skip the page lookup and go straight to this stream (a direct file link tapped in the browser). */
    val startWith: Stream? = null,
    /** Asked for from the private-mode browser, so any login retry uses that browser's own cookies. */
    val privateSession: Boolean = false,
)

/**
 * The in-app browser. One web view lives as long as the activity, so switching tabs keeps the page,
 * its history and its scroll position. Handles ad / pop-up blocking, fullscreen video and video detection.
 *
 * @param startUrl page to open, or null to start on Grab's own home page.
 * @param profile  null for the normal browser; a name gives a separate, disposable store of cookies and
 *                 site data, which is how private mode is kept apart from your normal logins.
 */
class Browser(private val activity: ComponentActivity, startUrl: String?, private val profile: String? = null) {

    val isPrivate: Boolean get() = profile != null

    /** True while Grab's own home page is showing instead of a web page. */
    val home = MutableStateFlow(startUrl == null)
    val url = MutableStateFlow(startUrl ?: "")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(100)
    val fullscreen = MutableStateFlow(false)
    /** Is blocking on for the site being viewed? */
    val adsBlocked = MutableStateFlow(true)
    /** Addresses of pop-ups and redirects that were stopped, for the "Open anyway" prompt. */
    val blocked = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Video players found on the page itself (covers sites such as YouTube that hide their stream). */
    val videos = MutableStateFlow(0)
    val sniffer = Sniffer()

    /** The device's real browser identity, minus the markers that make sites treat an app as second-class. */
    val userAgent: String = WebSettings.getDefaultUserAgent(activity)
        .replace("; wv", "")
        .replace(Regex("""Version/\d+(\.\d+)* """), "")

    /** A direct media link was tapped in the page. */
    var onDirectDownload: ((Target) -> Unit)? = null

    @Volatile private var pageHost: String? = hostOf(startUrl)
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var clearHistoryWhenLoaded = false
    private var destroyed = false
    private var lastVisited = ""
    private val consoleLog = ArrayDeque<String>()          // the page's most recent errors and warnings
    private val blockedLog = LinkedHashSet<String>()       // hosts the blocker has stopped on this page

    // RestrictedApi: the "do not send X-Requested-With" switch is still marked experimental in the web view library.
    @SuppressLint("SetJavaScriptEnabled", "RestrictedApi")
    val webView: WebView = WebView(activity).apply {
        // Must come before anything else is done with the web view.
        if (profile != null) {
            ProfileStore.getInstance().getOrCreateProfile(profile)
            WebViewCompat.setProfile(this, profile)
        }
        // Essential. A web view with no fixed layout size goes into "wrap content" mode, in which the CSS
        // unit vh (percentage of screen height) measures as zero. Pages that size things in vh then collapse:
        // YouTube's consent box (max-height: 80vh) shrank to its padding and left the page dimmed and dead.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.setSupportMultipleWindows(true)                 // so pop-ups reach onCreateWindow instead of hijacking the page
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.userAgentString = userAgent
        // A web view normally tells every site which app it is inside (an X-Requested-With header carrying
        // the package name). Send it to no one: it is a privacy leak and marks the browser as "embedded".
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        } catch (_: Throwable) { /* older web view: the header cannot be switched off */ }

        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { link, _, _, _, _ ->
            val page = this@Browser.url.value
            val stream = Stream(link, "File", page, System.currentTimeMillis())
            onDirectDownload?.invoke(Target(page, this@Browser.title.value, listOf(stream), true, userAgent, stream, isPrivate))
        }
        if (startUrl != null) loadUrl(startUrl)
    }

    /** This browser's cookie store (private mode has its own). */
    val cookieJar: CookieManager? = try {
        if (profile != null) ProfileStore.getInstance().getProfile(profile)?.cookieManager else CookieManager.getInstance()
    } catch (_: Throwable) { null }

    // ---- navigation ----

    fun load(input: String) {
        home.value = false
        webView.onResume()
        webView.loadUrl(normalise(input))
    }

    /** Back through the page history; from the first page, back to Grab's home page. */
    fun back() {
        if (webView.canGoBack()) webView.goBack() else goHome()
    }

    fun forward() {
        if (home.value) { if (url.value.isNotBlank()) { home.value = false; webView.onResume() } }
        else if (webView.canGoForward()) webView.goForward()
    }

    fun reload() = webView.reload()

    /** Shows Grab's home page. The web page stays loaded underneath, with anything playing stopped. */
    fun goHome() {
        if (home.value) return
        pause()
        home.value = true
    }

    private fun setPage(u: String?) {
        if (u.isNullOrBlank() || u == "about:blank") return
        url.value = u
        pageHost = hostOf(u)
        adsBlocked.value = AdBlock.enabled(pageHost)
        sniffer.pageChanged(u)
        if (!isPrivate) Prefs.lastUrl = u
    }

    /** Switch blocking on or off for the current site and reload so it takes effect. */
    fun toggleAdBlock() {
        adsBlocked.value = AdBlock.toggle(pageHost)
        webView.reload()
    }

    /**
     * Blocking that a host list cannot do. YouTube serves its adverts from the same servers as its videos,
     * so they are skipped inside the player instead. Only runs while blocking is on for the site.
     * The script installs itself once per page; calling this repeatedly is harmless.
     */
    private fun siteScripts() {
        if (destroyed || !Prefs.skipYouTubeAds.value || !AdBlock.enabled(pageHost)) return
        val site = baseDomain(pageHost)
        if (site == "youtube.com" || site == "youtube-nocookie.com") webView.evaluateJavascript(YOUTUBE_ADS, null)
    }

    // ---- lifecycle ----

    /** Leaving the browser tab or the app: stop anything that is playing. */
    fun pause() {
        if (destroyed) return
        webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null)
        webView.onPause()
    }

    fun resume() { if (!destroyed && !home.value) webView.onResume() }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        exitFullscreen()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    /** Wipes cookies, logins, site storage, cache and history, then returns to the home page. */
    fun clearData() {
        cookieJar?.removeAllCookies(null)
        cookieJar?.flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearFormData()
        sniffer.clear()
        videos.value = 0
        clearHistoryWhenLoaded = true
        webView.loadUrl("about:blank")
        url.value = ""
        home.value = true
    }

    // ---- video detection and download hand-off ----

    /** Counts the video players on the page that have something loaded. Called every second or so while browsing. */
    fun probeVideos() {
        if (destroyed || home.value) return
        webView.evaluateJavascript(COUNT_VIDEOS) { r -> videos.value = r?.trim('"')?.toIntOrNull() ?: 0 }
    }

    /**
     * Builds the download request for the page on screen: its true current address (single-page sites
     * change it without reloading), its title, and every stream it has been seen playing.
     */
    fun snapshot(done: (Target) -> Unit) {
        val page = webView.url ?: url.value
        webView.evaluateJavascript(PROBE) { result ->
            var pageTitle: String? = title.value.ifBlank { null }
            try {
                val o = JSONObject(JSONTokener(result).nextValue() as String)
                o.optString("t").takeIf { it.isNotBlank() }?.let { pageTitle = it }
                val srcs = o.optJSONArray("s")
                if (srcs != null) for (i in 0 until srcs.length()) sniffer.offer(Uri.parse(srcs.getString(i)), page, force = true)
            } catch (_: Exception) { /* page blocked the probe; the request log is still used */ }
            done(Target(page, pageTitle, sniffer.list(), true, userAgent, null, isPrivate))
        }
    }

    // ---- stuck pages ----

    /**
     * Removes whatever is covering the page (a full-screen layer or a pop-up box) and lets it scroll again.
     * For pages whose own pop-up fails to draw and leaves the page dimmed and dead. Reloading undoes it.
     */
    fun removeOverlay(done: (Int) -> Unit) {
        webView.evaluateJavascript(UNSTICK) { r -> done(r?.trim('"')?.toIntOrNull() ?: 0) }
    }

    /** A plain-text description of the page as the web view sees it, for working out why a page misbehaves. */
    fun report(done: (String) -> Unit) {
        webView.evaluateJavascript(REPORT) { result ->
            val page = try { JSONObject(JSONTokener(result).nextValue() as String) } catch (_: Exception) { null }
            val engine = try { WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } } catch (_: Exception) { null }
            val text = buildString {
                append("Grab ${BuildConfig.VERSION_NAME} page report\n")
                append("address: ${webView.url}\n")
                append("web view: $engine\n")
                append("view size: ${webView.width}x${webView.height}px, density ${activity.resources.displayMetrics.density}\n")
                append("identity: $userAgent\n")
                append("blocking on: ${adsBlocked.value} | streams seen: ${sniffer.count.value} | players seen: ${videos.value} | fullscreen: ${fullscreen.value} | private: $isPrivate\n")
                if (page == null) append("page script failed: ${result?.take(200)}\n")
                else {
                    for (key in listOf("viewport", "units", "state", "html", "body", "total")) append("$key: ${page.opt(key)}\n")
                    for (key in listOf("layers", "centre", "frames")) {
                        val arr = page.optJSONArray(key) ?: continue
                        append("\n[$key]\n")
                        for (i in 0 until arr.length()) append("${arr.optString(i)}\n")
                    }
                }
                append("\n[page errors]\n")
                synchronized(consoleLog) { consoleLog.forEach { append("$it\n") } }
                append("\n[requests blocked]\n")
                synchronized(blockedLog) { append(blockedLog.joinToString(", ")) }
                append("\n")
            }
            done(text)
        }
    }

    private fun noteBlocked(host: String?) {
        if (host == null) return
        synchronized(blockedLog) { if (blockedLog.size < 40) blockedLog.add(host) }
    }

    // ---- fullscreen video ----

    fun exitFullscreen() {
        val v = customView ?: return
        customView = null
        (v.parent as? ViewGroup)?.removeView(v)
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fullscreen.value = false
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customCallback = callback
        view.setBackgroundColor(Color.BLACK)
        (activity.window.decorView as ViewGroup).addView(
            view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        fullscreen.value = true
        // Turn the screen to suit the video: sideways for wide video, upright for tall.
        webView.evaluateJavascript(ASPECT) { r ->
            if (customView != null) activity.requestedOrientation =
                if (r == "false") ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // ---- pop-ups ----

    private fun handlePopup(target: String?, userGesture: Boolean) {
        if (target == null || !target.startsWith("http")) return
        val blockingOn = AdBlock.enabled(pageHost)
        when {
            // A link the user tapped that stays on the same site: just follow it in this view.
            userGesture && (!blockingOn || sameSite(hostOf(target), pageHost)) -> webView.loadUrl(target)
            // Blocking switched off here, but the page opened a window by itself: ignore it quietly.
            !blockingOn -> Unit
            // Anything else is treated as a pop-up; the user can still choose to open it.
            else -> blocked.tryEmit(target)
        }
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
            // The page report describes the page on screen, so its logs start afresh with each page load.
            synchronized(consoleLog) { consoleLog.clear() }
            synchronized(blockedLog) { blockedLog.clear() }
            videos.value = 0
            setPage(u)
        }

        // Fires for every address change, including ones a single-page site makes without reloading.
        override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
            setPage(u)
            siteScripts()
        }

        override fun onPageFinished(view: WebView, u: String?) {
            setPage(u)
            siteScripts()
            view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { title.value = it }
            if (clearHistoryWhenLoaded) { clearHistoryWhenLoaded = false; view.clearHistory() }
            // Private mode leaves no history.
            if (!isPrivate && u != null && u.startsWith("http") && u != lastVisited) {
                lastVisited = u
                Places.visit(title.value, u)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme
            // Never hand off to other apps (intent://, market:// and the like).
            if (scheme != "http" && scheme != "https") return true
            // Stop the page being dragged off to a known ad / tracker host.
            if (request.isForMainFrame && AdBlock.shouldBlock(request.url.host, pageHost)) {
                blocked.tryEmit(request.url.toString())
                return true
            }
            return false
        }

        // Runs on a background thread for every request the page makes.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            if (!request.isForMainFrame && AdBlock.shouldBlock(u.host, pageHost)) {
                noteBlocked(u.host)
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            val referer = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value
            sniffer.offer(u, referer ?: url.value)
            return null
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.value = newProgress
            if (newProgress >= 40) siteScripts()        // get in before a pre-roll advert starts
        }

        override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank() && !t.startsWith("http")) title.value = t }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            val level = m.messageLevel()
            if (level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING) synchronized(consoleLog) {
                consoleLog.addLast("$level: ${m.message().take(220)} (${m.sourceId().takeLast(60)}:${m.lineNumber()})")
                while (consoleLog.size > 40) consoleLog.removeFirst()
            }
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)

        override fun onHideCustomView() = exitFullscreen()

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            // A throwaway view whose only job is to reveal where the new window wanted to go.
            val probe = WebView(view.context)
            probe.webViewClient = object : WebViewClient() {
                private var done = false
                private fun take(u: String?) {
                    if (done) return
                    done = true
                    handlePopup(u, isUserGesture)
                    probe.post { probe.stopLoading(); probe.destroy() }
                }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { take(r.url.toString()); return true }
                override fun onPageStarted(v: WebView, u: String?, favicon: Bitmap?) = take(u)
            }
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }
    }

    companion object {
        const val GOOGLE = "https://www.google.com"
        const val PRIVATE_PROFILE = "private"

        /** Can this phone's web view keep a separate, disposable set of cookies for private mode? */
        fun privateModeSupported(): Boolean = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        } catch (_: Throwable) { false }

        /** Deletes everything private mode stored. Only possible once its web view has been destroyed. */
        fun wipePrivateData() {
            try {
                if (privateModeSupported()) ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
            } catch (_: Throwable) { /* still in use or never created */ }
        }

        private const val PROBE = "(function(){var o=[];document.querySelectorAll('video,audio,video source,audio source')" +
            ".forEach(function(e){var s=e.currentSrc||e.src;if(s&&s.indexOf('http')===0)o.push(s)});" +
            "return JSON.stringify({t:document.title,s:o})})()"

        // A player counts once it has media loaded and is big enough to be the page's content, not a decoration.
        private const val COUNT_VIDEOS = "(function(){var n=0,l=document.querySelectorAll('video');for(var i=0;i<l.length;i++){" +
            "var v=l[i],r=v.getBoundingClientRect();if((v.currentSrc||v.src)&&v.readyState>0&&r.width>=120&&r.height>=60)n++}return n})()"

        private const val ASPECT = "(function(){var l=document.querySelectorAll('video');for(var i=0;i<l.length;i++){" +
            "if(!l[i].paused&&l[i].videoWidth)return l[i].videoWidth>=l[i].videoHeight}" +
            "return l.length&&l[0].videoWidth?l[0].videoWidth>=l[0].videoHeight:true})()"

        // YouTube advert skipper. While the player is in its advert state: mute it, jump the advert to its end,
        // and press any skip / close button. Sound is restored afterwards only if this script muted it.
        // Promoted (paid) tiles in the listings are hidden. Class names are from YouTube's mobile player.
        private val YOUTUBE_ADS = """
(function(){
if(window.__grabAds)return;window.__grabAds=1;
var st=document.createElement('style');
st.textContent='ytm-promoted-video-renderer,ytm-promoted-sparkles-web-renderer,ytm-statement-banner-renderer,ytm-companion-slot,ad-slot-renderer,ytm-ad-slot-renderer{display:none!important}';
(document.head||document.documentElement).appendChild(st);
var muted=false;
setInterval(function(){
var p=document.querySelector('.html5-video-player');if(!p)return;
var v=p.querySelector('video');
if(p.classList.contains('ad-showing')){
if(v){if(!v.muted){v.muted=true;muted=true}
if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.3){try{v.currentTime=v.duration}catch(e){}}}
var b=p.querySelectorAll('.ytp-skip-ad-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button,.ytp-ad-skip-button-container button,.ytp-ad-overlay-close-button');
for(var i=0;i<b.length;i++){try{b[i].click()}catch(e){}}
}else if(muted&&v){v.muted=false;muted=false}
},250);
})()
""".trimIndent()

        // Hides full-screen fixed layers and pop-up boxes (never anything holding the video or most of the page)
        // and undoes the scroll lock they put on the page. Returns how many things were hidden.
        private val UNSTICK = """
(function(){var vw=innerWidth,vh=innerHeight,n=0,total=document.querySelectorAll('body *').length;
function hide(e){e.style.setProperty('display','none','important');n++}
document.querySelectorAll('dialog[open]').forEach(function(e){try{e.close()}catch(x){}hide(e)});
var all=document.querySelectorAll('body *');
for(var i=0;i<all.length;i++){var e=all[i],s=getComputedStyle(e);
if(s.display==='none'||s.visibility==='hidden')continue;
if(parseFloat(s.opacity)===0&&s.pointerEvents==='none')continue;
var role=e.getAttribute('role');
var modal=e.getAttribute('aria-modal')==='true'||role==='dialog'||role==='alertdialog';
if(s.position!=='fixed'&&!modal)continue;
var r=e.getBoundingClientRect();
if(r.width<1||r.height<1||r.right<=0||r.bottom<=0||r.left>=vw||r.top>=vh)continue;
if(!modal&&!(r.width>=vw*0.9&&r.height>=vh*0.6))continue;
if(e.querySelector('video'))continue;
var scrim=/rgba\([^)]*,\s*0?\.\d+\)/.test(s.backgroundColor);
if(!scrim&&!modal&&e.querySelectorAll('*').length>total*0.4)continue;
hide(e)}
[document.documentElement,document.body].forEach(function(e){if(!e)return;
e.style.setProperty('overflow','auto','important');e.style.setProperty('touch-action','auto','important');
if(getComputedStyle(e).position==='fixed')e.style.setProperty('position','static','important');
e.removeAttribute('inert')});
document.querySelectorAll('[inert]').forEach(function(e){e.removeAttribute('inert')});
return n})()
""".trimIndent()

        // Describes the page: viewport, scroll lock, every fixed layer and pop-up box, what sits at the centre
        // of the screen (and everything it is inside), and any embedded frames.
        private val REPORT = """
(function(){
function cs(e){var s=getComputedStyle(e);return 'overflow='+s.overflow+' position='+s.position+' height='+Math.round(e.getBoundingClientRect().height)+' class='+String(e.className).slice(0,80)}
function d(e){var s=getComputedStyle(e),r=e.getBoundingClientRect();
var c=(typeof e.className==='string'&&e.className)?'.'+e.className.trim().replace(/\s+/g,'.').slice(0,70):'';
return e.tagName.toLowerCase()+(e.id?'#'+e.id:'')+c+' at '+Math.round(r.left)+','+Math.round(r.top)+' size '+Math.round(r.width)+'x'+Math.round(r.height)
+' pos='+s.position+' z='+s.zIndex+' opacity='+s.opacity+' bg='+s.backgroundColor+' overflow='+s.overflow
+(s.transform!=='none'?' transform='+s.transform.slice(0,50):'')+(s.pointerEvents!=='auto'?' pointer='+s.pointerEvents:'')
+' | '+(e.innerText||'').replace(/\s+/g,' ').slice(0,90)}
var vw=innerWidth,vh=innerHeight,o={layers:[],centre:[],frames:[]};
o.viewport=vw+'x'+vh+' dpr='+devicePixelRatio+' visual='+(window.visualViewport?Math.round(visualViewport.width)+'x'+Math.round(visualViewport.height)+' scale='+visualViewport.scale:'n/a')+' screen='+screen.width+'x'+screen.height;
o.state='visibility='+document.visibilityState+' scrollY='+Math.round(scrollY)+' docHeight='+document.documentElement.scrollHeight+' dark='+matchMedia('(prefers-color-scheme: dark)').matches+' reducedMotion='+matchMedia('(prefers-reduced-motion: reduce)').matches;
o.html=cs(document.documentElement);o.body=cs(document.body);
var t=document.createElement('div');t.style.cssText='position:absolute;visibility:hidden;left:0;top:0;height:100vh;width:100vw';
document.body.appendChild(t);o.units='100vh='+t.offsetHeight+' 100vw='+t.offsetWidth;document.body.removeChild(t);
var all=document.querySelectorAll('body *');o.total=all.length;
for(var i=0;i<all.length&&o.layers.length<45;i++){var e=all[i],s=getComputedStyle(e);
if(s.display==='none'||s.visibility==='hidden')continue;
var role=e.getAttribute('role');
var modal=e.tagName==='DIALOG'||role==='dialog'||role==='alertdialog'||e.getAttribute('aria-modal')==='true';
if(s.position!=='fixed'&&!modal)continue;
var r=e.getBoundingClientRect();if(!modal&&r.width<vw*0.5)continue;
o.layers.push(d(e))}
var p=document.elementFromPoint(vw/2,vh/2);while(p&&o.centre.length<14){o.centre.push(d(p));p=p.parentElement}
document.querySelectorAll('iframe').forEach(function(f){if(o.frames.length<12){var r=f.getBoundingClientRect();
o.frames.push(String(f.src).slice(0,120)+' size '+Math.round(r.width)+'x'+Math.round(r.height)+' at '+Math.round(r.left)+','+Math.round(r.top))}});
return JSON.stringify(o)})()
""".trimIndent()

        /** Turns whatever was typed into an address: a full URL, a bare host, or a web search. */
        fun normalise(input: String): String {
            val t = input.trim()
            if (t.isEmpty()) return GOOGLE
            if (t.startsWith("http://") || t.startsWith("https://")) return t
            val looksLikeHost = !t.contains(' ') && t.contains('.')
            return if (looksLikeHost) "https://$t" else "https://www.google.com/search?q=${Uri.encode(t)}"
        }
    }
}
