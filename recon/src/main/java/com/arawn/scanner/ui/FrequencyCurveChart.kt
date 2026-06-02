package com.arawn.scanner.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arawn.scanner.WifiObservation
import kotlinx.coroutines.launch

/**
 * Live RF spectrum view for ARAWN.
 *
 * Renders each Wi-Fi observation as a channel-envelope parabola centred on its
 * [WifiObservation.frequencyMhz], with the apex height scaled to its
 * [WifiObservation.rssiDbm]. This is the same idiom a Wi-Fi analyzer uses to
 * show the local channel landscape — purely a read-only redraw of the same
 * passive snapshot the console already prints.
 *
 * The composable is self-contained: hand it the latest list of observations and
 * it filters to the selected [SpectrumBand], maps the maths, and redraws. As new
 * [com.arawn.scanner.ScanPacket]s arrive the list changes, each curve's apex
 * animates toward the new reading, and the canvas recomposes in real time.
 *
 * Axis labels are drawn with the native Android [Paint] text API rather than the
 * Compose on-canvas text APIs: the latter proved fragile across runtime versions
 * and could hard-crash the tab. The whole draw pass is additionally guarded so a
 * visualization fault can never force-close the app.
 */

/** The three numeric frequency windows the chart can display. */
enum class SpectrumBand(
    val label: String,
    val minMhz: Int,
    val maxMhz: Int,
) {
    BAND_A("2.4 GHz", 2400, 2500),
    BAND_B("5 GHz", 4900, 5900),
    BAND_C("6 GHz", 5900, 7100),
    ;

    /** A frequency belongs to this band when its centre falls inside the window. */
    fun contains(frequencyMhz: Int): Boolean = frequencyMhz in minMhz..maxMhz
}

// RSSI plotting envelope: the vertical axis runs from a weak floor up to a
// strong ceiling. Real-world Wi-Fi RSSI lives comfortably inside this range.
private const val RSSI_FLOOR_DBM = -100f
private const val RSSI_CEILING_DBM = -30f

// Each curve is drawn as a channel envelope ~40 MHz wide (a 20 MHz half-width),
// mirroring a real Wi-Fi channel footprint. Curves on the same frequency overlap
// and stay legible thanks to the semi-transparent stroke.
private const val CURVE_HALF_WIDTH_MHZ = 20f
private const val CURVE_SAMPLES = 40

// Theme — kept in step with the operator terminal in MainActivity.
private val PanelBlack = Color(0xFF0A0A0A)
private val GridLine = Color(0xFF2A2A2A)
private val AxisInk = Color(0xFF6E6E6E)
private val Amber = Color(0xFFE0B341)
private val Dim = Color(0xFF4A4A4A)

// Distinct hues cycled per access point so neighbouring curves separate by colour
// as well as position. Alpha is applied at draw time to keep overlaps readable.
private val CurvePalette = listOf(
    Color(0xFF35D07F), // green
    Color(0xFFE0B341), // amber
    Color(0xFF4FC3F7), // cyan
    Color(0xFFEF5350), // red
    Color(0xFFAB47BC), // purple
    Color(0xFF26C6DA), // teal
)

@Composable
fun FrequencyCurveChart(
    observations: List<WifiObservation>,
    modifier: Modifier = Modifier,
) {
    var band by remember { mutableStateOf(SpectrumBand.BAND_A) }

    // The set actually drawn: only APs whose centre lands in the active window.
    val visible = observations.filter { band.contains(it.frequencyMhz) }

    // Per-AP animated apex fraction (0f at the floor → 1f at the ceiling). Keyed
    // by BSSID so a given AP keeps animating smoothly as its RSSI drifts, and so
    // APs that drop out of range are forgotten.
    val animated = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(visible) {
        val present = visible.associateBy { it.bssid }
        (animated.keys - present.keys).forEach { animated.remove(it) }
        present.forEach { (bssid, obs) ->
            val target = rssiFraction(obs.rssiDbm)
            val anim = animated.getOrPut(bssid) { Animatable(target) }
            launch { anim.animateTo(target, animationSpec = tween(durationMillis = 450)) }
        }
    }

    Column(modifier = modifier) {
        // Minimalist text tab selector across the top.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpectrumBand.entries.forEach { b ->
                BandTab(
                    band = b,
                    selected = b == band,
                    modifier = Modifier.weight(1f),
                    onClick = { band = b },
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PanelBlack, RoundedCornerShape(6.dp))
                .padding(8.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // A visualization must never take the app down. If any draw call
                // throws on a given device/runtime, log it and leave the panel
                // blank rather than crashing the activity.
                try {
                    drawGrid(band)
                    visible.forEach { obs ->
                        val apex = animated[obs.bssid]?.value ?: rssiFraction(obs.rssiDbm)
                        drawSignalCurve(
                            band = band,
                            centreMhz = obs.frequencyMhz.toFloat(),
                            apexFraction = apex,
                            color = CurvePalette[(obs.bssid.hashCode() and Int.MAX_VALUE) % CurvePalette.size],
                        )
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("FrequencyCurveChart", "spectrum draw failed", t)
                }
            }

            if (visible.isEmpty()) {
                Text(
                    text = "// no signals in ${band.label}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${visible.size} AP in ${band.label}  ·  ${observations.size} total",
            color = AxisInk,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun BandTab(
    band: SpectrumBand,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = band.label,
        color = if (selected) Amber else AxisInk,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        modifier = modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) Color(0xFF161616) else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .padding(vertical = 6.dp),
    )
}

// ---------------------------------------------------------------------------
//  Drawing
// ---------------------------------------------------------------------------

/** Maps an RSSI reading to a 0f..1f apex fraction (clamped to the envelope). */
private fun rssiFraction(rssiDbm: Int): Float =
    ((rssiDbm - RSSI_FLOOR_DBM) / (RSSI_CEILING_DBM - RSSI_FLOOR_DBM)).coerceIn(0f, 1f)

/** Inset that leaves room for the dBm axis (left) and frequency axis (bottom). */
private val DrawScope.plotLeft get() = 40f
private val DrawScope.plotRight get() = size.width
private val DrawScope.plotTop get() = 4f
private val DrawScope.plotBottom get() = size.height - 18f

/** Horizontal pixel for a frequency within the active band. */
private fun DrawScope.freqToX(band: SpectrumBand, mhz: Float): Float {
    val span = (band.maxMhz - band.minMhz).toFloat()
    val t = ((mhz - band.minMhz) / span).coerceIn(0f, 1f)
    return plotLeft + t * (plotRight - plotLeft)
}

/** Vertical pixel for an apex fraction (1f = ceiling/top, 0f = floor/baseline). */
private fun DrawScope.fractionToY(fraction: Float): Float =
    plotBottom - fraction.coerceIn(0f, 1f) * (plotBottom - plotTop)

/** Builds the monospace axis-label paint, sized in the current density. */
private fun DrawScope.axisLabelPaint(): Paint = Paint().apply {
    color = AxisInk.toArgb()
    textSize = 9.sp.toPx()
    typeface = Typeface.MONOSPACE
    isAntiAlias = true
}

/** Draws the bounding grid, dBm rows, and frequency ticks (native-canvas text). */
private fun DrawScope.drawGrid(band: SpectrumBand) {
    val labelPaint = axisLabelPaint()
    val canvas = drawContext.canvas.nativeCanvas

    // Horizontal RSSI gridlines every 10 dBm, with labels on the left.
    var dbm = RSSI_CEILING_DBM
    while (dbm >= RSSI_FLOOR_DBM) {
        val y = fractionToY(rssiFraction(dbm.toInt()))
        drawLine(GridLine, start = Offset(plotLeft, y), end = Offset(plotRight, y), strokeWidth = 1f)
        canvas.drawText("${dbm.toInt()}", 2f, y + 3f, labelPaint)
        dbm -= 10f
    }

    // Vertical frequency gridlines: 4 interior divisions + the two edges.
    val divisions = 4
    for (i in 0..divisions) {
        val mhz = band.minMhz + (band.maxMhz - band.minMhz) * i / divisions
        val x = freqToX(band, mhz.toFloat())
        drawLine(GridLine, start = Offset(x, plotTop), end = Offset(x, plotBottom), strokeWidth = 1f)
        canvas.drawText("$mhz", x + 2f, plotBottom + 12f, labelPaint)
    }
}

/**
 * Draws one access point as a parabolic channel envelope centred on its
 * frequency. The parabola rises from the baseline to [apexFraction] of full
 * height at the centre and falls back to the baseline at ±[CURVE_HALF_WIDTH_MHZ].
 * A faint fill plus a semi-transparent stroke keeps overlapping curves legible.
 */
private fun DrawScope.drawSignalCurve(
    band: SpectrumBand,
    centreMhz: Float,
    apexFraction: Float,
    color: Color,
) {
    if (apexFraction <= 0f) return

    val baselineY = plotBottom
    val path = Path()
    var first = true
    for (i in 0..CURVE_SAMPLES) {
        val mhz = centreMhz - CURVE_HALF_WIDTH_MHZ +
            (2f * CURVE_HALF_WIDTH_MHZ) * i / CURVE_SAMPLES
        // Parabola: 1 at the centre, 0 at the half-width edges.
        val t = (mhz - centreMhz) / CURVE_HALF_WIDTH_MHZ
        val heightFrac = (1f - t * t).coerceAtLeast(0f) * apexFraction
        val x = freqToX(band, mhz)
        val y = fractionToY(heightFrac)
        if (first) {
            path.moveTo(x, y)
            first = false
        } else {
            path.lineTo(x, y)
        }
    }

    // Close the area down to the baseline for the translucent fill.
    val fill = Path().apply {
        addPath(path)
        lineTo(freqToX(band, centreMhz + CURVE_HALF_WIDTH_MHZ), baselineY)
        lineTo(freqToX(band, centreMhz - CURVE_HALF_WIDTH_MHZ), baselineY)
        close()
    }
    drawPath(fill, color = color.copy(alpha = 0.10f))
    drawPath(path, color = color.copy(alpha = 0.55f), style = Stroke(width = 2f))
}
