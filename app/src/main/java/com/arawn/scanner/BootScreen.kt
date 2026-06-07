package com.arawn.scanner

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlin.math.floor
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
    val lockOn          = remember { Animatable(0f) }
    val readyAlpha      = remember { Animatable(0f) }
    val progress        = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        backgroundAlpha.animateTo(1f, tween(420, easing = LinearEasing))
        wordmarkAlpha.animateTo(1f, tween(340))
        statusAlpha.animateTo(1f, tween(220))
        houndsAlpha.animateTo(1f, tween(440))
        // The "lock-on" beat: the targeting reticle converges on the hound's eye
        // and the eye-glow ramps — what sells the tracking-subsystem read.
        lockOn.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        progress.animateTo(1f, tween(380, easing = LinearEasing))
        readyAlpha.animateTo(1f, tween(160))
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
            Spacer(Modifier.weight(0.62f))
            ArawnWordmark(alpha = wordmarkAlpha.value)
            Spacer(Modifier.height(10.dp))
            Tagline(alpha = wordmarkAlpha.value)
            Spacer(Modifier.weight(0.5f))
            // The hero: a single Cŵn Annwn rendered as a tracking subsystem,
            // centred beneath the wordmark.
            CwnAnnwnSubsystem(alpha = houndsAlpha.value, lockOn = lockOn.value)
            Spacer(Modifier.weight(0.5f))
            BootStatus(
                progress  = progress.value,
                ready     = readyAlpha.value > 0.5f,
                alpha     = statusAlpha.value,
            )
            Spacer(Modifier.height(16.dp))
            DataTicker(alpha = houndsAlpha.value)
            Spacer(Modifier.weight(0.28f))
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
            text = "v${BuildConfig.VERSION_NAME}   ·   BUILD-${BuildConfig.BUILD_NUMBER}   ·   KEYSTORE: STABLE",
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
//  Cŵn Annwn — single tracking-subsystem hound (hero element)
//
//  One stylised hound head rendered as a platform subsystem: a clean white
//  digital silhouette with red ears and a glowing red eye, wrapped in a
//  targeting reticle that locks onto the eye, sensor scan-lines, a wireframe
//  hint, and a data-dissolve edge. Deliberately angular (CAD/technical, not
//  fantasy) — it reads as sensor hardware tracking a target, never a beast.
// ---------------------------------------------------------------------------

@Composable
private fun CwnAnnwnSubsystem(alpha: Float, lockOn: Float) {
    val infinite = rememberInfiniteTransition(label = "hound")
    val eyePulse by infinite.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eye",
    )
    val scanPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(2600, easing = LinearEasing)),
        label = "scan",
    )
    val glitchPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(820, easing = LinearEasing)),
        label = "glitch",
    )

    Box(
        modifier = Modifier.size(width = 300.dp, height = 208.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSubsystemFrame(alpha)
            drawHoundHead(eyePulse, scanPhase, glitchPhase, lockOn, alpha)
            drawSubsystemLabels(lockOn, eyePulse, alpha)
        }
    }
}

/** Corner brackets + faint rails framing the hound as a sensor viewport. */
private fun DrawScope.drawSubsystemFrame(alpha: Float) {
    val len = 14.dp.toPx()
    val stroke = 1.3.dp.toPx()
    val col = White.copy(alpha = 0.5f * alpha)
    val w = size.width
    val h = size.height
    // TL / TR / BL / BR corner brackets
    drawLine(col, Offset(0f, 0f), Offset(len, 0f), stroke)
    drawLine(col, Offset(0f, 0f), Offset(0f, len), stroke)
    drawLine(col, Offset(w, 0f), Offset(w - len, 0f), stroke)
    drawLine(col, Offset(w, 0f), Offset(w, len), stroke)
    drawLine(col, Offset(0f, h), Offset(len, h), stroke)
    drawLine(col, Offset(0f, h), Offset(0f, h - len), stroke)
    drawLine(col, Offset(w, h), Offset(w - len, h), stroke)
    drawLine(col, Offset(w, h), Offset(w, h - len), stroke)
    // Hairline top/bottom rails between the brackets.
    drawLine(TacticalCyan.copy(alpha = 0.10f * alpha), Offset(len + 6f, 0f), Offset(w - len - 6f, 0f), 0.8f)
    drawLine(TacticalCyan.copy(alpha = 0.10f * alpha), Offset(len + 6f, h), Offset(w - len - 6f, h), 0.8f)
}

/**
 * The hound head. Points live in a 0..100 box facing RIGHT, then map to the
 * canvas. Pure linear segments keep the silhouette technical, not furry.
 */
private fun DrawScope.drawHoundHead(
    eyePulse: Float,
    scanPhase: Float,
    glitchPhase: Float,
    lockOn: Float,
    alpha: Float,
) {
    val insetX = 46.dp.toPx()
    val insetY = 20.dp.toPx()
    val w = size.width - insetX * 2
    val h = size.height - insetY * 2
    fun p(x: Float, y: Float) = Offset(insetX + x / 100f * w, insetY + y / 100f * h)

    // Head outline (clockwise from the nose, two alert ears for 3/4 depth).
    val pts = listOf(
        p(97f, 54f),  // nose tip
        p(80f, 48f),  // muzzle top
        p(68f, 49f),  // nose bridge / stop
        p(62f, 41f),  // forehead
        p(60f, 36f),  // front ear base (front)
        p(66f, 9f),   // front ear tip
        p(49f, 33f),  // front ear base (back)
        p(45f, 31f),  // ear valley
        p(42f, 30f),  // rear ear base (front)
        p(31f, 11f),  // rear ear tip
        p(22f, 33f),  // rear ear base (back)
        p(18f, 41f),  // skull crown
        p(15f, 55f),  // nape
        p(16f, 70f),  // neck back
        p(22f, 84f),  // neck cut (bottom-left)
        p(44f, 82f),  // throat base
        p(58f, 76f),  // lower jaw rear
        p(72f, 70f),  // jaw mid
        p(88f, 62f),  // chin
        p(93f, 58f),  // mouth corner
    )
    val head = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y)
        close()
    }

    // Holographic chromatic split — faint cyan/red offset ghosts behind white.
    val split = 1.6.dp.toPx() * (0.6f + 0.4f * glitchPhase)
    drawPath(head.shifted(split, 0f),  TacticalCyan.copy(alpha = 0.18f * alpha), style = Stroke(1.dp.toPx()))
    drawPath(head.shifted(-split, 0f), DeepRed.copy(alpha = 0.16f * alpha),      style = Stroke(1.dp.toPx()))

    // Panel fill + primary white outline.
    drawPath(head, White.copy(alpha = 0.05f * alpha))
    drawPath(head, White.copy(alpha = 0.96f * alpha), style = Stroke(1.7.dp.toPx()))

    // Wireframe hint — a few interior construction lines.
    val wire = TacticalCyan.copy(alpha = 0.16f * alpha)
    drawLine(wire, p(64f, 49f), p(95f, 54f), 0.9f)   // eye → nose ridge
    drawLine(wire, p(64f, 49f), p(72f, 70f), 0.9f)   // eye → jaw
    drawLine(wire, p(40f, 38f), p(70f, 49f), 0.9f)   // skull → muzzle
    drawLine(wire, p(30f, 46f), p(30f, 74f), 0.9f)   // neck section

    // Red ears — filled wedges inside each ear outline.
    fun wedge(a: Offset, b: Offset, c: Offset, col: Color) =
        drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, col)
    wedge(p(60f, 35f), p(64f, 14f), p(52f, 33f), DeepRed.copy(alpha = 0.92f * alpha))  // front ear
    wedge(p(41f, 31f), p(33f, 15f), p(26f, 32f), DeepRed.copy(alpha = 0.85f * alpha))  // rear ear

    // Nostril + calm closed mouth line (no fangs — not a monster).
    drawLine(White.copy(alpha = 0.55f * alpha), p(90f, 52f), p(86f, 53f), 1.4.dp.toPx())
    drawLine(White.copy(alpha = 0.5f * alpha),  p(93f, 58f), p(74f, 58f), 1.1.dp.toPx())

    // Glowing red eye — intensifies as the reticle locks on.
    val eye = p(64f, 49f)
    val glow = (0.45f + 0.55f * lockOn) * eyePulse
    val eyeR = 3.0.dp.toPx()
    drawCircle(EmberRed.copy(alpha = 0.35f * glow * alpha), eyeR * 3.4f, eye)
    drawCircle(GlowRed.copy(alpha = 0.90f * glow * alpha),  eyeR * 1.7f, eye)
    drawCircle(White.copy(alpha = 0.95f * glow * alpha),    eyeR * 0.7f, eye)

    // Data-dissolve edge — the skull/neck back breaking into drifting shards.
    val edgeX = 16f
    for (k in 0 until 9) {
        val ry = 26f + k * 6.3f
        val flick = fract(glitchPhase * 1.3f + k * 0.37f)
        val drift = flick * 16f
        val segLen = 4f + fract(k * 0.61f + glitchPhase) * 11f
        val x1 = edgeX - drift
        val x0 = x1 - segLen
        val shardAlpha = (1f - flick) * 0.5f
        val col = if (k % 3 == 0) TacticalCyan else White
        drawLine(col.copy(alpha = shardAlpha * alpha), p(x0, ry), p(x1, ry), 1.2.dp.toPx())
    }

    // Sweeping sensor scan-line across the head bounds.
    val top = p(0f, 6f).y
    val bot = p(0f, 92f).y
    val sy = top + scanPhase * (bot - top)
    drawLine(TacticalCyan.copy(alpha = 0.22f * alpha), Offset(insetX, sy), Offset(size.width - insetX, sy), 0.9.dp.toPx())
    drawLine(TacticalCyan.copy(alpha = 0.06f * alpha), Offset(insetX, sy + 2f), Offset(size.width - insetX, sy + 2f), 2.4.dp.toPx())

    // Targeting reticle converging on the eye (spread → tight as lockOn → 1).
    val spread = (1f - lockOn) * 30.dp.toPx()
    val rHalf = 16.dp.toPx() + spread
    val arm = 7.dp.toPx()
    val rc = TacticalCyan.copy(alpha = (0.35f + 0.45f * lockOn) * alpha)
    fun bracket(cx: Float, cy: Float, dx: Float, dy: Float) {
        drawLine(rc, Offset(cx, cy), Offset(cx + dx, cy), 1.2.dp.toPx())
        drawLine(rc, Offset(cx, cy), Offset(cx, cy + dy), 1.2.dp.toPx())
    }
    bracket(eye.x - rHalf, eye.y - rHalf,  arm,  arm)
    bracket(eye.x + rHalf, eye.y - rHalf, -arm,  arm)
    bracket(eye.x - rHalf, eye.y + rHalf,  arm, -arm)
    bracket(eye.x + rHalf, eye.y + rHalf, -arm, -arm)
    // Lock ring + ticks once converged.
    if (lockOn > 0.5f) {
        val ringA = (lockOn - 0.5f) / 0.5f
        val rr = 13.dp.toPx()
        val tick = 4.dp.toPx()
        val gc = TacticalGreen.copy(alpha = 0.55f * ringA * alpha)
        drawCircle(gc, rr, eye, style = Stroke(1.dp.toPx()))
        drawLine(gc, Offset(eye.x, eye.y - rr - tick), Offset(eye.x, eye.y - rr + tick), 1.dp.toPx())
        drawLine(gc, Offset(eye.x, eye.y + rr - tick), Offset(eye.x, eye.y + rr + tick), 1.dp.toPx())
        drawLine(gc, Offset(eye.x - rr - tick, eye.y), Offset(eye.x - rr + tick, eye.y), 1.dp.toPx())
        drawLine(gc, Offset(eye.x + rr - tick, eye.y), Offset(eye.x + rr + tick, eye.y), 1.dp.toPx())
    }
}

/** Text read-outs around the subsystem (absolute px positions). */
private fun DrawScope.drawSubsystemLabels(lockOn: Float, eyePulse: Float, alpha: Float) {
    drawTacticalText("CWN ANNWN", 6.dp.toPx(), 22.dp.toPx(), 10f, White.copy(alpha = 0.85f * alpha))
    drawTacticalText("TRK SUBSYS", 6.dp.toPx(), 33.dp.toPx(), 8f, TacticalCyan.copy(alpha = 0.70f * alpha))
    val locked = lockOn > 0.85f
    val lockTxt = if (locked) "TGT LOCK 0.98" else "ACQUIRING"
    val lockCol = if (locked) TacticalGreen else TacticalAmber
    drawTacticalText(
        lockTxt,
        size.width - 92.dp.toPx(),
        22.dp.toPx(),
        9f,
        lockCol.copy(alpha = (0.6f + 0.4f * eyePulse) * alpha),
    )
    drawTacticalText("SENSOR · GPS/IR", 6.dp.toPx(), size.height - 8.dp.toPx(), 8f, TacticalAmber.copy(alpha = 0.55f * alpha))
    drawTacticalText("PURSUIT · VIGILANCE", size.width - 118.dp.toPx(), size.height - 8.dp.toPx(), 8f, MapInk.copy(alpha = 0.45f * alpha))
}

/** A copy of this path translated by ([dx], [dy]) — for the chromatic ghosts. */
private fun Path.shifted(dx: Float, dy: Float): Path =
    Path().apply { addPath(this@shifted, Offset(dx, dy)) }

/** Fractional part, for the data-dissolve shard hashing. */
private fun fract(v: Float): Float = v - floor(v)

// ---------------------------------------------------------------------------
//  Bottom data ticker
// ---------------------------------------------------------------------------

@Composable
private fun DataTicker(alpha: Float) {
    Text(
        text = "▸  PERSISTENT SURVEILLANCE   ◆   RELENTLESS TRACKING   ◆   OPERATIONAL AWARENESS",
        color = MapInk.copy(alpha = 0.55f * alpha),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        letterSpacing = 2.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
