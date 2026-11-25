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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import kotlin.math.abs
import kotlin.math.roundToInt
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
fun Detail(
    symbol: String, name: String, quote: Quote?, ext: Ext?, earning: Earning?, mention: Mention?, follow: Favorite?, plain: Boolean,
    news: List<Item>, levels: Levels?, now: Long, threshold: Int, onFollow: (Favorite, Boolean?) -> Unit, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val watched = follow != null
    var range by remember(symbol) { mutableStateOf(Range.D1) }
    var chart by remember(symbol, range) { mutableStateOf<Result<Chart>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var scrub by remember(symbol, range) { mutableStateOf<Int?>(null) }
    LaunchedEffect(symbol, range, attempt) { chart = Wire.chart(symbol, range) }
    val c = chart?.getOrNull()
    // a stock off the list has no stored price: today's chart gives price and yesterday's close (cached, so no second request)
    val dayChart by produceState<Chart?>(null, symbol) { if (quote == null) value = Wire.chart(symbol, Range.D1).getOrNull() }
    val quote = quote ?: dayChart?.let { Quote(symbol, it.price, it.previousClose, it.times.lastOrNull() ?: now, it.zone, it.currency) }
    val title = name.ifEmpty { dayChart?.name ?: c?.name.orEmpty() }
    // off the list: news and levels are asked when the page opens; on the list they come with every check
    val asked by produceState<List<Item>?>(null, symbol, title) { if (!watched && !plain && title.isNotEmpty()) value = Wire.newsFor(Favorite(symbol, title)) }
    val news = if (watched) news else asked.orEmpty()
    val lv by produceState<Result<Levels?>?>(levels?.let { Result.success(it) }, symbol, levels) {
        if (levels == null && !plain) value = Result.success(Wire.levels(symbol))
    }
    val currency = quote?.currency ?: c?.currency.orEmpty()
    fun fmt(v: Double) = if (plain || currency.isEmpty()) Market.number(v) else Market.money(v, currency)

    val event = news.firstNotNullOfOrNull { i -> Alerts.kind(i.title, i.source)?.let { i to it } }
    val status = regionStatus(ctx, quote?.let { Market.region(symbol, it.zone) }, now).orEmpty()
    val after = ext?.takeIf { quote != null && it.time > quote.time }
    val row = MARKET_NAMES[symbol]

    Glassed(Modifier.fillMaxSize(), chrome = {
        Row(Modifier.padding(start = 12.dp, top = 8.dp).glass().padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Text1) }
            // market rows by their name ("DAX"), not Yahoo's code ("^GDAXI")
            Text(row?.let { stringResource(it.first) } ?: symbol, fontSize = 16.sp, fontFamily = if (row == null) FontFamily.Monospace else FontFamily.Default, color = Text1)
        }
    }) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp)) {
            item {
                Text(row?.let { stringResource(it.second) } ?: (c?.name ?: title).ifEmpty { symbol }, fontSize = 16.sp, color = Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                if (!plain) {
                    val me = follow ?: Favorite(symbol, title.ifEmpty { symbol }, c?.exchange.orEmpty())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FollowButton(R.string.follow_watch, R.string.follow_watching, watched, Modifier.weight(1f)) { onFollow(me, if (watched) null else false) }
                        FollowButton(R.string.follow_own, R.string.follow_owned, follow?.own == true, Modifier.weight(1f)) { onFollow(me, follow?.own != true) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
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
            if (!plain) item { ChanceBlock(lv, quote?.price, ::fmt) }
            if (earning != null || mention != null) item {
                Column(Modifier.padding(top = 16.dp)) {
                    if (earning != null) Figure(R.string.next_earnings, nextEarning(ctx, earning), Modifier)
                    if (mention != null) Figure(R.string.reddit_title, stringResource(R.string.reddit_count, java.text.NumberFormat.getIntegerInstance().format(mention.mentions)) + "  ·  " + mentionTrend(mention), Modifier)
                }
            }
            // indices have no news search; stocks off the list ask for theirs when the page opens
            if (!plain) item {
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
            if (!plain) item { Text(stringResource(R.string.news), style = SectionStyle, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)) }
            if (!plain && news.isEmpty()) item {
                Text(stringResource(if (!watched && asked == null) R.string.news_loading else R.string.no_news, symbol), style = MetaStyle, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(news.take(30), key = { "n:" + it.id }) { HeadlineRow(it, now) }
            if (watched) item { Text(stringResource(R.string.alarm_at, threshold), style = MetaStyle.copy(color = Text3), modifier = Modifier.padding(top = 24.dp)) }
        }
    }
}

/** "Watch" / "I own this": quiet until on, then amber like a selection; a tick in the hand confirms. */
@Composable
private fun FollowButton(off: Int, on: Int, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 48.dp).pressable(source).clip(Pill).background(if (active) Amber.copy(alpha = 0.16f) else Raised)
            .clickable(source, indication = null) {
                view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Text(if (active) "✓  " + stringResource(on) else stringResource(off), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (active) Amber else Text1) }
}

/** "4,2 %" without sign: the words around it say the direction. */
private fun pct(v: Double) = String.format(java.util.Locale.getDefault(), "%.1f\u202F%%", abs(v))

/**
 * The rules of thumb against today's price, each with its number: four for a buying chance, three for a sell signal.
 * A rule that holds gets a dot before its label, amber for buying, red for selling.
 */
@Composable
private fun ChanceBlock(levels: Result<Levels?>?, price: Double?, fmt: (Double) -> String) {
    Text(stringResource(R.string.chance_title), style = SectionStyle, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))
    val l = levels?.getOrNull()
    if (l == null || price == null) {
        Text(stringResource(if (levels == null || price == null) R.string.chance_loading else R.string.chance_none), style = MetaStyle, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val signs = Chance.signs(l, price)
    val buy = signs.count { it.buy }
    val sell = signs.size - buy
    Text(stringResource(R.string.chance_count, buy), fontSize = 16.sp, color = if (buy > 0) Amber else Text1)
    Text(stringResource(R.string.sell_count, sell), fontSize = 16.sp, color = if (sell > 0) Down else Text1)
    // the level as the value, how far the price sits from it on the line below, so neither wraps on a phone
    @Composable
    fun side(base: Double) = Chance.distance(price, base).let { d -> stringResource(if (d < 0) R.string.chance_below else R.string.chance_above, pct(d)) }
    fun mark(vararg s: Sign) = s.firstOrNull { it in signs }
    val rsi = l.rsi?.let { r -> stringResource(if (r < Chance.OVERSOLD) R.string.rsi_oversold else if (r > Chance.OVERBOUGHT) R.string.rsi_overbought else R.string.rsi_neutral) }
    val pe = Chance.pe(l, price)
    Spacer(Modifier.height(8.dp))
    Row {
        ChanceFigure(R.string.chance_avg200, l.avg200?.let(fmt) ?: "—", l.avg200?.let { side(it) }, mark(Sign.AVG200, Sign.STRETCHED), Modifier.weight(1f))
        ChanceFigure(R.string.chance_rsi, l.rsi?.roundToInt()?.toString() ?: "—", rsi, mark(Sign.OVERSOLD, Sign.OVERBOUGHT), Modifier.weight(1f))
    }
    Row {
        ChanceFigure(R.string.chance_low, fmt(l.low), side(l.low), mark(Sign.LOW), Modifier.weight(1f))
        ChanceFigure(R.string.chance_high, "−" + pct(Chance.distance(price, l.high).coerceAtMost(0.0)), fmt(l.high), mark(Sign.DRAWDOWN), Modifier.weight(1f))
    }
    Row {
        ChanceFigure(R.string.chance_pe, pe?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "—", if (l.eps != null && l.eps <= 0) stringResource(R.string.pe_loss) else null, mark(Sign.PRICEY), Modifier.weight(1f))
        ChanceFigure(R.string.chance_eps, l.eps?.let(fmt) ?: "—", null, null, Modifier.weight(1f))
    }
    Text(stringResource(R.string.chance_rules), style = MetaStyle.copy(color = Text3), modifier = Modifier.padding(top = 8.dp))
}

/** A figure whose rule holds gets a dot before its label: amber for a buying chance, red for a sell signal. */
@Composable
private fun ChanceFigure(label: Int, value: String, note: String?, sign: Sign?, modifier: Modifier) {
    val color = when { sign == null -> Text2; sign.buy -> Amber; else -> Down }
    Column(modifier.padding(vertical = 8.dp)) {
        Text((if (sign != null) "● " else "") + stringResource(label), style = MetaStyle.copy(color = color))
        Text(value, fontSize = 16.sp, color = Text1, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) Text(note, style = MetaStyle.merge(Tnum), maxLines = 1)
    }
}

/**
 * The rule that leads for a stock's row, with its colour: "Near its 52-week low" in amber, "Overbought, RSI 74" in red.
 * An owned stock names its sell signal first, a watched one its buying chance. Null when no rule holds.
 */
@Composable
fun signalHint(l: Levels?, q: Quote?, own: Boolean): Pair<String, androidx.compose.ui.graphics.Color>? {
    if (l == null || q == null) return null
    val sign = Chance.lead(Chance.signs(l, q.price), own) ?: return null
    val text = when (sign) {
        Sign.LOW -> stringResource(R.string.hint_low)
        Sign.AVG200 -> stringResource(R.string.hint_avg_at)
        Sign.OVERSOLD -> stringResource(R.string.hint_oversold, l.rsi!!.roundToInt())
        Sign.DRAWDOWN -> stringResource(R.string.hint_drawdown, pct(Chance.distance(q.price, l.high)))
        Sign.OVERBOUGHT -> stringResource(R.string.hint_overbought, l.rsi!!.roundToInt())
        Sign.STRETCHED -> stringResource(R.string.hint_stretched, pct(Chance.distance(q.price, l.avg200!!)))
        Sign.PRICEY -> stringResource(R.string.hint_pricey, Chance.pe(l, q.price)!!.roundToInt())
    }
    return text to if (sign.buy) Amber else Down
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
