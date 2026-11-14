package uk.jj.grab

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Built-in player for downloaded files, including those in the private folder (which no other app can open).
 * Standard controls with speed and subtitle choice, swipe left/right to seek, double-tap to jump 10 seconds,
 * and it carries on from where the file was last stopped.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var key = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        key = uri.toString()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: uri.lastPathSegment ?: ""

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val p = ExoPlayer.Builder(this).build().apply {
            // Pause for calls and other apps' sound, as any media player should.
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            val at = Prefs.playPosition(key)
            if (at > 0) seekTo(at)
            playWhenReady = true
        }
        player = p

        setContent {
            PlayerScreen(
                player = p,
                title = title,
                canOpenElsewhere = uri.scheme == "content",
                onBack = { finish() },
                onRotate = {
                    val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                },
                onOpenElsewhere = {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { startActivity(view) } catch (_: ActivityNotFoundException) { }
                },
            )
        }
    }

    override fun onStop() {
        rememberPosition()
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    /** Remember where playback stopped, unless it is near the very start or end. */
    private fun rememberPosition() {
        val p = player ?: return
        val pos = p.currentPosition
        val length = p.duration
        Prefs.setPlayPosition(key, if (length > 0 && pos > 5_000 && pos < length - 10_000) pos else 0)
    }

    companion object {
        private const val EXTRA_TITLE = "title"

        fun intent(ctx: Context, uri: Uri, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java).setData(uri).putExtra(EXTRA_TITLE, title)

        fun open(ctx: Context, uri: Uri, title: String) {
            try { ctx.startActivity(intent(ctx, uri, title)) } catch (_: Exception) { }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    player: ExoPlayer,
    title: String,
    canOpenElsewhere: Boolean,
    onBack: () -> Unit,
    onRotate: () -> Unit,
    onOpenElsewhere: () -> Unit,
) {
    var controls by remember { mutableStateOf(true) }
    var seekLabel by remember { mutableStateOf<String?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    controllerShowTimeoutMs = 3500
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == View.VISIBLE })
                    playerView = this
                }
            },
        )

        // While the controls are hidden the whole picture takes gestures:
        // tap = show controls, double-tap left/right = back/forward 10 seconds, swipe sideways = seek.
        if (!controls) Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { playerView?.showController() },
                        onDoubleTap = { at ->
                            val step = if (at.x < size.width / 2f) -10_000L else 10_000L
                            player.seekTo((player.currentPosition + step).coerceAtLeast(0))
                        },
                    )
                }
                .pointerInput(Unit) {
                    var from = 0L
                    var moved = 0f
                    var target = 0L
                    detectHorizontalDragGestures(
                        onDragStart = { from = player.currentPosition; moved = 0f; target = from },
                        onDragEnd = { player.seekTo(target); seekLabel = null },
                        onDragCancel = { seekLabel = null },
                    ) { change, dx ->
                        change.consume()
                        moved += dx
                        // A swipe across the full width moves two minutes.
                        val length = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        target = (from + (moved / size.width * 120_000f).toLong()).coerceIn(0, length)
                        val delta = target - from
                        seekLabel = "${clock(target)}  (${if (delta >= 0) "+" else "-"}${clock(kotlin.math.abs(delta))})"
                    }
                }
        )

        if (controls) Row(
            Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close player", tint = Color.White) }
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onRotate) { Icon(painterResource(R.drawable.ic_rotate), contentDescription = "Turn the picture", tint = Color.White) }
            if (canOpenElsewhere) IconButton(onClick = onOpenElsewhere) {
                Icon(painterResource(R.drawable.ic_open), contentDescription = "Open in another app", tint = Color.White)
            }
        }

        seekLabel?.let {
            Text(
                it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
