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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.sin

private val MatteBlack    = Color(0xFF050507)
private val DeepRed       = Color(0xFFB31818)
private val GlowRed       = Color(0xFFFF2A2A)
private val White         = Color(0xFFF0F0F2)
private val MapInk        = Color(0xFFE6E6E6)
private val TacticalAmber = Color(0xFFE0B341)
private val TacticalCyan  = Color(0xFF4FD8E0)
private val TacticalGreen = Color(0xFF35D07F)

/**
 * Position of the hound's eye within the [R.drawable.arawn_hound] asset, as a
 * fraction of the image (measured from the artwork). Every acquisition effect —
 * reticle, lock ring, eye-glow — anchors here, so if the asset is ever re-cropped
 * only these two numbers need updating.
 */
private const val EYE_FX = 0.587f
private const val EYE_FY = 0.583f

/**
 * Cold-start boot screen. The [R.drawable.arawn_hound] artwork is the hero,
 * displayed directly (never redrawn) and centred on matte black; live ISR/HUD
 * effects are layered over and around it — a sensor sweep, signal-processing
 * texture, telemetry brackets, a target reticle that converges on the hound's
 * eye, a lock-on pulse as initialisation completes, and a controlled red
 * eye-glow synchronised to the lock. Holds ~3s, then calls [onComplete]; a tap
 * anywhere skips the remaining hold.
 */
@Composable
fun BootScreen(onComplete: () -> Unit) {
    val posterAlpha = remember { Animatable(0f) }
    val hudAlpha    = remember { Animatable(0f) }
    val statusAlpha = remember { Animatable(0f) }
    val lockOn      = remember { Animatable(0f) }   // reticle convergence + eye-glow ramp
    val lockPulse   = remember { Animatable(0f) }   // one-shot lock-on snap
    val progress    = remember { Animatable(0f) }
    val readyAlpha  = remember { Animatable(0f) }

    // Continuous, deliberate sensor motion.
    val infinite = rememberInfiniteTransition(label = "boot")
    val scanPhase by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
        label = "scan",
    )
    val sweep by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val glitch by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "glitch",
    )
    val noise by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(140, easing = LinearEasing), RepeatMode.Restart),
        label = "noise",
    )
    val eyePulse by infinite.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "eye",
    )

    LaunchedEffect(Unit) {
        posterAlpha.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        hudAlpha.animateTo(1f, tween(360))
        statusAlpha.animateTo(1f, tween(200))
        progress.animateTo(0.7f, tween(440, easing = LinearEasing))   // subsystems initialising
        lockOn.animateTo(1f, tween(640, easing = FastOutSlowInEasing)) // reticle converges on the eye
        progress.animateTo(1f, tween(240, easing = LinearEasing))      // initialisation completes
        lockPulse.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) // lock-on snap
        readyAlpha.animateTo(1f, tween(160))
        delay(260)
        onComplete()
    }

    val painter = painterResource(R.drawable.arawn_hound)
    val imgAspect = painter.intrinsicSize.let { s ->
        if (s.width > 0f && s.height > 0f) s.width / s.height else 1024f / 1536f
    }
    val ready = readyAlpha.value > 0.5f

    val skip = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MatteBlack)
            .clickable(interactionSource = skip, indication = null, onClick = onComplete),
    ) {
        // ── Hero: the provided artwork, shown directly (preserve quality) ──────
        Image(
            painter = painter,
            contentDescription = "ARAWN",
            contentScale = ContentScale.Fit,
            alpha = posterAlpha.value,
            modifier = Modifier.fillMaxSize(),
        )

        // ── Live ISR/HUD effects, anchored to the drawn artwork rect ──────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = fittedRect(imgAspect)
            val h = hudAlpha.value
            drawScanlineTexture(rect, h)
            drawDigitalNoise(rect, noise, h)
            drawSensorSweep(rect, scanPhase, h)
            drawTelemetry(rect, sweep, lockOn.value, ready, h)
            drawGlitchShards(rect, glitch, h)
            drawEyeGlow(rect, lockOn.value, lockPulse.value, eyePulse, h)
            drawReticle(rect, lockOn.value, lockPulse.value, ready, h)
            drawScreenFrame(h)
        }

        // ── Below the hound: live boot status + telemetry ─────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to MatteBlack.copy(alpha = 0.78f),
                        1f to MatteBlack,
                    ),
                )
                .padding(top = 28.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BootStatus(progress = progress.value, ready = ready, alpha = statusAlpha.value)
            Spacer(Modifier.height(12.dp))
            DataTicker(alpha = hudAlpha.value)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "v${BuildConfig.VERSION_NAME}   ·   BUILD-${BuildConfig.BUILD_NUMBER}   ·   KEYSTORE: STABLE",
                color = MapInk.copy(alpha = 0.40f * hudAlpha.value),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  Geometry helpers — map the ContentScale.Fit artwork rect + eye anchor
// ---------------------------------------------------------------------------

/** The rect the artwork actually occupies under [ContentScale.Fit] (centred, letterboxed). */
private fun DrawScope.fittedRect(imgAspect: Float): Rect {
    val cw = size.width
    val ch = size.height
    val w: Float
    val h: Float
    if (cw / ch > imgAspect) {        // container wider than the image → fit by height
        h = ch; w = h * imgAspect
    } else {                          // taller than the image → fit by width
        w = cw; h = w / imgAspect
    }
    val l = (cw - w) / 2f
    val t = (ch - h) / 2f
    return Rect(l, t, l + w, t + h)
}

/** A point at fractional ([fx],[fy]) inside this rect. */
private fun Rect.frac(fx: Float, fy: Float) = Offset(left + width * fx, top + height * fy)

/** Four inward-pointing L brackets at the corners of [r]. */
private fun DrawScope.cornerBrackets(r: Rect, arm: Float, color: Color, stroke: Float) {
    drawLine(color, r.topLeft, r.topLeft + Offset(arm, 0f), stroke)
    drawLine(color, r.topLeft, r.topLeft + Offset(0f, arm), stroke)
    drawLine(color, r.topRight, r.topRight + Offset(-arm, 0f), stroke)
    drawLine(color, r.topRight, r.topRight + Offset(0f, arm), stroke)
    drawLine(color, r.bottomLeft, r.bottomLeft + Offset(arm, 0f), stroke)
    drawLine(color, r.bottomLeft, r.bottomLeft + Offset(0f, -arm), stroke)
    drawLine(color, r.bottomRight, r.bottomRight + Offset(-arm, 0f), stroke)
    drawLine(color, r.bottomRight, r.bottomRight + Offset(0f, -arm), stroke)
}

// ---------------------------------------------------------------------------
//  Signal-processing layers
// ---------------------------------------------------------------------------

/** Faint horizontal sensor/CRT scanline texture over the artwork only. */
private fun DrawScope.drawScanlineTexture(rect: Rect, alpha: Float) {
    if (alpha <= 0f) return
    val gap = 3.5.dp.toPx()
    val col = TacticalCyan.copy(alpha = 0.030f * alpha)
    var y = rect.top
    while (y < rect.bottom) {
        drawLine(col, Offset(rect.left, y), Offset(rect.right, y), 1f)
        y += gap
    }
}

/** Sparse flickering digital-noise specks (signal grain). */
private fun DrawScope.drawDigitalNoise(rect: Rect, phase: Float, alpha: Float) {
    if (alpha <= 0f) return
    val step = floor(phase * 14f)
    val r = 1.1.dp.toPx()
    for (i in 0 until 60) {
        val s = i * 1.37f
        val tw = hash(s + step)
        if (tw < 0.80f) continue
        val px = rect.left + hash(s) * rect.width
        val py = rect.top + hash(s + 7.1f) * rect.height
        drawCircle(White.copy(alpha = (tw - 0.80f) / 0.20f * 0.10f * alpha), r, Offset(px, py))
    }
}

/** A single cyan sensor line sweeping top→bottom with a soft trailing band. */
private fun DrawScope.drawSensorSweep(rect: Rect, scanPhase: Float, alpha: Float) {
    if (alpha <= 0f) return
    val y = rect.top + scanPhase * rect.height
    val band = 44.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to TacticalCyan.copy(alpha = 0.05f * alpha),
            startY = y - band,
            endY = y,
        ),
        topLeft = Offset(rect.left, y - band),
        size = Size(rect.width, band),
    )
    drawLine(TacticalCyan.copy(alpha = 0.12f * alpha), Offset(rect.left, y), Offset(rect.right, y), 1.dp.toPx())
    drawLine(TacticalCyan.copy(alpha = 0.04f * alpha), Offset(rect.left, y + 2f), Offset(rect.right, y + 2f), 3.dp.toPx())
}

/** Holographic tracking markers + telemetry read-outs framing the hound. */
private fun DrawScope.drawTelemetry(rect: Rect, sweep: Float, lockOn: Float, ready: Boolean, alpha: Float) {
    if (alpha <= 0f) return
    // Bounding box around the hound head (artwork fractions).
    val hr = Rect(
        rect.left + rect.width * 0.30f,
        rect.top + rect.height * 0.495f,
        rect.left + rect.width * 0.775f,
        rect.top + rect.height * 0.715f,
    )
    cornerBrackets(hr, 12.dp.toPx(), TacticalCyan.copy(alpha = 0.42f * alpha), 1.3.dp.toPx())
    // Acquisition tick travelling along the top edge.
    val tx = hr.left + sweep * hr.width
    drawLine(
        TacticalCyan.copy(alpha = 0.5f * alpha),
        Offset(tx, hr.top - 3.dp.toPx()), Offset(tx, hr.top + 3.dp.toPx()),
        1.2.dp.toPx(),
    )
    drawTacticalText("TGT // CWN-ANNWN", hr.left, hr.top - 10.dp.toPx(), 8.5f, TacticalCyan.copy(alpha = 0.70f * alpha))
    drawTacticalText("CLASS  K9-ISR", hr.left, hr.bottom + 14.dp.toPx(), 7.5f, TacticalCyan.copy(alpha = 0.50f * alpha))
    val conf = if (ready) "TRK CONF 0.98" else "TRK CONF " + "%.2f".format(0.40f + 0.55f * lockOn)
    drawTacticalText(
        conf,
        hr.right - 58.dp.toPx(), hr.bottom + 14.dp.toPx(), 7.5f,
        (if (ready) TacticalGreen else TacticalAmber).copy(alpha = 0.65f * alpha),
    )
}

/** Data-fragment shards dissolving leftward off the hound's dispersion edge. */
private fun DrawScope.drawGlitchShards(rect: Rect, phase: Float, alpha: Float) {
    if (alpha <= 0f) return
    val originX = rect.left + rect.width * 0.42f
    for (k in 0 until 11) {
        val fy = 0.55f + (k / 11f) * 0.11f
        val py = rect.top + fy * rect.height
        val flick = fract(phase * 1.2f + k * 0.41f)
        val drift = flick * rect.width * 0.05f
        val len = (3f + fract(k * 0.7f + phase) * 10f).dp.toPx()
        val x1 = originX - drift
        val x0 = x1 - len
        val col = if (k % 3 == 0) TacticalCyan else White
        drawLine(col.copy(alpha = (1f - flick) * 0.45f * alpha), Offset(x0, py), Offset(x1, py), 1.2.dp.toPx())
    }
}

// ---------------------------------------------------------------------------
//  Acquisition: eye-glow + converging reticle + lock-on
// ---------------------------------------------------------------------------

/** Controlled additive red glow on the hound's eye, ramping with the lock. */
private fun DrawScope.drawEyeGlow(rect: Rect, lockOn: Float, lockPulse: Float, eyePulse: Float, alpha: Float) {
    if (alpha <= 0f) return
    val eye = rect.frac(EYE_FX, EYE_FY)
    val intensity = (0.30f + 0.70f * lockOn) * (0.72f + 0.28f * eyePulse)
    val flash = 4f * lockPulse * (1f - lockPulse)   // 0→1→0 over the lock snap
    val baseR = rect.width * 0.020f
    drawCircle(GlowRed.copy(alpha = (0.42f * intensity + 0.22f * flash) * alpha), baseR * 2.6f, eye, blendMode = BlendMode.Plus)
    drawCircle(GlowRed.copy(alpha = (0.62f * intensity + 0.30f * flash) * alpha), baseR * 1.4f, eye, blendMode = BlendMode.Plus)
    drawCircle(White.copy(alpha = (0.42f * intensity * lockOn + 0.25f * flash) * alpha), baseR * 0.55f, eye, blendMode = BlendMode.Plus)
    if (flash > 0.001f) {
        drawCircle(
            GlowRed.copy(alpha = flash * 0.5f * alpha),
            baseR * 2.2f + lockPulse * rect.width * 0.055f, eye,
            style = Stroke(1.5.dp.toPx()), blendMode = BlendMode.Plus,
        )
    }
}

/** Target reticle that converges onto the eye, then a green lock + snap pulse. */
private fun DrawScope.drawReticle(rect: Rect, lockOn: Float, lockPulse: Float, ready: Boolean, alpha: Float) {
    if (alpha <= 0f) return
    val eye = rect.frac(EYE_FX, EYE_FY)
    val spread = (1f - lockOn) * rect.width * 0.17f
    val rHalf = rect.width * 0.072f + spread
    val arm = rect.width * 0.026f
    val rc = TacticalCyan.copy(alpha = (0.30f + 0.50f * lockOn) * alpha)
    val box = Rect(eye.x - rHalf, eye.y - rHalf, eye.x + rHalf, eye.y + rHalf)
    cornerBrackets(box, arm, rc, 1.3.dp.toPx())
    // Inward crosshair ticks N/E/S/W.
    val tick = rect.width * 0.012f
    drawLine(rc, Offset(eye.x, box.top), Offset(eye.x, box.top + tick), 1.dp.toPx())
    drawLine(rc, Offset(eye.x, box.bottom), Offset(eye.x, box.bottom - tick), 1.dp.toPx())
    drawLine(rc, Offset(box.left, eye.y), Offset(box.left + tick, eye.y), 1.dp.toPx())
    drawLine(rc, Offset(box.right, eye.y), Offset(box.right - tick, eye.y), 1.dp.toPx())
    // Green lock ring fades in as the reticle settles.
    if (lockOn > 0.5f) {
        val ringA = (lockOn - 0.5f) / 0.5f
        drawCircle(TacticalGreen.copy(alpha = 0.60f * ringA * alpha), rect.width * 0.052f, eye, style = Stroke(1.2.dp.toPx()))
    }
    // Lock-on snap: a brief expanding green ring.
    val flash = 4f * lockPulse * (1f - lockPulse)
    if (flash > 0.001f) {
        drawCircle(
            TacticalGreen.copy(alpha = flash * 0.55f * alpha),
            rect.width * (0.052f + lockPulse * 0.05f), eye,
            style = Stroke(1.5.dp.toPx()),
        )
    }
    // Acquisition status flag beside the reticle.
    val col = if (ready) TacticalGreen else TacticalAmber
    val lx = eye.x + rHalf + 10.dp.toPx()
    val ly = eye.y - rHalf + 2.dp.toPx()
    drawCircle(col.copy(alpha = 0.9f * alpha), 2.2.dp.toPx(), Offset(lx - 6.dp.toPx(), ly - 3.dp.toPx()))
    drawTacticalText(if (ready) "TGT LOCK" else "ACQUIRING", lx, ly, 9f, col.copy(alpha = 0.85f * alpha))
}

/** Subtle white corner brackets at the screen edges — command-console framing. */
private fun DrawScope.drawScreenFrame(alpha: Float) {
    if (alpha <= 0f) return
    val pad = 12.dp.toPx()
    val len = 20.dp.toPx()
    val stroke = 1.3.dp.toPx()
    val col = White.copy(alpha = 0.40f * alpha)
    val w = size.width
    val h = size.height
    drawLine(col, Offset(pad, pad), Offset(pad + len, pad), stroke)
    drawLine(col, Offset(pad, pad), Offset(pad, pad + len), stroke)
    drawLine(col, Offset(w - pad, pad), Offset(w - pad - len, pad), stroke)
    drawLine(col, Offset(w - pad, pad), Offset(w - pad, pad + len), stroke)
    drawLine(col, Offset(pad, h - pad), Offset(pad + len, h - pad), stroke)
    drawLine(col, Offset(pad, h - pad), Offset(pad, h - pad - len), stroke)
    drawLine(col, Offset(w - pad, h - pad), Offset(w - pad - len, h - pad), stroke)
    drawLine(col, Offset(w - pad, h - pad), Offset(w - pad, h - pad - len), stroke)
}

// ---------------------------------------------------------------------------
//  Canvas text + hashing helpers
// ---------------------------------------------------------------------------

/** Tactical-style monospace label drawn directly on the canvas. */
private fun DrawScope.drawTacticalText(text: String, xPx: Float, yPx: Float, sizeSp: Float, color: Color) {
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
    val r = (red * 255f).toInt() and 0xff
    val g = (green * 255f).toInt() and 0xff
    val b = (blue * 255f).toInt() and 0xff
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun hash(n: Float): Float = fract(sin(n) * 43758.5453f)
private fun fract(v: Float): Float = v - floor(v)

// ---------------------------------------------------------------------------
//  Boot status + progress (below the hound)
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
            drawLine(
                color = White.copy(alpha = 0.12f * alpha),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = size.height,
            )
            val fillColor = if (ready) TacticalGreen else DeepRed
            drawLine(
                color = fillColor.copy(alpha = 0.90f * alpha),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width * progress, size.height / 2f),
                strokeWidth = size.height,
            )
            val segs = 16
            for (s in 1 until segs) {
                val sx = size.width * (s.toFloat() / segs)
                drawLine(
                    color = MatteBlack,
                    start = Offset(sx, 0f),
                    end = Offset(sx, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

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
