package uk.jj.grab

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.thread

/**
 * One option on the quality sheet. [res] 0 = best available; [audioFormat] set = audio only.
 * [size] is the exact file size in bytes when the site states it, otherwise 0 (never an estimate).
 */
data class Choice(val label: String, val detail: String, val res: Int, val audioFormat: String?, val size: Long = 0)

/**
 * One video inside a playlist / multi-video page.
 * [item] 0 = the video has an address of its own ([url]); above 0 = it only exists as that numbered
 * item of the parent page, so it is fetched as "parent address, item N".
 */
data class Entry(val url: String, val title: String, val duration: Int, val item: Int = 0)

sealed interface Lookup {
    /**
     * [item] as for [Entry]: many sites (BBC among them) present a single video as a one-item list.
     * [url] is set when the video lives at a different address from the one that was looked up.
     */
    data class Video(val title: String, val choices: List<Choice>, val item: Int = 0, val url: String? = null) : Lookup
    data class Playlist(val title: String, val entries: List<Entry>) : Lookup
}

/**
 * How an address should be fetched.
 * [cookies]   pass the in-app browser's cookies for the site (only after a plain attempt failed).
 * [referer]   set for a stream caught in the browser: the page it was playing on.
 * [fixedName] file name to use instead of the engine's own title (streams have no title of their own).
 */
data class Source(
    val url: String,
    val cookies: Boolean = false,
    val referer: String? = null,
    val userAgent: String? = null,
    val fixedName: String? = null,
    /** Came from the private-mode browser: a login retry uses that browser's own cookies. */
    val privateSession: Boolean = false,
)

/** A failure from the download engine: [message] is short and readable, [log] is the full output for copying. */
class EngineException(message: String, val log: String) : Exception(message)

object Yt {
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val NIGHTLY_API = "https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"

    /** True while the engine is being replaced; lookups and downloads wait for it. */
    val updating = MutableStateFlow(false)

    @Volatile private var ready = false
    private val startup = CountDownLatch(1)

    // The updater deletes and rewrites the engine file, so it must never overlap a running engine process:
    // every engine run holds the read lock, the updater takes the write lock.
    private val lock = ReentrantReadWriteLock(true)

    /** Called once from [App]: unpacks the engine off the main thread, then runs the daily update check. */
    fun start(ctx: Context, init: () -> Unit) {
        thread(name = "engine-start", isDaemon = true) {
            try {
                init()
                ready = true
                if (System.currentTimeMillis() - Prefs.lastEngineCheck >= DAY) update(ctx, wait = true)
            } catch (e: Exception) {
                Log.w(App.TAG, "engine start/update: ${e.message}")
            } finally {
                startup.countDown()
            }
        }
    }

    /**
     * Called each time the app comes to the front. Android can keep Grab alive for days, so the check
     * made at start-up is repeated here once it is a day old. Skipped if a download is using the engine.
     */
    fun checkDaily(ctx: Context) {
        if (!ready || startup.count > 0L || System.currentTimeMillis() - Prefs.lastEngineCheck < DAY) return
        thread(name = "engine-update", isDaemon = true) {
            try { update(ctx, wait = false) } catch (e: Exception) { Log.w(App.TAG, "engine update: ${e.message}") }
        }
    }

    /** Runs [block] with the engine guaranteed present and not mid-update. Blocking: call off the main thread. */
    fun <T> engine(block: () -> T): T {
        startup.await(90, TimeUnit.SECONDS)
        if (!ready) throw EngineException("The download engine did not start. Close Grab and open it again.", "engine not initialised")
        if (!lock.readLock().tryLock(120, TimeUnit.SECONDS))
            throw EngineException("The download engine is still updating. Try again in a minute.", "update lock timeout")
        try {
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    fun version(ctx: Context): String = YoutubeDL.getInstance().version(ctx) ?: "built-in (not yet updated)"

    /** Manual update from the menu. */
    suspend fun updateNow(ctx: Context): String = withContext(Dispatchers.IO) {
        try {
            update(ctx, wait = false)
        } catch (e: Exception) {
            "Update failed: ${e.message?.take(120) ?: "no connection"}"
        }
    }

    private fun update(ctx: Context, wait: Boolean): String {
        val w = lock.writeLock()
        if (wait) w.lock() else if (!w.tryLock()) return "Downloads are running. Update when they finish."
        updating.value = true
        try {
            val latest = latestTag()
            val now = System.currentTimeMillis()
            if (latest == YoutubeDL.getInstance().version(ctx)) {
                Prefs.lastEngineCheck = now
                return "Download engine is up to date ($latest)"
            }
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
            Prefs.lastEngineCheck = now
            return "Download engine updated to ${version(ctx)}"
        } finally {
            updating.value = false
            w.unlock()
        }
    }

    /** Newest yt-dlp nightly tag, fetched with short timeouts so a dead connection cannot stall the app. */
    private fun latestTag(): String {
        val c = URL(NIGHTLY_API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).getString("tag_name")
        } finally {
            c.disconnect()
        }
    }

    // ---- building engine commands ----

    private fun base(src: Source, cookieFile: File?) = YoutubeDLRequest(src.url).apply {
        addOption("--no-playlist")                 // a link to one video inside a playlist means that video
        addOption("--socket-timeout", "30")
        if (cookieFile != null) addOption("--cookies", cookieFile.absolutePath)
        // The browser's identity is only sent when its session (cookies) or its stream is being reused.
        if (src.userAgent != null && (cookieFile != null || src.referer != null)) addOption("--user-agent", src.userAgent)
        if (src.referer != null) {
            addOption("--add-headers", "Referer:${src.referer}")
            originOf(src.referer)?.let { addOption("--add-headers", "Origin:$it") }
        }
    }

    /** Resolution first, then frame rate; at equal quality prefer H.264 + AAC, which every player handles. */
    private fun sort(res: Int) = (if (res > 0) "res:$res" else "res") + ",fps,vcodec:h264,acodec:aac"

    /** The phone's language first, then English. */
    internal fun subtitleLanguages(language: String = java.util.Locale.getDefault().language): String =
        if (language.isBlank() || language == "en") "en.*,en" else "$language.*,$language,en.*,en"

    /**
     * Reads a time typed as seconds, m:ss or h:mm:ss. Returns seconds, or null if it is not a time.
     */
    fun parseClock(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 5 }) return null
        return parts.fold(0) { acc, p -> acc * 60 + p.toInt() }
    }

    fun downloadRequest(t: Task, tmp: File, cookieFile: File?): YoutubeDLRequest =
        base(t.source(), cookieFile).apply {
            addOption("--no-mtime")
            addOption("--windows-filenames")
            addOption("--concurrent-fragments", "4")
            if (t.playlistItem > 0) addOption("--playlist-items", t.playlistItem.toString())
            // Just a section: the cut lands on the nearest key frame, so it can be a few seconds out.
            if (t.section != null) addOption("--download-sections", "*${t.section}")
            val name = t.fixedName?.replace("%", "%%") ?: "%(title).150B"
            addOption("-o", "${tmp.absolutePath}/$name.%(ext)s")
            if (t.audioFormat != null) {
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", t.audioFormat)
                addOption("--audio-quality", "0")
                if (Prefs.coverArt.value) {
                    // The video's thumbnail becomes the album art; title and artist are filled in.
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                    addOption("--embed-metadata")
                }
            } else {
                addOption("-f", "bv*+ba/b")
                addOption("-S", sort(t.res))
                addOption("--merge-output-format", "mp4")
                addOption("--remux-video", "mp4")
                if (Prefs.subtitles.value) {
                    // Real subtitles only (not machine captions), saved inside the MP4 as a track the player can show.
                    addOption("--sub-langs", subtitleLanguages())
                    addOption("--embed-subs")
                }
            }
        }

    // ---- looking an address up ----

    /** What is at this address: one video (with its qualities) or several videos. Cancellable. */
    suspend fun lookup(ctx: Context, src: Source): Lookup = runInterruptible(Dispatchers.IO) {
        val cookieFile = if (src.cookies) Cookies.write(ctx, "look-${System.nanoTime()}", listOf(src.url, src.referer), src.privateSession) else null
        try {
            val first = query(ctx, src, cookieFile)
            // A list holding exactly one separately-addressed video: open that video so its real qualities can be shown.
            val only = (first as? Lookup.Playlist)?.entries?.singleOrNull()?.takeIf { it.item == 0 }
            if (only == null) first
            else try {
                when (val inner = query(ctx, src.copy(url = only.url), cookieFile)) {
                    is Lookup.Video -> inner.copy(url = only.url)
                    is Lookup.Playlist -> first
                }
            } catch (_: EngineException) {
                first
            }
        } finally {
            cookieFile?.delete()
        }
    }

    private fun query(ctx: Context, src: Source, cookieFile: File?): Lookup {
        val req = base(src, cookieFile).apply {
            addOption("-J")
            addOption("--flat-playlist")
            addOption("--playlist-end", "300")
        }
        val out = try {
            engine { YoutubeDL.getInstance().execute(req, null, false, null) }.out
        } catch (e: YoutubeDLException) {
            throw failure(ctx, e.message)
        }
        return parse(out)
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    internal fun parse(out: String): Lookup {
        val start = out.indexOf('{')
        if (start < 0) throw EngineException("The site returned nothing usable.", out.take(2000))
        val o = try { JSONObject(out.substring(start)) } catch (e: Exception) {
            throw EngineException("The site returned something unreadable.", out.take(2000))
        }
        if (o.str("_type") != "playlist") return Lookup.Video(o.str("title").ifBlank { o.str("id").ifBlank { "Video" } }, choices(o))

        val arr = o.optJSONArray("entries")
        val entries = ArrayList<Entry>()
        var soleFull: JSONObject? = null
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val title = e.str("title").ifBlank { "Video ${i + 1}" }
            val duration = e.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
            val type = e.str("_type")
            val link = e.str("url")
            if ((type == "url" || type == "url_transparent") && link.startsWith("http")) {
                entries += Entry(link, title, duration, 0)                       // has an address of its own
            } else {
                entries += Entry("", title, duration, e.optInt("playlist_index", i + 1))   // item N of this page
                soleFull = e
            }
        }
        if (entries.isEmpty()) throw EngineException("No videos were found at this address.", out.take(2000))
        val only = soleFull
        if (entries.size == 1 && only != null && only.optJSONArray("formats") != null)
            return Lookup.Video(entries[0].title, choices(only), entries[0].item)
        return Lookup.Playlist(o.str("title").ifBlank { "${entries.size} videos" }, entries)
    }

    /** One of the site's formats, reduced to what decides which one the engine will pick and how big it is. */
    private class Rep(val h264: Boolean, val aac: Boolean, val tbr: Double, val fps: Int, val size: Long, val muxed: Boolean, val ext: String)

    /**
     * One tile per resolution the site offers (highest first), then the two audio-only options.
     * If the site states no resolutions, a single "Best" tile. Video is always saved as MP4.
     *
     * Sizes are exact figures from the site only, never estimates. For each resolution the size is that of
     * the format the engine will pick (highest frame rate, then H.264, then bitrate, matching the download
     * command), plus the audio track when the video comes without sound. Unknown parts mean no size is shown.
     */
    private fun choices(o: JSONObject): List<Choice> {
        val byRes = TreeMap<Int, Rep>(reverseOrder())
        var audio: Rep? = null
        val formats = o.optJSONArray("formats")
        if (formats != null) for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val vcodec = f.str("vcodec")
            val acodec = f.str("acodec")
            val rep = Rep(
                h264 = vcodec.startsWith("avc") || vcodec.startsWith("h264"),
                aac = acodec.startsWith("mp4a") || acodec.startsWith("aac"),
                tbr = f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
                fps = f.optDouble("fps", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
                size = f.optLong("filesize", 0),                       // the site's stated size; filesize_approx is a guess
                muxed = acodec.isNotEmpty() && acodec != "none",
                ext = f.str("ext"),
            )
            if (vcodec == "none") {
                // Audio-only track: the engine prefers AAC (it goes into MP4 untouched), then the higher bitrate.
                val cur = audio
                if (acodec != "none" && (cur == null || (rep.aac && !cur.aac) || (rep.aac == cur.aac && rep.tbr > cur.tbr))) audio = rep
                continue
            }
            if (rep.ext == "mhtml") continue                           // preview storyboards, not video
            val res = listOf(f.optInt("height", 0), f.optInt("width", 0)).filter { it > 0 }.minOrNull() ?: continue
            val cur = byRes[res]
            val better = cur == null || rep.fps > cur.fps ||
                (rep.fps == cur.fps && ((rep.h264 && !cur.h264) || (rep.h264 == cur.h264 && rep.tbr > cur.tbr)))
            if (better) byRes[res] = rep
        }

        val out = ArrayList<Choice>()
        val sound = audio
        for ((res, r) in byRes) {
            val total = when {
                r.size <= 0 -> 0
                r.muxed -> r.size
                sound != null && sound.size > 0 -> r.size + sound.size
                else -> 0
            }
            out += Choice("${res}p", if (r.fps > 30) "${r.fps}fps" else "", res, null, total)
        }
        if (out.isEmpty()) out += Choice("Best", "", 0, null)
        // M4A is the audio track as the site has it, so its size is known when that track is already M4A. MP3 is re-encoded.
        out += Choice("M4A", "Audio", 0, "m4a", if (sound != null && sound.ext == "m4a") sound.size else 0)
        out += Choice("MP3", "Audio", 0, "mp3")
        return out
    }

    /** Qualities offered for a batch, where each video's exact resolutions are not known in advance. */
    val batchChoices: List<Choice> = listOf(
        Choice("Best", "", 0, null),
        Choice("2160p", "", 2160, null),
        Choice("1080p", "", 1080, null),
        Choice("720p", "", 720, null),
        Choice("480p", "", 480, null),
        Choice("M4A", "", 0, "m4a"),
        Choice("MP3", "", 0, "mp3"),
    )

    // ---- errors ----

    /** Turns the engine's raw output into a short readable reason plus the full text for "Copy error". */
    fun failure(ctx: Context, raw: String?): EngineException {
        val text = raw.orEmpty().trim()
        val line = text.lines().firstOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
            ?: text.lines().lastOrNull { it.isNotBlank() }?.trim()
            ?: "The download engine stopped without saying why."
        val low = line.lowercase()
        val short = when {
            "unsupported url" in low -> "No downloadable video was recognised at this address."
            "drm" in low -> "This video is DRM-protected. No downloader can save it."
            "sign in" in low || "log in" in low || "login" in low || "members" in low || "cookies" in low ->
                "This video needs a login. Log in to the site in the Browser tab, then try again."
            "geo" in low || "not available in your" in low || "your country" in low -> "The site says this video is not available in your region."
            "http error 403" in low -> "The site refused the request (403 Forbidden)."
            "http error 404" in low -> "The site says this page or video no longer exists (404)."
            "http error 429" in low -> "The site is rate-limiting you (429). Wait a while and try again."
            "unable to download webpage" in low || "name resolution" in low || "network is unreachable" in low || "timed out" in low ->
                "Could not reach the site. Check your connection."
            else -> line.take(300)
        }
        val log = "Grab ${BuildConfig.VERSION_NAME} · yt-dlp ${version(ctx)}\n" + text.take(6000)
        return EngineException(short, log)
    }
}
