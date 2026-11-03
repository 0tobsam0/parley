package de.developaway.parley.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.developaway.parley.Slide
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val CardInk = Color(0xFFF5F4EF)
private val CardDim = Color(0xFF9D9B95)
private val CardFill = Color(0xFF262624)
private val CardStroke = Color(0x14FFFFFF)
private val Chip = Color(0xFF30302E)

/** Pie slices and legend dots: the accent first, then calm neighbours (ios Theme.palette.series). */
private val Series = listOf(Color(0xFFD97757), Color(0xFF5B8DEF), Color(0xFF4EBA65), Color(0xFFD9A43B), Color(0xFF9B87F5), Color(0xFF8B8A85))

/**
 * One slide in the call screen (ios SlideCard): a screenshot, a chart, a big number or a short
 * card — shown while Parley talks about it. It comes in calmly when it becomes current: text and
 * images fade in, bars and lines grow and the pie fills clockwise, numbers count up.
 */
@Composable
fun SlideCard(slide: Slide, image: Bitmap?, index: Int, count: Int, accent: Color, modifier: Modifier = Modifier) {
    val shown = remember(slide.id) { Animatable(0f) }   // fades: text, images, labels
    val grown = remember(slide.id) { Animatable(0f) }   // builds: bars, lines, the pie's sweep, the number
    LaunchedEffect(slide.id) { shown.animateTo(1f, tween(700, delayMillis = 100, easing = LinearOutSlowInEasing)) }
    LaunchedEffect(slide.id) { grown.animateTo(1f, tween(1800, delayMillis = 350, easing = FastOutSlowInEasing)) }

    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.clip(shape)
            .background(CardFill)
            // A warm glow from the corner, like the call screen's backdrop.
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent), center = Offset.Zero, radius = 700f))
            .border(1.dp, CardStroke, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(icon(slide), null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(slide.title ?: kindName(slide), color = CardInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count > 1) {
                Spacer(Modifier.width(8.dp))
                Text("${index + 1} / $count", color = CardDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).background(Chip).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        when (slide.kind) {
            "image" -> if (image != null) {
                Image(image.asImageBitmap(), slide.title, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp).clip(RoundedCornerShape(16.dp))
                        .border(1.dp, CardStroke, RoundedCornerShape(16.dp)).alpha(shown.value))
            } else {
                Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp)).background(Chip), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CardDim, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }
            "chart" -> when (slide.chart) {
                "pie" -> Pie(slide, grown.value, shown.value)
                "line" -> Lines(slide, accent, grown.value, Modifier.fillMaxWidth().height(190.dp))
                else -> Bars(slide, accent, grown.value, Modifier.fillMaxWidth().height(190.dp))
            }
            "metric" -> {
                Text(countUp(slide.metric ?: "", grown.value), color = accent, fontSize = 60.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                slide.note?.let { Text(it, color = CardDim, fontSize = 20.sp, modifier = Modifier.alpha(shown.value)) }
            }
            else -> Text(slide.text ?: "", color = CardInk, fontSize = 20.sp, lineHeight = 27.sp, modifier = Modifier.alpha(shown.value))
        }
    }
}

private fun icon(s: Slide): ImageVector = when (s.kind) {
    "image" -> Icons.Filled.Image
    "chart" -> when (s.chart) { "pie" -> Icons.Filled.PieChart; "line" -> Icons.AutoMirrored.Filled.ShowChart; else -> Icons.Filled.BarChart }
    "metric" -> Icons.Filled.Tag
    else -> Icons.AutoMirrored.Filled.Notes
}

private fun kindName(s: Slide) = when (s.kind) { "image" -> "Bild"; "chart" -> "Diagramm"; "metric" -> "Kennzahl"; else -> "Notiz" }

private fun points(s: Slide): List<Pair<String, Double>> =
    s.values.mapIndexed { i, v -> (s.labels.getOrNull(i) ?: "${i + 1}") to v }

/** "12", "3,5" — German decimals, no trailing zeros. */
private fun format(v: Double): String =
    if (v == v.roundToLong().toDouble()) v.roundToLong().toString()
    else String.format(Locale.GERMANY, "%.2f", v).trimEnd('0').trimEnd(',')

private fun Double.roundToLong() = Math.round(this)

/** "38 %", "<1 %". */
private fun share(f: Double): String = (f * 100).let { if (it < 1) "<1 %" else "${it.roundToInt()} %" }

/** "244" / "24 / 24" / "98,5 %": the leading number counts up from 0 with `progress`, the rest stays. */
private fun countUp(metric: String, progress: Float): String {
    val m = Regex("""^\s*(\d+(?:[.,]\d+)?)(.*)$""").find(metric) ?: return metric
    val raw = m.groupValues[1]
    val number = raw.replace(',', '.').toDoubleOrNull() ?: return metric
    val sep = raw.firstOrNull { it == ',' || it == '.' }
    val v = number * progress
    val shown = if (sep == null) v.roundToInt().toString()
        else String.format(Locale.ROOT, "%.${raw.length - raw.indexOf(sep) - 1}f", v).replace('.', sep)
    return shown + m.groupValues[2]
}

/** Bars on a fixed scale (the marks grow, not the axis), each with its value on top and its label below. */
@Composable
private fun Bars(s: Slide, accent: Color, grow: Float, modifier: Modifier) {
    val pts = points(s)
    if (pts.isEmpty()) return
    val top = max(1.0, (pts.maxOf { it.second }) * 1.18)
    val unit = s.unit?.let { " $it" } ?: ""
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            pts.forEach { (_, v) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(format(v) + unit, color = CardDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.alpha(grow))
                    Spacer(Modifier.height(4.dp))
                    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                        val h = (size.height * (v / top) * grow).toFloat()
                        drawRoundRect(
                            Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.75f)), startY = size.height - h, endY = size.height),
                            topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = CornerRadius(7.dp.toPx()),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pts.forEach { (label, _) ->
                Text(label, color = CardDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** A line drawn from left to right as `grow` runs, dots on its points, first and last label below. */
@Composable
private fun Lines(s: Slide, accent: Color, grow: Float, modifier: Modifier) {
    val pts = points(s)
    if (pts.isEmpty()) return
    val lo = pts.minOf { it.second }.coerceAtMost(0.0)
    val hi = max(lo + 1, pts.maxOf { it.second } * 1.12)
    val unit = s.unit?.let { " $it" } ?: ""
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)) {
            val n = pts.size
            fun at(i: Int) = Offset(if (n == 1) size.width / 2 else size.width * i / (n - 1),
                (size.height * (1 - (pts[i].second - lo) / (hi - lo))).toFloat())
            val reach = grow * (n - 1)   // how far along the line is drawn, in points
            val path = Path().apply {
                moveTo(at(0).x, at(0).y)
                for (i in 1 until n) {
                    if (i - 1 >= reach) break
                    val f = (reach - (i - 1)).coerceAtMost(1f)
                    val a = at(i - 1); val b = at(i)
                    lineTo(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                }
            }
            drawPath(path, accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            for (i in 0 until n) if (i <= reach + 0.001f) drawCircle(accent, 4.dp.toPx(), at(i))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(pts.first().first, color = CardDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${format(pts.last().second)}$unit · ${pts.last().first}", color = CardDim, fontSize = 12.sp, modifier = Modifier.alpha(grow))
        }
    }
}

/** A ring that fills clockwise from the top, with our own legend (name, share) beside it. */
@Composable
private fun Pie(s: Slide, grow: Float, shown: Float) {
    val pts = points(s)
    if (pts.isEmpty()) return
    val total = max(1e-9, pts.sumOf { it.second })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(150.dp)) {
            val ring = 0.4f * size.minDimension / 2   // inner radius at 60 %, as the iPhone
            val inset = ring / 2
            var start = -90f
            val sweepAll = 360f * grow
            pts.forEachIndexed { i, (_, v) ->
                val sweep = (360f * (v / total)).toFloat()
                val drawn = (sweepAll - (start + 90f)).coerceIn(0f, sweep)
                if (drawn > 0f) drawArc(Series[i % Series.size], start + 0.75f, (drawn - 1.5f).coerceAtLeast(0.1f), useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - ring, size.height - ring), style = Stroke(ring))
                start += sweep
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.alpha(shown), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            pts.forEachIndexed { i, (label, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Series[i % Series.size]))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = CardInk, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(share(v / total), color = CardDim, fontSize = 15.sp)
                }
            }
        }
    }
}
