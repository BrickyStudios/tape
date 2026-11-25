package io.github.brickystudios.tape

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- plain words, shared by the screens and the morning overview ----

fun placeLabel(ctx: Context, place: String): String = ctx.getString(
    when (place) {
        "USD" -> R.string.c_usd
        "EUR" -> R.string.c_eur
        "DE" -> R.string.c_de
        "FR" -> R.string.c_fr
        "GBP" -> R.string.c_gbp
        "JPY" -> R.string.c_jpy
        "CNY" -> R.string.c_cny
        else -> R.string.c_other
    },
)

/** "Wirtschaftswachstum (BIP), Kern-PCE-Inflation" for one row of releases. */
fun releaseName(ctx: Context, row: List<Indicator>): String = row.map { r ->
    Agenda.speaker(r)?.let { ctx.getString(R.string.ev_speaks, it) } ?: Agenda.nameRes(r)?.let(ctx::getString) ?: r.title
}.distinct().joinToString(", ")

fun slotText(ctx: Context, e: Earning): String? = when (e.slot) {
    "BMO" -> ctx.getString(R.string.slot_bmo)
    "AMC" -> ctx.getString(R.string.slot_amc)
    else -> null
}

/** The calendar's numbers ("0.3%", "225K") in the phone's decimal sign. */
private fun calendarNumber(v: String) = if (Locale.getDefault().language == "de") v.replace('.', ',') else v

/** "Today: 14:30 US economic growth (GDP) · Earnings: MRVL, CRDO", for the morning overview; null on a quiet day. */
fun todayLine(ctx: Context, releases: List<Indicator>, earnings: List<Earning>, favorites: List<Favorite>, now: Long): String? {
    val zone = ZoneId.systemDefault()
    val from = Agenda.dayStart(now, zone)
    val to = Agenda.dayStart(now, zone, 1)
    val time = android.text.format.DateFormat.getTimeFormat(ctx)
    val rel = Agenda.rows(releases.filter { it.time in from until to }).map { row -> time.format(Date(row[0].time)) + " " + placeLabel(ctx, row[0].place) + " " + releaseName(ctx, row) }
    val mine = favorites.mapTo(HashSet()) { it.symbol }
    val reports = earnings.filter { it.time in from until to }.sortedWith(compareByDescending<Earning> { it.symbol in mine }.thenByDescending { it.rank }).take(4).map { it.symbol }
    val parts = rel + listOfNotNull(reports.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.agenda_reports, it.joinToString(", ")) })
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

// ---- Termine tab ----

/** The week as r/wallstreetbets and r/mauerstrassenwetten post it: per day the releases, then your earnings, then the most watched ones. */
fun LazyListScope.agendaItems(releases: List<Indicator>, earnings: List<Earning>, favorites: List<Favorite>, quotes: Map<String, Quote>, now: Long, onOpen: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val mine = favorites.mapTo(HashSet()) { it.symbol }
    var any = false
    // yesterday only for its results: a report after yesterday's close is what today's prices answer
    for (d in -1L..6L) {
        val from = Agenda.dayStart(now, zone, d)
        val to = Agenda.dayStart(now, zone, d + 1)
        val rows = if (d < 0) emptyList() else Agenda.rows(releases.filter { it.time in from until to })
        val day = earnings.filter { it.time in from until to && (d >= 0 || it.actual != null) }
        val yours = day.filter { it.symbol in mine }
        val watched = day.filter { it.symbol !in mine }.sortedByDescending { it.rank }.take(5)
        if (rows.isEmpty() && day.isEmpty()) continue
        item(key = "day:$d") { DayHeader(from, d, first = !any) }
        // by the clock, your stocks first among reports at the same time
        val entries = rows.map { Triple(it[0].time, 0, it as Any) } + yours.map { Triple(it.time, 1, it as Any) } + watched.map { Triple(it.time, 2, it as Any) }
        items(entries.sortedWith(compareBy({ it.first }, { it.second })), key = { (t, kind, v) -> "$kind:$t:" + ((v as? Earning)?.symbol ?: (v as List<*>).size) }) { (_, kind, v) ->
            if (v is Earning) EarningRow(v, yours = kind == 1, quotes[v.symbol]) { onOpen(v.symbol) } else @Suppress("UNCHECKED_CAST") ReleaseRow(v as List<Indicator>, now)
        }
        any = true
    }
    if (!any) item { Text(stringResource(R.string.agenda_empty), style = MetaStyle, modifier = Modifier.padding(vertical = 12.dp)) }
    item { Text(stringResource(R.string.agenda_note), style = MetaStyle.copy(color = Text3), modifier = Modifier.padding(top = 24.dp)) }
}

@Composable
private fun DayHeader(day: Long, offset: Long, first: Boolean) {
    val date = SimpleDateFormat("EEE d.M.", Locale.getDefault()).format(Date(day))
    val label = when (offset) {
        -1L -> stringResource(R.string.yesterday) + "  ·  " + date
        0L -> stringResource(R.string.today) + "  ·  " + date
        1L -> stringResource(R.string.tomorrow) + "  ·  " + date
        else -> date
    }
    Text(label, style = SectionStyle, modifier = Modifier.padding(top = if (first) 8.dp else 32.dp, bottom = 8.dp))
}

/** Clock column like the headlines; amber in the hour before a release, grey once it is out. */
@Composable
private fun ReleaseRow(row: List<Indicator>, now: Long) {
    val ctx = LocalContext.current
    val r = row[0]
    val forecast = if (row.size == 1 && r.forecast.isNotEmpty()) stringResource(R.string.agenda_forecast, calendarNumber(r.forecast), calendarNumber(r.previous.ifEmpty { "—" })) else null
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            android.text.format.DateFormat.getTimeFormat(ctx).format(Date(r.time)), Modifier.width(72.dp), style = ClockStyle, maxLines = 1,
            color = if (r.time - now in 0..HOUR) Amber else Text3,
        )
        Column(Modifier.weight(1f)) {
            Text(releaseName(ctx, row), style = HeadlineStyle.copy(color = if (r.time < now - 30 * MINUTE) Text2 else Text1))
            Spacer(Modifier.height(4.dp))
            Text(listOfNotNull(placeLabel(ctx, r.place), forecast).joinToString("  ·  "), style = MetaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = Hairline)
}

/** One report: your stocks in full white with "Watchlist", the most watched ones in grey. Tapping opens the chart. */
/**
 * A report: before it, when and what is expected; after it, whether the company beat, met or missed the estimate and how the
 * stock has moved since, on the right like a price row. Your stocks move with today's price, the others with the last check.
 */
@Composable
private fun EarningRow(e: Earning, yours: Boolean, quote: Quote?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val slot = slotText(ctx, e)
    val num = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    val eps = e.eps?.let { stringResource(R.string.agenda_eps, num.format(it)) }
    val price = quote?.takeIf { it.time > e.time }?.price ?: e.last
    val since = if (e.base != null && price != null) (price - e.base) / e.base * 100 else null
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        // a date without a time comes as midnight UTC; any other time is when the report came out
        val timed = slot != null || e.time % (24 * HOUR) != 0L
        Text(
            if (timed) android.text.format.DateFormat.getTimeFormat(ctx).format(Date(e.time)) else "—",
            Modifier.width(72.dp), style = ClockStyle, color = Text3, maxLines = 1,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(e.symbol, fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = if (yours) Text1 else Text2)
                Spacer(Modifier.width(8.dp))
                Text(e.name, style = HeadlineStyle.copy(color = if (yours) Text1 else Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            val verdict = e.verdict
            if (verdict != null && e.actual != null) {
                // "beat · 0.34 vs 0.31 per share": the word carries the colour, the figures stay quiet
                val (word, color) = when (verdict) {
                    1 -> stringResource(R.string.result_beat) to Up
                    -1 -> stringResource(R.string.result_miss) to Down
                    else -> stringResource(R.string.result_inline) to Text1
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) { append(word) }
                        // an estimate of exactly 0 is Yahoo having none, not a forecast of zero
                        append("  ·  " + if (e.eps != null && e.eps != 0.0) stringResource(R.string.result_eps, num.format(e.actual), num.format(e.eps)) else num.format(e.actual))
                    },
                    style = MetaStyle.merge(Tnum), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    listOfNotNull(if (yours) stringResource(R.string.tab_watchlist) else null, slot ?: stringResource(R.string.slot_open), eps).joinToString("  ·  "),
                    style = MetaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (since != null) {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                ChangeTag(since)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.since_report), style = MetaStyle.copy(color = Text3), maxLines = 1)
            }
        }
    }
    HorizontalDivider(color = Hairline)
}

// ---- Markt tab: mood, positioning, Reddit ----

/** The VIX in words instead of a green "up": a rising VIX is fear, not good news. */
@Composable
fun VixRow(quote: Quote?, onClick: () -> Unit) {
    val v = quote?.price
    val (word, color) = when {
        v == null -> "—" to Text2
        v < 15 -> stringResource(R.string.vix_calm) to Text2
        v < 20 -> stringResource(R.string.vix_normal) to Text2
        v < 30 -> stringResource(R.string.vix_nervous) to Text1
        else -> stringResource(R.string.vix_panic) to Down
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.m_vix), fontSize = 16.sp, color = Text1)
            Text(stringResource(R.string.m_vix_sub), style = MetaStyle)
        }
        Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End) {
            Text(v?.let { Market.number(it) } ?: "—", fontSize = 16.sp, color = Text1, style = Tnum)
            Spacer(Modifier.height(4.dp))
            Text(word, Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 13.sp, color = color)
        }
    }
    HorizontalDivider(color = Hairline)
}

/** Two sentences on the CFTC numbers, written from the data: who bets which way, how that changed in 4 weeks, and whether it is unusual. */
@Composable
fun PositioningBlock(history: List<Cot>) {
    val managers = Positioning.read(history, Cot::managers) ?: return
    val funds = Positioning.read(history, Cot::funds) ?: return
    val date = runCatching { SimpleDateFormat("d.M.", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(history[0].date)!!) }.getOrDefault(history[0].date)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(stringResource(R.string.cot_title), fontSize = 16.sp, color = Text1)
        Text(stringResource(R.string.cot_sub, date), style = MetaStyle)
        Spacer(Modifier.height(8.dp))
        Text(betSentence(R.string.cot_managers, managers), style = HeadlineStyle)
        Spacer(Modifier.height(8.dp))
        Text(betSentence(R.string.cot_funds, funds), style = HeadlineStyle.copy(color = Text1))
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun betSentence(group: Int, b: Bet): androidx.compose.ui.text.AnnotatedString {
    val n = NumberFormat.getIntegerInstance().format(abs(b.net))
    val way = stringResource(if (b.net >= 0) R.string.cot_up else R.string.cot_down)
    val change = b.change?.let { c ->
        val pct = (abs(c) * 100).roundToInt()
        if (pct == 0) stringResource(R.string.cot_same) else stringResource(if (c > 0) R.string.cot_more else R.string.cot_less, pct)
    }
    val unusual = when {
        b.rank >= 90 -> stringResource(R.string.cot_big)
        b.rank <= 10 -> stringResource(R.string.cot_small)
        else -> null
    }
    val rest = " " + listOfNotNull(stringResource(R.string.cot_sentence, way, n) + (change?.let { ", $it" } ?: "") + ".", unusual).joinToString(" ")
    return androidx.compose.ui.text.buildAnnotatedString {
        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) { append(stringResource(group)) }
        append(rest)
    }
}

/** "4× as often as the day before", "about as often", "less than the day before", "new in the top 100". */
@Composable
fun mentionTrend(m: Mention): String {
    val f = Reddit.factor(m) ?: return stringResource(R.string.reddit_new)
    return when {
        f >= 1.5 -> stringResource(R.string.reddit_more, NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(f))
        f > 0.67 -> stringResource(R.string.reddit_same)
        else -> stringResource(R.string.reddit_less)
    }
}

/** A most-named ticker with its mentions and, like the MSW Daily App, its move today. */
@Composable
fun MentionRow(m: Mention, quote: Quote?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(m.symbol, fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = Text1)
                Spacer(Modifier.width(8.dp))
                Text(m.name, style = MetaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(R.string.reddit_count, NumberFormat.getIntegerInstance().format(m.mentions)) + "  ·  " + mentionTrend(m), style = MetaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End) {
            Text(quote?.let { priceText(it, plain = false) } ?: "—", fontSize = 16.sp, color = Text1, style = Tnum, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            ChangeTag(quote?.change)
        }
    }
    HorizontalDivider(color = Hairline)
}

/** One post: title, then sub, flair, comments and age; opens on reddit.com. */
@Composable
fun PostRow(p: Post, now: Long) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { open(ctx, p.link) }.padding(vertical = 12.dp)) {
        Text(p.title, style = HeadlineStyle, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            listOf("r/" + p.sub, p.flair, stringResource(R.string.reddit_comments, NumberFormat.getIntegerInstance().format(p.comments)), clock(ctx, p.time, now)).joinToString("  ·  "),
            style = MetaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(color = Hairline)
}
