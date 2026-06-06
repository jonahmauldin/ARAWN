package com.arawn.scanner

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val MatteBlack    = Color(0xFF050507)
private val DeepRed       = Color(0xFFB31818)
private val GlowRed       = Color(0xFFFF2A2A)
private val EmberRed      = Color(0xFF7A0F0F)
private val White         = Color(0xFFF0F0F2)
private val MapInk        = Color(0xFFE6E6E6)
private val TacticalAmber = Color(0xFFE0B341)
private val TacticalCyan  = Color(0xFF4FD8E0)
private val TacticalGreen = Color(0xFF35D07F)

/**
 * Cold-start boot screen. Holds for ~1.8s then calls [onComplete]; tapping
 * anywhere skips the rest of the hold. Pure Compose Canvas — no extra deps.
 */
@Composable
fun BootScreen(onComplete: () -> Unit) {
    val backgroundAlpha = remember { Animatable(0f) }
    val wordmarkAlpha   = remember { Animatable(0f) }
    val statusAlpha     = remember { Animatable(0f) }
    val houndsAlpha     = remember { Animatable(0f) }
    val readyAlpha      = remember { Animatable(0f) }
    val progress        = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        backgroundAlpha.animateTo(1f, tween(420, easing = LinearEasing))
        wordmarkAlpha.animateTo(1f, tween(360))
        statusAlpha.animateTo(1f, tween(220))
        houndsAlpha.animateTo(1f, tween(420))
        progress.animateTo(1f, tween(420, easing = LinearEasing))
        readyAlpha.animateTo(1f, tween(180))
        delay(220)
        onComplete()
    }

    // Skip-on-tap (no ripple, terminal feel).
    val skip = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MatteBlack)
            .clickable(
                interactionSource = skip,
                indication = null,
                onClick = onComplete,
            ),
    ) {
        TopoBackground(alpha = backgroundAlpha.value)
        ScanLineOverlay(alpha = backgroundAlpha.value)
        ViewportFrame(alpha = backgroundAlpha.value)

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.85f))
            ArawnWordmark(alpha = wordmarkAlpha.value)
            Spacer(Modifier.height(8.dp))
            Tagline(alpha = wordmarkAlpha.value)
            Spacer(Modifier.height(28.dp))
            BootStatus(
                progress  = progress.value,
                ready     = readyAlpha.value > 0.5f,
                alpha     = statusAlpha.value,
            )
            Spacer(Modifier.weight(0.55f))
            CwnAnnwnArray(alpha = houndsAlpha.value)
            Spacer(Modifier.height(18.dp))
            DataTicker(alpha = houndsAlpha.value)
            Spacer(Modifier.weight(0.20f))
        }
    }
}

// ---------------------------------------------------------------------------
//  Background: topographic map, grid, coordinate markings
// ---------------------------------------------------------------------------

@Composable
private fun TopoBackground(alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Minor 32dp grid
        val minor = 32.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = MapInk.copy(alpha = 0.025f * alpha),
                start = Offset(x, 0f),
                end   = Offset(x, size.height),
                strokeWidth = 0.6f,
            )
            x += minor
        }
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = MapInk.copy(alpha = 0.025f * alpha),
                start = Offset(0f, y),
                end   = Offset(size.width, y),
                strokeWidth = 0.6f,
            )
            y += minor
        }

        // Major 128dp grid with crosshair ticks
        val major = 128.dp.toPx()
        val tickHalf = 3.dp.toPx()
        var mx = 0f
        while (mx < size.width) {
            drawLine(
                color = MapInk.copy(alpha = 0.06f * alpha),
                start = Offset(mx, 0f),
                end   = Offset(mx, size.height),
                strokeWidth = 0.8f,
            )
            mx += major
        }
        var my = 0f
        while (my < size.height) {
            drawLine(
                color = MapInk.copy(alpha = 0.06f * alpha),
                start = Offset(0f, my),
                end   = Offset(size.width, my),
                strokeWidth = 0.8f,
            )
            my += major
        }
        // Crosshair tick marks at every major intersection
        var ix = 0f
        while (ix < size.width) {
            var iy = 0f
            while (iy < size.height) {
                drawLine(
                    color = MapInk.copy(alpha = 0.14f * alpha),
                    start = Offset(ix - tickHalf, iy),
                    end   = Offset(ix + tickHalf, iy),
                    strokeWidth = 0.8f,
                )
                drawLine(
                    color = MapInk.copy(alpha = 0.14f * alpha),
                    start = Offset(ix, iy - tickHalf),
                    end   = Offset(ix, iy + tickHalf),
                    strokeWidth = 0.8f,
                )
                iy += major
            }
            ix += major
        }

        // Two terrain features — concentric contour "ridges"
        drawContourRings(
            center = Offset(size.width * 0.28f, size.height * 0.36f),
            baseRadius = size.minDimension * 0.16f,
            rings = 5,
            phase = 0.7f,
            alpha = alpha,
        )
        drawContourRings(
            center = Offset(size.width * 0.78f, size.height * 0.62f),
            baseRadius = size.minDimension * 0.20f,
            rings = 6,
            phase = 2.1f,
            alpha = alpha,
        )

        // Coordinate labels in the four corners
        drawTacticalText(
            text = "GRID 47Q ML 84500 32600",
            xPx = 14.dp.toPx(),
            yPx = 22.dp.toPx(),
            sizeSp = 9f,
            color = TacticalCyan.copy(alpha = 0.55f * alpha),
        )
        drawTacticalText(
            text = "UTM-K · WGS84",
            xPx = size.width - 110.dp.toPx(),
            yPx = 22.dp.toPx(),
            sizeSp = 9f,
            color = TacticalCyan.copy(alpha = 0.55f * alpha),
        )
        drawTacticalText(
            text = "MAG 047° · DECL +12.3",
            xPx = 14.dp.toPx(),
            yPx = size.height - 14.dp.toPx(),
            sizeSp = 9f,
            color = TacticalAmber.copy(alpha = 0.45f * alpha),
        )
        drawTacticalText(
            text = "ELEV 248m · SLOPE 4°",
            xPx = size.width - 130.dp.toPx(),
            yPx = size.height - 14.dp.toPx(),
            sizeSp = 9f,
            color = TacticalAmber.copy(alpha = 0.45f * alpha),
        )
    }
}

/** Draw [rings] nested wavy closed curves around [center] — a contour-line cluster. */
private fun DrawScope.drawContourRings(
    center: Offset,
    baseRadius: Float,
    rings: Int,
    phase: Float,
    alpha: Float,
) {
    val segments = 80
    for (r in 0 until rings) {
        val ringRadius = baseRadius * (0.35f + r * 0.18f)
        val path = Path()
        for (i in 0..segments) {
            val t = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
            // Two summed harmonics so each ring is irregular but smooth.
            val wobble =
                sin(t * 3f + phase + r * 0.4f) * 0.10f +
                cos(t * 5f - phase * 0.5f + r * 0.7f) * 0.06f
            val rad = ringRadius * (1f + wobble)
            val x = center.x + cos(t) * rad
            val y = center.y + sin(t) * rad * 0.78f  // squash → ovaloid terrain
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path = path,
            color = MapInk.copy(alpha = (0.13f - r * 0.012f) * alpha),
            style = Stroke(width = 0.9f),
        )
    }
}

/** Tactical-style monospace label drawn directly on the canvas. */
private fun DrawScope.drawTacticalText(
    text: String,
    xPx: Float,
    yPx: Float,
    sizeSp: Float,
    color: Color,
) {
    val paint = Paint().apply {
        this.color = color.toArgb()
        textSize = sizeSp * density
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        letterSpacing = 0.15f
    }
    drawContext.canvas.nativeCanvas.drawText(text, xPx, yPx, paint)
}

private fun Color.toArgb(): Int {
    val a = (alpha * 255f).toInt() and 0xff
    val r = (red   * 255f).toInt() and 0xff
    val g = (green * 255f).toInt() and 0xff
    val b = (blue  * 255f).toInt() and 0xff
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

// ---------------------------------------------------------------------------
//  Corner-bracket viewport frame
// ---------------------------------------------------------------------------

@Composable
private fun ViewportFrame(alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pad = 12.dp.toPx()
        val len = 22.dp.toPx()
        val stroke = 1.4.dp.toPx()
        val col = White.copy(alpha = 0.55f * alpha)
        // TL
        drawLine(col, Offset(pad, pad), Offset(pad + len, pad), stroke)
        drawLine(col, Offset(pad, pad), Offset(pad, pad + len), stroke)
        // TR
        drawLine(col, Offset(size.width - pad, pad), Offset(size.width - pad - len, pad), stroke)
        drawLine(col, Offset(size.width - pad, pad), Offset(size.width - pad, pad + len), stroke)
        // BL
        drawLine(col, Offset(pad, size.height - pad), Offset(pad + len, size.height - pad), stroke)
        drawLine(col, Offset(pad, size.height - pad), Offset(pad, size.height - pad - len), stroke)
        // BR
        drawLine(col, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - len, size.height - pad), stroke)
        drawLine(col, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - len), stroke)
    }
}

// ---------------------------------------------------------------------------
//  Animated scan line
// ---------------------------------------------------------------------------

@Composable
private fun ScanLineOverlay(alpha: Float) {
    val infinite = rememberInfiniteTransition(label = "scan")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scan-y",
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val y = size.height * t
        // Hairline scan ribbon — three stacked lines so it has a soft "core".
        drawLine(
            color = TacticalCyan.copy(alpha = 0.04f * alpha),
            start = Offset(0f, y - 2f),
            end   = Offset(size.width, y - 2f),
            strokeWidth = 3.dp.toPx(),
        )
        drawLine(
            color = TacticalCyan.copy(alpha = 0.10f * alpha),
            start = Offset(0f, y),
            end   = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = TacticalCyan.copy(alpha = 0.04f * alpha),
            start = Offset(0f, y + 2f),
            end   = Offset(size.width, y + 2f),
            strokeWidth = 3.dp.toPx(),
        )
    }
}

// ---------------------------------------------------------------------------
//  ARAWN wordmark with red edge glow
// ---------------------------------------------------------------------------

@Composable
private fun ArawnWordmark(alpha: Float) {
    Box(contentAlignment = Alignment.Center) {
        // Bloom layer (red), large blur — soft halo behind the white letters.
        // Modifier.blur is API 31+; below it gracefully renders as solid red,
        // hidden behind the white letterforms — still acceptable.
        Text(
            text = "ARAWN",
            color = GlowRed.copy(alpha = 0.85f * alpha),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 64.sp,
            letterSpacing = 12.sp,
            modifier = Modifier.blur(22.dp),
        )
        // Mid-tone bloom — tighter halo for definition.
        Text(
            text = "ARAWN",
            color = DeepRed.copy(alpha = 0.55f * alpha),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 64.sp,
            letterSpacing = 12.sp,
            modifier = Modifier.blur(6.dp),
        )
        // Primary white wordmark.
        Text(
            text = "ARAWN",
            color = White.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 64.sp,
            letterSpacing = 12.sp,
        )
    }
}

@Composable
private fun Tagline(alpha: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .width(220.dp)
                .height(1.dp),
        ) {
            drawLine(
                color = DeepRed.copy(alpha = 0.85f * alpha),
                start = Offset(0f, 0f),
                end   = Offset(size.width, 0f),
                strokeWidth = 1.4.dp.toPx(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "FIELD  OPERATIONS  PLATFORM",
            color = TacticalAmber.copy(alpha = 0.85f * alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "v1.7.0   ·   BUILD-52   ·   KEYSTORE: STABLE",
            color = MapInk.copy(alpha = 0.42f * alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
        )
    }
}

// ---------------------------------------------------------------------------
//  Boot status + progress
// ---------------------------------------------------------------------------

@Composable
private fun BootStatus(progress: Float, ready: Boolean, alpha: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (ready) "▸ ALL SUBSYSTEMS ONLINE" else "▸ INITIALIZING SUBSYSTEMS",
            color = if (ready) TacticalGreen.copy(alpha = alpha) else TacticalAmber.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .width(260.dp)
                .height(4.dp),
        ) {
            // Track
            drawLine(
                color = White.copy(alpha = 0.12f * alpha),
                start = Offset(0f, size.height / 2f),
                end   = Offset(size.width, size.height / 2f),
                strokeWidth = size.height,
            )
            // Fill
            val fillColor = if (ready) TacticalGreen else DeepRed
            drawLine(
                color = fillColor.copy(alpha = 0.90f * alpha),
                start = Offset(0f, size.height / 2f),
                end   = Offset(size.width * progress, size.height / 2f),
                strokeWidth = size.height,
            )
            // Tick segmentation
            val segs = 16
            for (s in 1 until segs) {
                val sx = size.width * (s.toFloat() / segs)
                drawLine(
                    color = MatteBlack,
                    start = Offset(sx, 0f),
                    end   = Offset(sx, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Cŵn Annwn — three HUD hound silhouettes
// ---------------------------------------------------------------------------

@Composable
private fun CwnAnnwnArray(alpha: Float) {
    val infinite = rememberInfiniteTransition(label = "hound")
    // Slow eye pulse + per-hound scanline drift.
    val eyePulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eye",
    )
    val scanDrift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
        ),
        label = "drift",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("TRK-01", "TRK-02", "TRK-03").forEachIndexed { idx, label ->
            HoundNode(
                label = label,
                confidence = listOf("0.94", "0.97", "0.91")[idx],
                eyePulse = eyePulse,
                scanPhase = (scanDrift + idx * 0.33f) % 1f,
                alpha = alpha,
            )
        }
    }
}

@Composable
private fun HoundNode(
    label: String,
    confidence: String,
    eyePulse: Float,
    scanPhase: Float,
    alpha: Float,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 92.dp, height = 72.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawHoundFrame(alpha)
                drawHound(eyePulse, scanPhase, alpha)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "◆ $label",
            color = White.copy(alpha = 0.92f * alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
        )
        Text(
            text = "CONF $confidence",
            color = TacticalCyan.copy(alpha = 0.75f * alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

/** Corner-bracket reticle around the hound — the "subsystem viewport" framing. */
private fun DrawScope.drawHoundFrame(alpha: Float) {
    val len = 8.dp.toPx()
    val stroke = 1.2.dp.toPx()
    val col = White.copy(alpha = 0.45f * alpha)
    // TL
    drawLine(col, Offset(0f, 0f), Offset(len, 0f), stroke)
    drawLine(col, Offset(0f, 0f), Offset(0f, len), stroke)
    // TR
    drawLine(col, Offset(size.width, 0f), Offset(size.width - len, 0f), stroke)
    drawLine(col, Offset(size.width, 0f), Offset(size.width, len), stroke)
    // BL
    drawLine(col, Offset(0f, size.height), Offset(len, size.height), stroke)
    drawLine(col, Offset(0f, size.height), Offset(0f, size.height - len), stroke)
    // BR
    drawLine(col, Offset(size.width, size.height), Offset(size.width - len, size.height), stroke)
    drawLine(col, Offset(size.width, size.height), Offset(size.width, size.height - len), stroke)
}

/**
 * Stylized side-profile hound, tracking pose (head low, tail straight back).
 * Points are defined in a 100×60 unit box facing RIGHT, then scaled to canvas.
 */
private fun DrawScope.drawHound(eyePulse: Float, scanPhase: Float, alpha: Float) {
    val unitW = 100f
    val unitH = 60f
    // Inset so the silhouette doesn't touch the reticle brackets.
    val insetX = 8.dp.toPx()
    val insetY = 10.dp.toPx()
    val sx = (size.width  - insetX * 2) / unitW
    val sy = (size.height - insetY * 2) / unitH
    fun p(x: Float, y: Float) = Offset(insetX + x * sx, insetY + y * sy)

    // Body polyline (CW from nose tip). Crisp linear segments give it a
    // technical/CAD silhouette feel — anti-fantasy, anti-furry.
    val pts = listOf(
        p(98f, 34f),   // 1 nose tip
        p(89f, 28f),   // 2 top of snout
        p(78f, 26f),   // 3 forehead
        p(75f, 16f),   // 4 front ear base
        p(80f, 10f),   // 5 ear tip
        p(84f, 22f),   // 6 rear ear base
        p(68f, 24f),   // 7 nape (head low)
        p(56f, 18f),   // 8 shoulder peak
        p(38f, 22f),   // 9 back ridge
        p(20f, 26f),   // 10 lower back
        p(10f, 28f),   // 11 hip
        p(2f,  30f),   // 12 tail root
        p(-2f, 32f),   // 13 tail tip (held back, slightly down)
        p(8f,  35f),   // 14 underside of tail
        p(12f, 38f),   // 15 rear haunch
        p(10f, 54f),   // 16 rear paw back
        p(18f, 54f),   // 17 rear paw front
        p(20f, 38f),   // 18 belly back
        p(46f, 42f),   // 19 belly mid
        p(60f, 42f),   // 20 belly forward
        p(60f, 54f),   // 21 front paw back
        p(68f, 54f),   // 22 front paw front
        p(70f, 38f),   // 23 front leg upper
        p(78f, 34f),   // 24 chest under
        p(86f, 36f),   // 25 throat
        p(92f, 36f),   // 26 underjaw
    )

    val body = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }

    // Subtle inner fill so the silhouette reads as a "panel" not just an outline.
    drawPath(body, color = White.copy(alpha = 0.04f * alpha))
    // Primary stroke — clean white outline.
    drawPath(
        path = body,
        color = White.copy(alpha = 0.95f * alpha),
        style = Stroke(width = 1.5.dp.toPx()),
    )

    // Second front leg, hinted behind the lead leg → adds depth/motion.
    drawLine(
        color = White.copy(alpha = 0.55f * alpha),
        start = p(66f, 40f),
        end   = p(66f, 54f),
        strokeWidth = 1.2.dp.toPx(),
    )
    // Second rear leg hint.
    drawLine(
        color = White.copy(alpha = 0.55f * alpha),
        start = p(22f, 38f),
        end   = p(22f, 54f),
        strokeWidth = 1.2.dp.toPx(),
    )

    // RED ear interior — small filled wedge inside the ear outline.
    val earPath = Path().apply {
        moveTo(p(76f, 18f).x, p(76f, 18f).y)
        lineTo(p(80f, 11f).x, p(80f, 11f).y)
        lineTo(p(82f, 20f).x, p(82f, 20f).y)
        close()
    }
    drawPath(earPath, color = DeepRed.copy(alpha = 0.92f * alpha))

    // RED EYE — pulsing dot with a soft halo. Two concentric circles.
    val eyeCenter = p(82f, 23f)
    val eyeR = 2.2.dp.toPx()
    drawCircle(
        color = EmberRed.copy(alpha = (0.45f * eyePulse) * alpha),
        radius = eyeR * 2.6f,
        center = eyeCenter,
    )
    drawCircle(
        color = GlowRed.copy(alpha = (0.90f * eyePulse) * alpha),
        radius = eyeR,
        center = eyeCenter,
    )

    // Local horizontal scanline crossing the hound (subsystem feel).
    val scanY = insetY + scanPhase * (size.height - insetY * 2)
    drawLine(
        color = TacticalCyan.copy(alpha = 0.18f * alpha),
        start = Offset(insetX, scanY),
        end   = Offset(size.width - insetX, scanY),
        strokeWidth = 0.8.dp.toPx(),
    )
}

// ---------------------------------------------------------------------------
//  Bottom data ticker
// ---------------------------------------------------------------------------

@Composable
private fun DataTicker(alpha: Float) {
    Text(
        text = "▸  CWN ANNWN RECON ARRAY   ◆   3 TRACK NODES ACTIVE   ◆   PERSISTENT SURVEILLANCE",
        color = MapInk.copy(alpha = 0.55f * alpha),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        letterSpacing = 2.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
