package uk.jj.grab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Places.init(this)              // shortcuts and history
        DownloadStore.init(this)       // saved queue and history; interrupted downloads go back in the queue
        AdBlock.init(this)
        applyTheme(this)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )

        // Wi-Fi only: when Wi-Fi comes back, carry on with whatever was waiting for it.
        Net.init(this) {
            if (DownloadStore.tasks.value.any { it.status == Status.QUEUED }) DownloadService.kick(this)
        }

        // Unpack the download engine off the main thread, then run the once-a-day update check.
        Yt.start(this) {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        }

        // Cookie hand-off files are deleted straight after use; clear any left behind by a crash.
        thread(isDaemon = true) {
            cacheDir.listFiles { f: File -> f.name.startsWith("ck-") }?.forEach { it.delete() }
        }
    }

    companion object {
        const val TAG = "Grab"
        const val CHANNEL = "downloads"

        /**
         * Applies the light/dark setting to the whole app, including the web pages in the browser.
         * (Android 12 and later; on older phones only Grab's own screens follow the setting.)
         */
        fun applyTheme(ctx: Context) {
            if (Build.VERSION.SDK_INT < 31) return
            try {
                ctx.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                    when (Prefs.theme.value) {
                        Prefs.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                        Prefs.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                        else -> UiModeManager.MODE_NIGHT_AUTO
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "theme: ${e.message}")
            }
        }
    }
}
