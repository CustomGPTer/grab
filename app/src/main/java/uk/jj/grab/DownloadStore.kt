package uk.jj.grab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

data class Task(
    val id: String,
    val url: String,
    val title: String,
    val res: Int = 0,                    // 0 = best available
    val audioFormat: String? = null,     // set = audio only
    val cookies: Boolean = false,
    val referer: String? = null,
    val userAgent: String? = null,
    val fixedName: String? = null,
    val pageUrl: String? = null,
    val playlistItem: Int = 0,           // above 0 = download item N of the page at [url]
    val privateFile: Boolean = false,    // keep the finished file inside Grab, hidden from Gallery and other apps
    val privateSession: Boolean = false, // came from the private-mode browser (which cookies a login retry may use)
    val section: String? = null,         // "start-end" in seconds: download only that part
    val startAt: Long = 0,               // epoch millis; do not start before this (0 = straight away)
    val created: Long = System.currentTimeMillis(),
    val status: Status = Status.QUEUED,
    val progress: Float = -1f,           // below 0 = not known yet
    val eta: Long = -1,
    val phase: String = "",
    val error: String? = null,
    val log: String? = null,
    val outputUri: String? = null,
    val outputName: String? = null,
) {
    /** Waiting to run or running. */
    val active: Boolean get() = status == Status.QUEUED || status == Status.RUNNING

    /** Not finished one way or another: still shown in the working part of the list. */
    val open: Boolean get() = active || status == Status.PAUSED

    fun source() = Source(url, cookies, referer, userAgent, fixedName, privateSession)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("res", res)
        putOpt("audioFormat", audioFormat); put("cookies", cookies)
        putOpt("referer", referer); putOpt("userAgent", userAgent); putOpt("fixedName", fixedName)
        putOpt("pageUrl", pageUrl); put("playlistItem", playlistItem)
        put("privateFile", privateFile); put("privateSession", privateSession)
        putOpt("section", section); put("startAt", startAt)
        put("created", created); put("status", status.name); put("progress", progress.toDouble())
        putOpt("error", error); putOpt("log", log); putOpt("outputUri", outputUri); putOpt("outputName", outputName)
    }

    companion object {
        private fun JSONObject.strOrNull(k: String): String? = if (isNull(k)) null else optString(k)

        fun fromJson(o: JSONObject) = Task(
            id = o.getString("id"),
            url = o.getString("url"),
            title = o.optString("title", "Video"),
            res = o.optInt("res", 0),
            audioFormat = o.strOrNull("audioFormat"),
            cookies = o.optBoolean("cookies", false),
            referer = o.strOrNull("referer"),
            userAgent = o.strOrNull("userAgent"),
            fixedName = o.strOrNull("fixedName"),
            pageUrl = o.strOrNull("pageUrl"),
            playlistItem = o.optInt("playlistItem", 0),
            privateFile = o.optBoolean("privateFile", false),
            privateSession = o.optBoolean("privateSession", false),
            section = o.strOrNull("section"),
            startAt = o.optLong("startAt", 0),
            created = o.optLong("created", 0),
            status = try { Status.valueOf(o.optString("status")) } catch (_: Exception) { Status.FAILED },
            progress = o.optDouble("progress", -1.0).toFloat(),
            error = o.strOrNull("error"),
            log = o.strOrNull("log"),
            outputUri = o.strOrNull("outputUri"),
            outputName = o.strOrNull("outputName"),
        )
    }
}

/**
 * The download queue and history. Held in memory for the UI and written to the app's private storage
 * on every change of substance, so a closed or killed app picks up where it stopped. Nothing leaves the device.
 */
object DownloadStore {
    private const val KEEP_FINISHED = 200

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks = _tasks.asStateFlow()

    /** Address handed in through the share sheet. */
    val incomingUrl = MutableStateFlow<String?>(null)

    /** A link the user copied in another app, waiting to be offered. */
    val copiedUrl = MutableStateFlow<String?>(null)

    private var file: File? = null
    private var tmp: File? = null
    private val disk = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "queue.json")
        tmp = tmpRoot(ctx)
        // Anything that was mid-download when the app stopped goes back in the queue; its partial file is kept.
        _tasks.value = load().map {
            if (it.status == Status.RUNNING) it.copy(status = Status.QUEUED) else it
        }
        disk.execute { cleanTemp() }
    }

    private fun load(): List<Task> = try {
        val f = file
        if (f == null || !f.exists()) emptyList()
        else JSONArray(f.readText()).let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(Task::fromJson) } }
    } catch (e: Exception) {
        Log.w(App.TAG, "queue load failed", e)
        emptyList()
    }

    private fun save() {
        val snapshot = _tasks.value
        val f = file ?: return
        disk.execute {
            try {
                val part = File(f.parentFile, f.name + ".new")
                part.writeText(JSONArray().apply { snapshot.forEach { put(it.toJson()) } }.toString())
                if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
            } catch (e: Exception) {
                Log.w(App.TAG, "queue save failed", e)
            }
        }
    }

    /** Removes temp folders that no longer belong to a waiting, running, paused or resumable (failed) task. */
    private fun cleanTemp() {
        val keep = _tasks.value.filter { it.open || it.status == Status.FAILED }.map { it.id }.toSet()
        tmp?.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
    }

    private fun dropTemp(ids: List<String>) {
        val root = tmp ?: return
        disk.execute { ids.forEach { File(root, it).deleteRecursively() } }
    }

    fun get(id: String): Task? = _tasks.value.firstOrNull { it.id == id }

    fun add(new: List<Task>) {
        _tasks.update { old ->
            val all = old + new
            val finished = all.filter { !it.open }
            if (finished.size <= KEEP_FINISHED) all
            else {
                val drop = finished.sortedBy { it.created }.take(finished.size - KEEP_FINISHED).map { it.id }.toSet()
                dropTemp(drop.toList())
                all.filterNot { it.id in drop }
            }
        }
        save()
    }

    /** @param persist write to disk too. Progress ticks pass false; status changes pass true. */
    fun update(id: String, persist: Boolean = false, f: Task.() -> Task) {
        _tasks.update { l -> l.map { if (it.id == id) it.f() else it } }
        if (persist) save()
    }

    /** Atomically takes the next waiting task whose start time has come and marks it running. */
    @Synchronized
    fun claimNext(): Task? {
        val now = System.currentTimeMillis()
        val next = _tasks.value.firstOrNull { it.status == Status.QUEUED && it.startAt <= now } ?: return null
        update(next.id, persist = true) { copy(status = Status.RUNNING, eta = -1, phase = "Starting", error = null, log = null) }
        return get(next.id)
    }

    /** The earliest future start time among waiting tasks, or null if nothing is scheduled. */
    fun nextScheduled(): Long? {
        val now = System.currentTimeMillis()
        return _tasks.value.filter { it.status == Status.QUEUED && it.startAt > now }.minOfOrNull { it.startAt }
    }

    /** Back into the queue to run now: used for Retry, Resume and "Start now". */
    fun requeue(id: String) =
        update(id, persist = true) { copy(status = Status.QUEUED, startAt = 0, eta = -1, phase = "", error = null, log = null) }

    fun remove(id: String) {
        _tasks.update { l -> l.filterNot { it.id == id } }
        dropTemp(listOf(id))
        save()
    }

    fun clearFinished() {
        val gone = _tasks.value.filter { !it.open }.map { it.id }
        _tasks.update { l -> l.filter { it.open } }
        dropTemp(gone)
        save()
    }
}
