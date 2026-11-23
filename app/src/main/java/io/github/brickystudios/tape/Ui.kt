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
            val kind = if (item.tickers.isNotEmpty()) Alerts.kind(item.title, item.source) else null
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
                        val tone = Alerts.tone(item.title)
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
    val color = if (change >= 0) Up else Down
    Text(
        (if (change >= 0) "▲ " else "▼ ") + percent(change),
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
        val color = if (p.last() >= (previousClose ?: p.first())) Up else Down
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
fun QuoteRow(title: String, subtitle: String, quote: Quote?, points: List<Double>?, plain: Boolean, mono: Boolean, stale: String?, onClick: (() -> Unit)?) {
    val change = quote?.change
    val price = quote?.let { priceText(it, plain) } ?: "—"
    val spoken = "$subtitle $title, $price, " + (change?.let { (if (it >= 0) "▲ " else "▼ ") + percent(it) } ?: "")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    HorizontalDivider(color = Hairline)
}

/** "US market open · checked 15:42", the line under every page title. */
@Composable
fun statusLine(now: Long, checked: Long, checking: Boolean): String {
    val ctx = LocalContext.current
    val market = stringResource(
        when (Market.status(now)) {
            MarketStatus.OPEN -> R.string.market_open
            MarketStatus.PRE -> R.string.market_pre
            MarketStatus.AFTER -> R.string.market_after
            MarketStatus.CLOSED -> R.string.market_closed
        },
    )
    val check = when {
        checking -> stringResource(R.string.checking)
        checked > 0 -> stringResource(R.string.checked, clock(ctx, checked, now))
        else -> return market
    }
    return "$market  ·  $check"
}
