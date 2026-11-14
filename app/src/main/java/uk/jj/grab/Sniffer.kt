package uk.jj.grab

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/** A media stream or file the current page was seen requesting. */
data class Stream(val url: String, val kind: String, val referer: String?, val at: Long) {
    val manifest: Boolean get() = kind == "HLS" || kind == "DASH"
    val label: String get() = "$kind · ${hostOf(url) ?: ""}"
}

/**
 * Notes the media a page actually loads (HLS / DASH manifests and direct media files) so it can be
 * offered when the download engine does not recognise the page itself. Encrypted (DRM) streams are
 * still listed here but cannot be saved.
 */
class Sniffer {
    private val found = LinkedHashMap<String, Stream>()
    private var page = ""

    /** Roughly how many separate videos the caught streams amount to (for the Download button's badge). */
    val count = MutableStateFlow(0)

    /** Call whenever the browser moves to a different page; older detections are dropped. */
    fun pageChanged(url: String?) {
        val key = url?.substringBefore('#') ?: return
        synchronized(found) {
            if (key == page) return
            page = key
            // A single-page site may request the next video's stream just before it changes the address.
            val cutoff = System.currentTimeMillis() - 1500
            found.values.removeAll { it.at < cutoff }
            count.value = estimate()
        }
    }

    fun clear() = synchronized(found) {
        found.clear()
        page = ""
        count.value = 0
    }

    /** @param force accept the address even if it does not look like media (used for <video> sources). */
    fun offer(url: Uri, referer: String?, force: Boolean = false) {
        val kind = classify(url) ?: if (force) "Video file" else return
        val s = url.toString()
        synchronized(found) {
            if (found.size >= 40 || found.containsKey(s)) return
            found[s] = Stream(s, kind, referer, System.currentTimeMillis())
            count.value = estimate()
        }
    }

    /** Manifests first (they describe the whole video), then direct files, in the order seen. */
    fun list(): List<Stream> =
        synchronized(found) { found.values.toList() }.sortedBy { if (it.manifest) 0 else 1 }.take(12)

    // One video usually loads a master manifest plus one per quality, all from one server, so manifests
    // are counted per server. Direct files are counted individually.
    private fun estimate(): Int {
        val manifests = found.values.filter { it.manifest }
        return if (manifests.isNotEmpty()) manifests.map { hostOf(it.url) }.distinct().size else found.size
    }

    companion object {
        private val MEDIA = Regex("""\.(m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|mp3|m4a|aac|ogg|opus|flac|wav)$""")
        private val SEGMENT = Regex("""(^|[-_.])(seg|segment|chunk|frag|fragment|init|part)[-_]?\d*([-_.]|$)""")
        private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")

        fun classify(u: Uri): String? = classify(u.scheme, u.path)

        /** What kind of media an address points at, or null if it is not a downloadable video or audio file. */
        fun classify(scheme: String?, rawPath: String?): String? {
            if (scheme != "http" && scheme != "https") return null
            val path = rawPath?.lowercase() ?: return null
            val ext = MEDIA.find(path)?.groupValues?.get(1) ?: return null
            if (ext == "m3u8") return "HLS"
            if (ext == "mpd") return "DASH"
            // Pieces of a segmented stream are not downloadable videos in their own right.
            if (SEGMENT.containsMatchIn(path.substringAfterLast('/'))) return null
            return if (ext in AUDIO) "Audio file" else "Video file"
        }
    }
}
