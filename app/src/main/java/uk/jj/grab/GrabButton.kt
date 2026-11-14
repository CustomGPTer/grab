package uk.jj.grab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

private val IDLE_GREY = Color(0xFF8D8D8D)
private val FOUND_RED = Color(0xFFE53935)
private val FOUND_GREEN = Color(0xFF2E9E4F)

/**
 * The round Download button that floats over the page.
 * Grey while nothing has been found; turns the chosen colour, pulses once and shows a count when Grab
 * spots a video. It still works while grey. Drag it anywhere; it stays where it is left.
 *
 * @param found how many videos Grab has spotted on the page.
 */
@Composable
fun GrabButton(found: Int, onClick: () -> Unit) {
    val colourChoice by Prefs.buttonColour.flow.collectAsStateWithLifecycle()
    val reset by Prefs.buttonReset.collectAsStateWithLifecycle()
    val lit = found > 0
    val foundColour = when (colourChoice) {
        Prefs.BUTTON_GREEN -> FOUND_GREEN
        Prefs.BUTTON_THEME -> MaterialTheme.colorScheme.primary
        else -> FOUND_RED
    }
    val colour by animateColorAsState(if (lit) foundColour else IDLE_GREY, tween(250), label = "button colour")

    // One ring that grows and fades each time the button goes from grey to lit.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(lit) {
        if (lit) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(800))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sizePx = with(density) { 60.dp.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }
        val maxX = (constraints.maxWidth - sizePx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - sizePx).coerceAtLeast(0f)

        // Saved as a fraction of the page area so it lands in the same place after rotation.
        var pos by remember(maxX, maxY, reset) {
            val fx = Prefs.buttonX
            val fy = Prefs.buttonY
            mutableStateOf(
                if (fx < 0f || fy < 0f) Offset((maxX - marginPx).coerceAtLeast(0f), (maxY - marginPx).coerceAtLeast(0f))
                else Offset(fx.coerceIn(0f, 1f) * maxX, fy.coerceIn(0f, 1f) * maxY)
            )
        }

        Box(
            Modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(60.dp)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            Prefs.buttonX = if (maxX > 0f) pos.x / maxX else 1f
                            Prefs.buttonY = if (maxY > 0f) pos.y / maxY else 1f
                        },
                    ) { change, drag ->
                        change.consume()
                        pos = Offset((pos.x + drag.x).coerceIn(0f, maxX), (pos.y + drag.y).coerceIn(0f, maxY))
                    }
                }
                .semantics {
                    contentDescription = if (lit) "Download. $found video${if (found == 1) "" else "s"} found on this page." else "Download. No video found on this page yet."
                },
            contentAlignment = Alignment.Center,
        ) {
            if (lit && pulse.value < 1f) Box(
                Modifier.size(60.dp).scale(1f + 0.9f * pulse.value).alpha((1f - pulse.value) * 0.55f).background(foundColour, CircleShape)
            )
            Surface(onClick = onClick, shape = CircleShape, color = colour, shadowElevation = 6.dp, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            if (lit) Box(
                Modifier.align(Alignment.TopEnd).defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .background(Color.White, CircleShape).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (found > 9) "9+" else found.toString(), color = foundColour, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
