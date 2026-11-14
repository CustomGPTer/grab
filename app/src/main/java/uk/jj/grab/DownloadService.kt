package uk.jj.grab

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that works through the saved queue, as many downloads at a time as the setting allows.
 * Each download runs in its own temp folder (kept on failure or pause so it can resume), and the finished
 * file goes either to Gallery (Movies/Grab, Music/Grab) or to Grab's private folder.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var running = 0
    @Volatile private var stopping = false
    private var wake: PowerManager.WakeLock? = null
    private val lastNotify = AtomicLong(0)
    private val lastTick = ConcurrentHashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Wi-Fi only: if the phone drops to mobile data mid-download, park what is running until Wi-Fi is back.
        scope.launch(Dispatchers.Main) {
            Net.wifi.collect { if (!Net.downloadsAllowed) park() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, progressNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Android can refuse (e.g. its daily background allowance is used up). Carry on while the app is open.
            Log.w(App.TAG, "could not enter foreground: ${e.message}")
        }
        pump()
        return START_NOT_STICKY
    }

    /** Starts waiting downloads up to the allowed number; stops the service when nothing is left to do. Main thread only. */
    private fun pump() {
        if (stopping) return
        synchronized(lock) {
            val max = Prefs.parallel.value.coerceIn(1, 3)
            while (running < max && Net.downloadsAllowed) {
                val task = DownloadStore.claimNext() ?: break
                running++
                scope.launch {
                    try {
                        run(task)
                    } catch (e: Exception) {
                        Log.e(App.TAG, "download crashed", e)
                        DownloadStore.update(task.id, persist = true) { copy(status = Status.FAILED, error = e.message ?: e.toString()) }
                    } finally {
                        lastTick.remove(task.id)
                        synchronized(lock) { running-- }
                        main.post { pump() }
                    }
                }
            }
            Scheduler.arm(this)          // anything set to start later gets (or keeps) its alarm
            if (running == 0) {
                releaseWake()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                acquireWake()
                post(NOTIF_ID, progressNotification())
            }
        }
    }

    /** Puts running downloads back in the queue with their partial files kept (used when Wi-Fi drops). */
    private fun park() {
        DownloadStore.tasks.value.filter { it.status == Status.RUNNING }.forEach {
            DownloadStore.update(it.id, persist = true) { copy(status = Status.QUEUED, eta = -1, phase = "") }
            try { YoutubeDL.getInstance().destroyProcessById(it.id) } catch (_: Exception) { }
        }
    }

    private fun run(task: Task) {
        val tmp = File(tmpRoot(this), task.id).apply { mkdirs() }
        var withCookies = task.cookies
        while (true) {
            val cookieFile = if (withCookies) Cookies.write(this, task.id, listOf(task.pageUrl, task.url, task.referer), task.privateSession) else null
            try {
                if (stateOf(task.id) != Status.RUNNING) { if (gone(task.id)) tmp.deleteRecursively(); return }
                val request = Yt.downloadRequest(task, tmp, cookieFile)
                Yt.engine {
                    YoutubeDL.getInstance().execute(request, task.id, false) { progress, eta, line -> tick(task.id, progress, eta, line) }
                }
                if (gone(task.id)) { tmp.deleteRecursively(); return }

                val file = tmp.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() !in LEFTOVERS }
                    ?.maxByOrNull { it.length() }
                    ?: throw IOException("The engine finished but produced no file.")

                DownloadStore.update(task.id) { copy(phase = "Saving", progress = 100f) }
                val (uri, name) = if (task.privateFile) Library.keepPrivate(this, file) else Library.publish(this, file, task.audioFormat != null)
                tmp.deleteRecursively()
                DownloadStore.update(task.id, persist = true) {
                    copy(status = Status.DONE, progress = 100f, phase = "", outputUri = uri.toString(), outputName = name)
                }
                post(task.id.hashCode(), resultNotification("Downloaded", name, playIntent(uri, name)))
                return
            } catch (_: YoutubeDL.CanceledException) {
                // Stopped on purpose. Cancelled or removed: delete the partial file. Paused or parked: keep it to resume.
                if (gone(task.id)) tmp.deleteRecursively()
                return
            } catch (e: Exception) {
                if (gone(task.id)) { tmp.deleteRecursively(); return }
                if (stopping || stateOf(task.id) != Status.RUNNING) return
                // "Only if the first try fails": one more go with the browser's login for this site, if it has one.
                if (!withCookies && Cookies.has(task.privateSession, task.pageUrl, task.url)) {
                    withCookies = true
                    DownloadStore.update(task.id) { copy(phase = "Retrying with your login", progress = -1f) }
                    continue
                }
                val failure = when (e) {
                    is EngineException -> e
                    is YoutubeDLException -> Yt.failure(this, e.message)
                    else -> EngineException(e.message ?: e.toString(), e.stackTraceToString().take(4000))
                }
                DownloadStore.update(task.id, persist = true) {
                    copy(status = Status.FAILED, phase = "", error = failure.message, log = failure.log, cookies = withCookies)
                }
                post(task.id.hashCode(), resultNotification("Download failed", task.title, openAppIntent()))
                return
            } finally {
                cookieFile?.delete()
            }
        }
    }

    private fun stateOf(id: String): Status? = DownloadStore.get(id)?.status

    /** Cancelled or removed from the list: nothing of it should be kept. */
    private fun gone(id: String): Boolean = stateOf(id).let { it == null || it == Status.CANCELLED }

    /** Engine output line -> queue state. Throttled: the engine can print many lines a second. */
    private fun tick(id: String, progress: Float, eta: Long, line: String) {
        val phase = when {
            line.startsWith("[Merger]") || line.startsWith("[VideoRemuxer]") || line.startsWith("[Fixup") ||
                line.startsWith("[EmbedSubtitle]") || line.startsWith("[Metadata]") || line.startsWith("[EmbedThumbnail]") -> "Finishing"
            line.startsWith("[ExtractAudio]") -> "Converting audio"
            line.startsWith("[download]") -> "Downloading"
            else -> null
        }
        val now = System.currentTimeMillis()
        val last = lastTick[id] ?: 0L
        if ((phase == null || phase == "Downloading") && now - last < 400) return
        lastTick[id] = now
        // The library only reports a percentage once the engine prints a time estimate; read it directly as well.
        val percent = PERCENT.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: progress
        DownloadStore.update(id) {
            if (status != Status.RUNNING) this
            else copy(
                progress = if (phase == "Finishing" || phase == "Converting audio") 100f else if (percent >= 0f) percent else this.progress,
                eta = eta,
                phase = phase ?: this.phase,
            )
        }
        if (now - lastNotify.get() >= 1000) {
            lastNotify.set(now)
            post(NOTIF_ID, progressNotification())
        }
    }

    /** Android 15+ caps this kind of background work at 6 hours a day. Park what is running; it resumes on next open. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        park()
        post(NOTIF_ID + 1, resultNotification("Downloads paused", "Android paused your downloads (daily background limit). Open Grab to carry on.", openAppIntent()))
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }

    // ---- keeping the phone awake ----

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grab:download").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(App.TAG, "wake lock: ${e.message}")
        }
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) { }
        wake = null
    }

    // ---- notifications ----

    private fun post(id: Int, n: Notification) {
        try { NotificationManagerCompat.from(this).notify(id, n) } catch (_: SecurityException) { /* permission declined */ }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Tapping a "Downloaded" notification plays the file in Grab's own player. */
    private fun playIntent(uri: Uri, name: String): PendingIntent = PendingIntent.getActivity(
        this, uri.hashCode(), PlayerActivity.intent(this, uri, name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(): Notification {
        val all = DownloadStore.tasks.value
        val active = all.filter { it.status == Status.RUNNING }
        val queued = all.count { it.status == Status.QUEUED }
        val known = active.filter { it.progress >= 0 }
        val pct = if (known.isEmpty()) -1 else known.map { it.progress }.average().toInt()
        val title = when (active.size) {
            0 -> "Preparing downloads"
            1 -> active[0].title
            else -> "${active.size} downloads running"
        }
        val text = buildString {
            if (active.size == 1) {
                append(active[0].phase.ifBlank { "Starting" })
                if (pct in 0..99) append(" · $pct%")
                fmtEta(active[0].eta).takeIf { it.isNotEmpty() && pct in 0..99 }?.let { append(" · $it") }
            } else if (pct >= 0) append("$pct% overall")
            if (queued > 0) append(if (isEmpty()) "$queued waiting" else " · $queued waiting")
        }
        return NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .build()
    }

    private fun resultNotification(title: String, text: String, intent: PendingIntent): Notification =
        NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

    companion object {
        const val NOTIF_ID = 1
        private val LEFTOVERS = setOf("part", "ytdl", "json", "txt", "tmp", "vtt", "srt", "jpg", "jpeg", "png", "webp")
        private val PERCENT = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")

        /** Make sure the service is working through the queue. Call after adding or re-queuing tasks. */
        fun kick(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
            } catch (e: Exception) {
                Log.w(App.TAG, "could not start download service: ${e.message}")
            }
        }

        private fun stopProcess(id: String) {
            try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) { }
        }

        /** Stops one download for good. A running one has its engine process ended; its partial file is removed. */
        fun cancel(ctx: Context, id: String) {
            val wasRunning = DownloadStore.get(id)?.status == Status.RUNNING
            DownloadStore.update(id, persist = true) { copy(status = Status.CANCELLED, phase = "", progress = -1f) }
            if (wasRunning) stopProcess(id) else File(tmpRoot(ctx), id).deleteRecursively()
            Scheduler.arm(ctx)
        }

        /** Pauses one download, keeping what it has so far. */
        fun pause(ctx: Context, id: String) {
            val t = DownloadStore.get(id) ?: return
            if (!t.active) return
            DownloadStore.update(id, persist = true) { copy(status = Status.PAUSED, eta = -1, phase = "") }
            if (t.status == Status.RUNNING) stopProcess(id)
            Scheduler.arm(ctx)
        }

        /** Carries on a paused download, retries a failed one, or starts a scheduled one now. */
        fun resume(ctx: Context, id: String) {
            DownloadStore.requeue(id)
            kick(ctx)
        }

        fun pauseAll(ctx: Context) = DownloadStore.tasks.value.filter { it.active }.forEach { pause(ctx, it.id) }

        fun resumeAll(ctx: Context) {
            DownloadStore.tasks.value.filter { it.status == Status.PAUSED }.forEach { DownloadStore.requeue(it.id) }
            kick(ctx)
        }
    }
}
