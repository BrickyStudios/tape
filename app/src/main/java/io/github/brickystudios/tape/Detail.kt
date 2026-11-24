package io.github.brickystudios.tape

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/**
 * One stock (or market row) in full: price, chart with a finger readout, the day's figures,
 * what the latest event means, and every headline about it.
 */
@Composable
fun Detail(symbol: String, name: String, quote: Quote?, ext: Ext?, earning: Earning?, mention: Mention?, watched: Boolean, plain: Boolean, news: List<Item>, now: Long, threshold: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var range by remember(symbol) { mutableStateOf(Range.D1) }
    var chart by remember(symbol, range) { mutableStateOf<Result<Chart>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var scrub by remember(symbol, range) { mutableStateOf<Int?>(null) }
    LaunchedEffect(symbol, range, attempt) { chart = Wire.chart(symbol, range) }
    val c = chart?.getOrNull()
    val currency = quote?.currency ?: c?.currency.orEmpty()
    fun fmt(v: Double) = if (plain || currency.isEmpty()) Market.number(v) else Market.money(v, currency)

    val event = news.firstNotNullOfOrNull { i -> Alerts.kind(i.title, i.source)?.let { i to it } }
    val status = regionStatus(ctx, quote?.let { Market.region(symbol, it.zone) }, now).orEmpty()
    val after = ext?.takeIf { quote != null && it.time > quote.time }
    val row = MARKET_NAMES[symbol]

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Text1) }
            // market rows by their name ("DAX"), not Yahoo's code ("^GDAXI")
            Text(row?.let { stringResource(it.first) } ?: symbol, fontSize = 16.sp, fontFamily = if (row == null) FontFamily.Monospace else FontFamily.Default, color = Text1)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(row?.let { stringResource(it.second) } ?: (c?.name ?: name).ifEmpty { symbol }, fontSize = 16.sp, color = Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(c?.exchange.orEmpty(), currency, status).filter { it.isNotEmpty() }.joinToString("  ·  "), style = MetaStyle)
                Spacer(Modifier.height(16.dp))
                // the finger on the chart replaces price and change with that moment's value
                val s = scrub?.let { i -> c?.closes?.getOrNull(i)?.let { v -> v to c.times[i] } }
                val shown = s?.first ?: quote?.price ?: c?.price
                Text(shown?.let(::fmt) ?: "—", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = Text1, style = Tnum)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s != null) {
                        // 1 day compares with yesterday's close like the header; longer ranges with their first point
                        val base = (if (range == Range.D1) quote?.previousClose ?: c?.previousClose else null) ?: c?.closes?.firstOrNull() ?: s.first
                        ChangeTag((s.first - base) / base * 100)
                        Spacer(Modifier.size(8.dp))
                        Text(moment(ctx, s.second, range), style = MetaStyle)
                    } else {
                        ChangeTag(quote?.change)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.since_close), style = MetaStyle)
                    }
                }
                if (s == null && after != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (after.pre) R.string.ext_pre else R.string.ext_after) + "  " + fmt(after.price), style = MetaStyle.copy(color = Text1).merge(Tnum))
                        Spacer(Modifier.size(8.dp))
                        ChangeTag(after.change)
                        Spacer(Modifier.size(8.dp))
                        Text(clock(ctx, after.time, now), style = MetaStyle)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            item {
                Box(Modifier.fillMaxWidth().height(220.dp)) {
                    when {
                        chart == null -> Text(stringResource(R.string.chart_loading), Modifier.align(Alignment.Center), style = MetaStyle)
                        c == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.chart_failed, chart?.exceptionOrNull()?.message.orEmpty()), style = MetaStyle)
                            Text(stringResource(R.string.retry), Modifier.clickable { attempt++ }.padding(12.dp), color = Amber, fontSize = 14.sp)
                        }
                        c.closes.size < 2 -> Text(stringResource(R.string.chart_empty), Modifier.align(Alignment.Center), style = MetaStyle)
                        else -> {
                            val pc = if (range == Range.D1) quote?.previousClose ?: c.previousClose else null
                            PriceChart(c, pc, rangeLabel(range), fmt(c.closes.first()), fmt(c.closes.last())) { scrub = it }
                            // the scale: highest and lowest value shown, small and muted at the right edge
                            val hi = maxOf(c.closes.max(), pc ?: c.closes.max())
                            val lo = minOf(c.closes.min(), pc ?: c.closes.min())
                            Text(fmt(hi), Modifier.align(Alignment.TopEnd).background(Ink.copy(alpha = 0.8f)).padding(horizontal = 4.dp), style = MetaStyle.copy(color = Text3, fontFamily = FontFamily.Monospace))
                            Text(fmt(lo), Modifier.align(Alignment.BottomEnd).background(Ink.copy(alpha = 0.8f)).padding(horizontal = 4.dp), style = MetaStyle.copy(color = Text3, fontFamily = FontFamily.Monospace))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Range.entries.forEach { r ->
                        val on = r == range
                        Box(
                            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Raised else Ink).clickable { range = r }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(rangeLabel(r), fontSize = 14.sp, color = if (on) Text1 else Text2) }
                    }
                }
                if (c != null && c.closes.size >= 2 && range != Range.D1) {
                    val first = c.closes.first()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.over_range, rangeLabel(range)), style = MetaStyle)
                        Spacer(Modifier.size(8.dp))
                        ChangeTag((c.closes.last() - first) / first * 100)
                    }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                val day = c?.takeIf { it.dayLow > 0 && it.dayHigh > 0 }?.let { fmt(it.dayLow) + " – " + fmt(it.dayHigh) } ?: "—"
                val year = c?.takeIf { it.yearLow > 0 && it.yearHigh > 0 }?.let { fmt(it.yearLow) + " – " + fmt(it.yearHigh) } ?: "—"
                val prev = (quote?.previousClose ?: c?.previousClose?.takeIf { range == Range.D1 })?.let(::fmt) ?: "—"
                val volume = c?.volume?.takeIf { it > 0 }?.let { Market.compact(it) } ?: "—"
                Row { Figure(R.string.day_range, day, Modifier.weight(1f)); Figure(R.string.previous_close, prev, Modifier.weight(1f)) }
                Row { Figure(R.string.year_range, year, Modifier.weight(1f)); Figure(R.string.volume, volume, Modifier.weight(1f)) }
            }
            if (earning != null || mention != null) item {
                Column(Modifier.padding(top = 16.dp)) {
                    if (earning != null) Figure(R.string.next_earnings, nextEarning(ctx, earning), Modifier)
                    if (mention != null) Figure(R.string.reddit_title, stringResource(R.string.reddit_count, java.text.NumberFormat.getIntegerInstance().format(mention.mentions)) + "  ·  " + mentionTrend(mention), Modifier)
                }
            }
            // indices and stocks off the watchlist have no news search and no price alert
            if (watched) item {
                Text(stringResource(R.string.assessment), style = SectionStyle, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))
                if (event == null) {
                    Text(stringResource(R.string.no_event, symbol), style = MetaStyle, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    val (item, kind) = event
                    val tone = Alerts.tone(item.title)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Raised).clickable { open(ctx, item.link) }.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Notify.kindLabel(kind)), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Text1)
                            if (tone != 0) {
                                Spacer(Modifier.size(8.dp))
                                Text(if (tone > 0) "▲ " + stringResource(R.string.tone_up) else "▼ " + stringResource(R.string.tone_down), fontSize = 13.sp, color = if (tone > 0) Up else Down)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(item.title, style = HeadlineStyle)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(explanation(kind)), style = MetaStyle.copy(color = Text1))
                        Spacer(Modifier.height(8.dp))
                        Text(clock(ctx, item.time, now) + "  ·  " + item.source + "  ·  " + stringResource(R.string.not_advice), style = MetaStyle.copy(color = Text3))
                    }
                }
            }
            if (watched) item { Text(stringResource(R.string.news), style = SectionStyle, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)) }
            if (watched && news.isEmpty()) item { Text(stringResource(R.string.no_news, symbol), style = MetaStyle, modifier = Modifier.padding(vertical = 8.dp)) }
            items(news.take(30), key = { "n:" + it.id }) { HeadlineRow(it, now) }
            if (watched) item { Text(stringResource(R.string.alarm_at, threshold), style = MetaStyle.copy(color = Text3), modifier = Modifier.padding(top = 24.dp)) }
        }
    }
}

/** "Mon 1.12., 22:00, after the close · EPS est. 1.10" */
private fun nextEarning(ctx: android.content.Context, e: Earning): String {
    val day = java.text.SimpleDateFormat("EEE d.M.", java.util.Locale.getDefault()).format(Date(e.time))
    val slot = slotText(ctx, e)
    val time = if (slot != null) android.text.format.DateFormat.getTimeFormat(ctx).format(Date(e.time)) + ", " + slot else ctx.getString(R.string.slot_open)
    return "$day $time" + (e.eps?.let { "  ·  " + ctx.getString(R.string.agenda_eps, String.format(java.util.Locale.getDefault(), "%.2f", it)) } ?: "")
}

@Composable
private fun Figure(label: Int, value: String, modifier: Modifier) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(stringResource(label), style = MetaStyle)
        Text(value, fontSize = 16.sp, color = Text1, style = Tnum, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The line over the chosen range, with yesterday's close dashed (1 day only). A finger on it reports the index under it,
 * lifting reports null; horizontal moves are consumed so the list does not scroll away under the finger.
 */
@Composable
private fun PriceChart(c: Chart, previousClose: Double?, rangeName: String, from: String, to: String, onScrub: (Int?) -> Unit) {
    val p = c.closes
    var at by remember(c) { mutableStateOf<Int?>(null) }
    val describe = stringResource(R.string.chart_description, rangeName, from, to)
    Canvas(
        Modifier.fillMaxSize()
            .semantics { contentDescription = describe }
            .pointerInput(c) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun index(x: Float) = ((x / size.width) * (p.size - 1)).toInt().coerceIn(0, p.size - 1)
                    at = index(down.position.x); onScrub(at)
                    while (true) {
                        val e = awaitPointerEvent()
                        val change = e.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        at = index(change.position.x); onScrub(at)
                    }
                    at = null; onScrub(null)
                }
            },
    ) {
        val lo = minOf(p.min(), previousClose ?: p.min())
        val hi = maxOf(p.max(), previousClose ?: p.max())
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = 8.dp.toPx()
        fun y(v: Double) = (pad + (size.height - 2 * pad) * (1 - (v - lo) / span)).toFloat()
        fun x(i: Int) = size.width * i / (p.size - 1)
        val color = if (p.last() >= (previousClose ?: p.first())) Up else Down
        previousClose?.let { pc ->
            drawLine(Text3, Offset(0f, y(pc)), Offset(size.width, y(pc)), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        val path = Path()
        p.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        at?.let { i ->
            drawLine(Text2, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1.dp.toPx())
            drawCircle(color, 4.dp.toPx(), Offset(x(i), y(p[i])))
        }
    }
}

@Composable
private fun rangeLabel(r: Range) = stringResource(
    when (r) {
        Range.D1 -> R.string.range_1d
        Range.D5 -> R.string.range_5d
        Range.M1 -> R.string.range_1m
        Range.M6 -> R.string.range_6m
        Range.Y1 -> R.string.range_1y
    },
)

/** "15:42" within a day, "Mon 15:42" within five days, the date for longer ranges. */
private fun moment(ctx: android.content.Context, time: Long, range: Range): String {
    val t = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(time))
    return when (range) {
        Range.D1 -> t
        Range.D5 -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(time)) + " " + t
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    }
}

/** One plain sentence per kind of event: what it is and how prices tend to take it. */
fun explanation(kind: Kind) = when (kind) {
    Kind.EARNINGS -> R.string.explain_earnings
    Kind.ANALYST -> R.string.explain_analyst
    Kind.DEAL -> R.string.explain_deal
    Kind.MANAGEMENT -> R.string.explain_management
    Kind.LEGAL -> R.string.explain_legal
    Kind.HALT -> R.string.explain_halt
    Kind.CAPITAL -> R.string.explain_capital
    Kind.MOVE -> R.string.explain_move
    Kind.RELEASE -> R.string.explain_release
}
