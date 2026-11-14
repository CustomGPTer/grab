package uk.jj.grab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** A saved or visited page. */
data class Place(val title: String, val url: String, val at: Long = 0)

/**
 * Bookmarks (shown as the shortcut tiles on the home page) and browsing history.
 * Kept in the app's private storage; private mode never writes here.
 */
object Places {
    private const val MAX_HISTORY = 300

    val bookmarks = MutableStateFlow<List<Place>>(emptyList())
    val history = MutableStateFlow<List<Place>>(emptyList())

    private var file: File? = null
    private val disk = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "places.json")
        file = f
        if (!f.exists()) {
            // First run: a few starting tiles. All of them can be removed.
            bookmarks.value = listOf(
                Place("Google", "https://www.google.com"),
                Place("YouTube", "https://m.youtube.com"),
                Place("BBC News", "https://www.bbc.co.uk/news"),
            )
            save()
            return
        }
        try {
            val o = JSONObject(f.readText())
            bookmarks.value = read(o.optJSONArray("bookmarks"))
            history.value = read(o.optJSONArray("history"))
        } catch (e: Exception) {
            Log.w(App.TAG, "places load failed", e)
        }
    }

    private fun read(a: JSONArray?): List<Place> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) null else Place(o.optString("title").ifBlank { hostOf(url) ?: url }, url, o.optLong("at", 0))
        }
    }

    private fun write(list: List<Place>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("title", it.title).put("url", it.url).put("at", it.at)) }
    }

    private fun save() {
        val f = file ?: return
        val b = bookmarks.value
        val h = history.value
        disk.execute {
            try {
                f.writeText(JSONObject().put("bookmarks", write(b)).put("history", write(h)).toString())
            } catch (e: Exception) {
                Log.w(App.TAG, "places save failed", e)
            }
        }
    }

    fun isBookmarked(url: String?): Boolean = url != null && bookmarks.value.any { it.url == url }

    fun addBookmark(title: String, url: String) {
        if (url.isBlank() || isBookmarked(url)) return
        bookmarks.value = bookmarks.value + Place(title.ifBlank { hostOf(url) ?: url }, url)
        save()
    }

    fun removeBookmark(url: String) {
        bookmarks.value = bookmarks.value.filterNot { it.url == url }
        save()
    }

    /** Records a visited page, newest first, one entry per address. */
    fun visit(title: String, url: String) {
        if (!url.startsWith("http")) return
        val entry = Place(title.ifBlank { hostOf(url) ?: url }, url, System.currentTimeMillis())
        history.value = (listOf(entry) + history.value.filterNot { it.url == url }).take(MAX_HISTORY)
        save()
    }

    fun clearHistory() {
        history.value = emptyList()
        save()
    }
}
