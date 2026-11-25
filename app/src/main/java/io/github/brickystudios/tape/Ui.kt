package io.github.brickystudios.tape

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// A wire desk at night: one graphite ramp (unslop tokens), one amber accent. Amber marks what is minutes old and the primary action.
val Ink = Color(0xFF141415)
val Raised = Color(0xFF232325)
val Text1 = Color(0xFFEDEDED)
val Text2 = Color(0xFFA1A1A6)
/** 4.9:1 on Ink, still readable for 13 sp meta */
val Text3 = Color(0xFF85858B)
val Amber = Color(0xFFF2A93B)
val Hairline = Text1.copy(alpha = 0.08f)
/** Price change only. Leaf green (OKLCH hue 137), clear of Nurvo's teal; 8.7:1 and 4.7:1 on Ink. */
val Up = Color(0xFF7CC45A)
val Down = Color(0xFFE5484D)
/** Tint of the floating glass: Raised, see-through where Android can blur behind it. */
val Glass = Color(0xCC232325)

val AppType = Typography().run { copy(bodyLarge = bodyLarge.copy(letterSpacing = 0.sp)) }
val TitleStyle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = Text1)
val HeadlineStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = Text1)
val MetaStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Text2)
val ClockStyle = TextStyle(fontSize = 13.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace)
/** Tabular figures: prices and percentages line up in their column. */
val Tnum = TextStyle(fontFeatureSettings = "tnum")
val SectionStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = Text2)

/** Headlines this young get the amber clock. */
const val LIVE = 10 * MINUTE

fun open(ctx: Context, link: String) = runCatching {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** "18:41" today, "Mon" this week, "23 Nov" before. */
fun clock(ctx: Context, time: Long, now: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    return when {
        time >= day -> DateFormat.getTimeFormat(ctx).format(Date(time))
        time >= day - 6 * 24 * HOUR -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(time))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time))
    }
}

@Composable
fun Title(text: String, sub: String, clock: Boolean = true) {
    Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
        Text(text, style = TitleStyle)
        Text(sub, style = if (clock) MetaStyle.copy(fontFamily = FontFamily.Monospace, color = Text3) else MetaStyle)
    }
}

/** Clock in its own column, headline, then the stocks it is about and where it comes from. */
@Composable
fun HeadlineRow(item: Item, now: Long, tag: String? = null) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { open(ctx, item.link) }.padding(vertical = 12.dp),
    ) {
        Text(
            clock(ctx, item.time, now),
            Modifier.width(72.dp),
            style = ClockStyle,
            color = if (now - item.time in 0 until LIVE) Amber else Text3,
            maxLines = 1,
        )
        Column(Modifier.weight(1f)) {
            Text(item.title, style = HeadlineStyle, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            // a row that scrolls back into view reads its headline once, not on every pass
            val kind = remember(item.id, item.tickers.isNotEmpty()) { if (item.tickers.isNotEmpty()) Alerts.kind(item.title, item.source) else null }
            val tone = remember(item.id) { Alerts.tone(item.title) }
            val kindLabel = kind?.let { stringResource(Notify.kindLabel(it)) }
            val toneUp = stringResource(R.string.tone_up)
            val toneDown = stringResource(R.string.tone_down)
            Text(
                buildAnnotatedString {
                    if (item.tickers.isNotEmpty()) {
                        withStyle(SpanStyle(color = Text1, fontFamily = FontFamily.Monospace)) { append(item.tickers.joinToString("  ")) }
                        append("  ·  ")
                    }
                    if (kindLabel != null) {
                        withStyle(SpanStyle(color = Text1)) { append(kindLabel) }
                        append("  ·  ")
                        if (tone != 0) {
                            withStyle(SpanStyle(color = if (tone > 0) Up else Down)) { append(if (tone > 0) "▲ " + toneUp else "▼ " + toneDown) }
                            append("  ·  ")
                        }
                    }
                    if (tag != null) {
                        withStyle(SpanStyle(color = Amber)) { append(tag) }
                        append("  ·  ")
                    }
                    append(item.source)
                    // videos and posts say where they come from
                    if (item.id.startsWith("yt:")) append("  ·  YouTube") else if (item.id.startsWith("x:")) append("  ·  X")
                },
                style = MetaStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = Hairline)
}

/** "▲ +31,5 %" on a faint tint of its own colour; the arrow carries the direction for people who cannot tell red from green. */
@Composable
fun ChangeTag(change: Double?, modifier: Modifier = Modifier) {
    if (change == null || change.isNaN()) { Text("—", modifier, style = MetaStyle); return }
    val color = if (flat(change)) Text2 else if (change > 0) Up else Down
    Text(
        (if (flat(change)) "" else if (change > 0) "▲ " else "▼ ") + percent(change),
        modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 13.sp, color = color, style = Tnum, maxLines = 1,
    )
}

/** Today's line, coloured by direction, with yesterday's close as a dashed line when it falls inside the range. */
@Composable
fun Sparkline(points: List<Double>?, previousClose: Double?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = points?.takeIf { it.size >= 2 } ?: return@Canvas
        val lo = minOf(p.min(), previousClose ?: p.min())
        val hi = maxOf(p.max(), previousClose ?: p.max())
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat()
        val base = previousClose ?: p.first()
        val color = if (flat((p.last() - base) / base * 100)) Text2 else if (p.last() > base) Up else Down
        previousClose?.let { pc ->
            drawLine(Text3, Offset(0f, y(pc)), Offset(size.width, y(pc)), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        }
        val path = Path()
        p.forEachIndexed { i, v ->
            val x = size.width * i / (p.size - 1)
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Price as people read it: with currency for stocks, plain for index points and rates. */
fun priceText(q: Quote, plain: Boolean) = if (plain) Market.number(q.price) else Market.money(q.price, q.currency)

/**
 * One row of the watchlist or the market list, 72 dp: who | today's line | price over change.
 * TalkBack reads it as one sentence: "NVIDIA, 165,05 $, ▲ +31,5 %".
 */
@Composable
fun QuoteRow(title: String, subtitle: String, quote: Quote?, points: List<Double>?, plain: Boolean, mono: Boolean, stale: String?, ext: Ext? = null, hint: String? = null, signal: Pair<String, Color>? = null, onClick: (() -> Unit)?) {
    val change = quote?.change
    val after = ext?.takeIf { quote != null && it.time > quote.time }
    val price = quote?.let { priceText(it, plain) } ?: "—"
    val extLabel = after?.let { stringResource(if (it.pre) R.string.ext_pre else R.string.ext_after) + " " + percent(it.change) }.orEmpty()
    val spoken = "$subtitle $title, $price, " + (change?.let { (if (it >= 0) "▲ " else "▼ ") + percent(it) } ?: "") + " $extLabel " + listOfNotNull(signal?.first, hint).joinToString(", ")
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, color = Text1, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MetaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Sparkline(points, quote?.previousClose, Modifier.width(72.dp).height(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End) {
                Text(price, fontSize = 16.sp, color = Text1, style = Tnum, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                if (stale != null) Text(stale, style = MetaStyle.copy(color = Text3), maxLines = 1) else ChangeTag(change)
            }
        }
        // under the whole row, so it does not push the day line aside: a signal (amber to buy, red to sell), the earnings date, after hours on the right
        if (after != null || hint != null || signal != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    if (signal != null) withStyle(SpanStyle(color = signal.second)) { append(signal.first) }
                    if (signal != null && hint != null) append("  ·  ")
                    if (hint != null) append(hint)
                },
                Modifier.weight(1f), style = MetaStyle.copy(color = Text1), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (after != null) ExtLine(after)
        }
    }
    HorizontalDivider(color = Hairline)
}

/** "Europe open · US pre-market · checked 10:12", the line under every page title. What trades now comes first. */
@Composable
fun statusLine(now: Long, checked: Long, checking: Boolean): String {
    val ctx = LocalContext.current
    val market = Market.order(now).mapNotNull { r ->
        when (Market.status(r, now)) {
            MarketStatus.OPEN -> ctx.getString(R.string.region_open, ctx.getString(r.label))
            MarketStatus.PRE -> ctx.getString(R.string.market_pre)
            MarketStatus.AFTER -> ctx.getString(R.string.market_after)
            MarketStatus.CLOSED -> null
        }
    }.joinToString(" · ").ifEmpty { ctx.getString(R.string.markets_closed) }
    val check = when {
        checking -> stringResource(R.string.checking)
        checked > 0 -> stringResource(R.string.checked, clock(ctx, checked, now))
        else -> return market
    }
    return "$market · $check"
}

/** "Europe open" or "US pre-market" for one price, by where it trades; null for futures, currencies and crypto. */
fun regionStatus(ctx: Context, region: Region?, now: Long): String? {
    region ?: return null
    return when (Market.status(region, now)) {
        MarketStatus.OPEN -> ctx.getString(R.string.region_open, ctx.getString(region.label))
        MarketStatus.PRE -> ctx.getString(R.string.market_pre)
        MarketStatus.AFTER -> ctx.getString(R.string.market_after)
        MarketStatus.CLOSED -> ctx.getString(R.string.region_closed, ctx.getString(region.label))
    }
}

/** "15:30" today, "Wed 09:00" on another day, in the phone's time. */
fun whenText(ctx: Context, time: Long, now: Long): String {
    val t = DateFormat.getTimeFormat(ctx).format(Date(time))
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.ROOT).run { format(Date(time)) == format(Date(now)) }
    return if (sameDay) t else SimpleDateFormat("EEE", Locale.getDefault()).format(Date(time)) + " " + t
}

/** "Europe · open until 17:30": the region's name, and in amber while it trades. */
@Composable
fun RegionHeader(region: Region, now: Long, first: Boolean) {
    val ctx = LocalContext.current
    val at = whenText(ctx, Market.nextChange(region, now), now)
    val st = Market.status(region, now)
    val note = stringResource(
        when (st) {
            MarketStatus.OPEN -> R.string.region_open_until
            MarketStatus.PRE -> R.string.region_pre_note
            MarketStatus.AFTER -> R.string.region_after_note
            MarketStatus.CLOSED -> R.string.region_opens
        },
        at,
    )
    Text(
        buildAnnotatedString {
            append(stringResource(region.label))
            withStyle(SpanStyle(color = if (st == MarketStatus.OPEN) Amber else Text3, fontWeight = FontWeight.Normal, fontSize = 13.sp)) { append("  ·  $note") }
        },
        style = SectionStyle,
        modifier = Modifier.padding(top = if (first) 8.dp else 32.dp, bottom = 8.dp),
    )
}

/** "pre-market ▲ +0.3 %" under a US stock's price before the open and after the close, when that trade is newer than the close. */
@Composable
fun ExtLine(ext: Ext) {
    val color = if (flat(ext.change)) Text2 else if (ext.change > 0) Up else Down
    Text(
        buildAnnotatedString {
            append(stringResource(if (ext.pre) R.string.ext_pre else R.string.ext_after) + " ")
            withStyle(SpanStyle(color = color)) { append((if (flat(ext.change)) "" else if (ext.change > 0) "▲ " else "▼ ") + percent(ext.change)) }
        },
        style = MetaStyle.copy(color = Text3).merge(Tnum),
        maxLines = 1,
    )
}

/** iOS-style continuous corners: the curve eases into the straight edge instead of meeting it at a kink. */
val Card: Shape = RoundedRectangle(20.dp)
val Pill: Shape = Capsule()

/** What scrolls behind the floating chrome, for the glass to blur and bend. */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Liquid Glass (github.com/Kyant0/AndroidLiquidGlass, Apache-2.0), as in Freehub: blur from Android 12, refraction at the rim
 * from Android 13. Older phones only get the tint, nearly solid there to keep text readable.
 * Only for chrome that floats over content (tab bar, search, back, update); glass on glass loses all legibility.
 */
@Composable
fun Modifier.glass(shape: Shape = Pill): Modifier {
    val tint = if (Build.VERSION.SDK_INT >= 31) Glass else Raised.copy(alpha = 0.96f)
    val backdrop = LocalBackdrop.current ?: return background(tint, shape)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(20.dp.toPx())
            lens(12.dp.toPx(), 24.dp.toPx())
        },
        onDrawSurface = { drawRect(tint) },
    )
}

/** [content] draws into a layer; [chrome] floats over it as glass that blurs whatever scrolls behind. */
@Composable
fun Glassed(modifier: Modifier = Modifier, chrome: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop), content = content)
        CompositionLocalProvider(LocalBackdrop provides backdrop) { chrome() }
    }
}

/** "Remove animations" in Android's settings: nothing slides, scales or fades. */
@Composable
fun reducedMotion(): Boolean {
    val c = LocalContext.current
    return remember { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** A press sinks the control a little and lets it spring back: the same 100/160 ms as Freehub. */
@Composable
fun Modifier.pressable(source: MutableInteractionSource): Modifier {
    if (reducedMotion()) return this
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, tween(if (pressed) 100 else 160), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}
