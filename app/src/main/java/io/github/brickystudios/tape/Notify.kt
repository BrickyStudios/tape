package io.github.brickystudios.tape

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Signed percent the same way everywhere, chips, pushes and Setup: "+31.5 %", "−5.2 %" with a narrow no-break space. */
fun percent(change: Double, locale: Locale = Locale.getDefault()): String =
    if (flat(change)) String.format(locale, "%.1f\u202F%%", 0.0) // no "−0.0 %": a flat price is not a loss
    else String.format(locale, "%+.1f\u202F%%", change).replace('-', '−')

/** Rounds to 0.0 %: shown without arrow and in grey. */
fun flat(change: Double) = Math.abs(change) < 0.05

object Notify {
    private const val PRICES = "prices"
    private const val STOCKS = "stocks"
    private const val MARKET = "market"
    private const val WIRE = "wire"
    private const val BRIEF = "brief"
    private const val WIRE_ID = 1
    private const val MARKET_ID = 2
    private const val BRIEF_ID = 3
    const val EXTRA_TAB = "tab"
    const val EXTRA_SYMBOL = "symbol"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(PRICES, ctx.getString(R.string.channel_prices), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(STOCKS, ctx.getString(R.string.channel_stocks), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(MARKET, ctx.getString(R.string.channel_market), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(WIRE, ctx.getString(R.string.channel_wire), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(BRIEF, ctx.getString(R.string.channel_brief), NotificationManager.IMPORTANCE_LOW))
    }

    /** The morning overview, silent: how Europe opened, where the US futures stand, how Asia closed. Opens the Market tab. */
    @SuppressLint("MissingPermission")
    fun brief(ctx: Context, market: List<Quote>, now: Long) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        channels(ctx)
        val by = market.associateBy { it.symbol }
        fun row(symbols: List<String>) = symbols.mapNotNull { s -> by[s]?.let { ctx.getString(MARKET_NAMES.getValue(s).first) + " " + percent(it.change) } }.joinToString("  ·  ")
        // big text, not inbox lines: those cut off after one line, and three indices do not fit
        val all = listOf(Region.EUROPE to listOf("^GDAXI", "^STOXX50E", "^FTSE"), Region.US to listOf("ES=F", "NQ=F"), Region.ASIA to listOf("^N225", "^HSI"))
            .map { (r, s) -> r to row(s) }.filter { it.second.isNotEmpty() }
            .joinToString("\n") { (r, text) -> ctx.getString(r.label) + ":  " + text }
        nm.notify(
            BRIEF_ID,
            base(ctx, BRIEF, now, app(ctx, 1), BRIEF_ID, quiet = true)
                .setContentTitle(ctx.getString(R.string.brief_title))
                .setContentText(row(listOf("^GDAXI", "ES=F", "^N225")))
                .setStyle(NotificationCompat.BigTextStyle().bigText(all))
                .build(),
        )
    }

    /**
     * Everything that rings after one check, each headline once (see [Alerts.decide]).
     * Prices and stock events open the Watchlist or the article, market news the Wire. [quiet]: night, no sound.
     */
    @SuppressLint("MissingPermission") // areNotificationsEnabled is false without POST_NOTIFICATIONS
    fun alerts(ctx: Context, events: List<Pair<Item, Kind>>, market: List<Pair<Item, String>>, moves: List<Pair<Quote, Int>>, quiet: Boolean) {
        val nm = NotificationManagerCompat.from(ctx)
        if ((events.isEmpty() && market.isEmpty() && moves.isEmpty()) || !nm.areNotificationsEnabled()) return
        channels(ctx)
        for ((q, _) in moves) {
            val title = q.symbol + "  " + percent(q.change)
            val text = ctx.getString(R.string.price_text, String.format(Locale.getDefault(), "%.2f", q.price), q.currency, String.format(Locale.getDefault(), "%.2f", q.previousClose))
            nm.notify(("price:" + q.symbol).hashCode(), base(ctx, PRICES, q.time, detail(ctx, q.symbol), q.symbol.hashCode(), quiet).setContentTitle(title).setContentText(text).build())
        }
        // shortcut: at most 5 stock events per check, the rest waits in the list; upgrade to a summary if people miss them
        for ((it, kind) in events.take(5)) {
            nm.notify(
                it.id.hashCode(),
                base(ctx, STOCKS, it.time, detail(ctx, it.tickers.first()), it.id.hashCode(), quiet)
                    .setContentTitle(it.tickers.joinToString("  ") + "  ·  " + ctx.getString(kindLabel(kind)) + "  ·  " + it.source)
                    .setContentText(it.title)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(it.title))
                    .setGroup(STOCKS)
                    .build(),
            )
        }
        if (market.isNotEmpty()) {
            // CPI comes as 3 or 4 lines at once: one push with all of them
            val lines = NotificationCompat.InboxStyle()
            market.forEach { lines.addLine(it.first.title) }
            nm.notify(
                MARKET_ID,
                base(ctx, MARKET, market[0].first.time, app(ctx, 1), MARKET_ID, quiet)
                    .setContentTitle(market[0].first.title)
                    .setContentText(market.joinToString("  ·  ") { it.second })
                    .setStyle(lines)
                    .build(),
            )
        }
    }

    /**
     * At most 3 short lines to read aloud, prices first: "NVDA up 31.5 percent", "NVDA: Huang returns as Nvidia CEO after …".
     * 12 words each keep all three inside [Squawk]'s 20 s. English, like the sources.
     */
    fun spoken(events: List<Pair<Item, Kind>>, market: List<Pair<Item, String>>, moves: List<Pair<Quote, Int>>): List<String> {
        fun short(t: String) = t.split(Regex("\\s+")).take(12).joinToString(" ")
        return (moves.map { (q, _) -> "${q.symbol.substringBefore('.')} ${if (q.change >= 0) "up" else "down"} ${String.format(Locale.US, "%.1f", abs(q.change))} percent" } +
            events.map { (i, _) -> i.tickers.first() + ": " + short(i.title) } + market.map { short(it.first.title) }).take(3)
    }

    fun clearWire(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(WIRE_ID)

    /** One quiet notification that always shows the newest wire headlines. Setup's "Ticker in the notification shade", off by default. */
    @SuppressLint("MissingPermission")
    fun wire(ctx: Context, latest: List<Item>) {
        val nm = NotificationManagerCompat.from(ctx)
        if (latest.isEmpty() || !nm.areNotificationsEnabled()) return
        channels(ctx)
        val time = DateFormat.getTimeFormat(ctx)
        val lines = NotificationCompat.InboxStyle()
        latest.forEach { lines.addLine(time.format(Date(it.time)) + "  " + it.title) }
        nm.notify(
            WIRE_ID,
            base(ctx, WIRE, latest[0].time, app(ctx, 1), WIRE_ID, quiet = false)
                .setContentTitle(latest[0].title)
                .setContentText(latest[0].source)
                .setStyle(lines)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun kindLabel(kind: Kind) = when (kind) {
        Kind.EARNINGS -> R.string.kind_earnings
        Kind.ANALYST -> R.string.kind_analyst
        Kind.DEAL -> R.string.kind_deal
        Kind.MANAGEMENT -> R.string.kind_management
        Kind.LEGAL -> R.string.kind_legal
        Kind.HALT -> R.string.kind_halt
        Kind.CAPITAL -> R.string.kind_capital
        Kind.MOVE -> R.string.kind_move
        Kind.RELEASE -> R.string.kind_release
    }

    /** Opens the stock's detail page: price, chart, the assessment and this headline on top of its news. */
    private fun detail(ctx: Context, symbol: String) = app(ctx, 0).putExtra(EXTRA_SYMBOL, symbol)

    private fun app(ctx: Context, tab: Int) =
        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun base(ctx: Context, channel: String, time: Long, intent: Intent, code: Int, quiet: Boolean) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFF2A93B.toInt())
            .setWhen(time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setSilent(quiet)
            .setContentIntent(PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
}

/** Reads the most important alerts aloud, Setup's "Read alerts aloud". Off by default, never at night. */
object Squawk {
    /** Waits until the last line is spoken, at most 20 s: with the 30 s check the alarm receiver stays inside Android's 60 s. */
    suspend fun say(ctx: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        val ready = CompletableDeferred<Boolean>()
        val done = CompletableDeferred<Unit>()
        val tts = withContext(Dispatchers.Main) { TextToSpeech(ctx.applicationContext) { ready.complete(it == TextToSpeech.SUCCESS) } }
        try {
            withTimeoutOrNull(20_000) {
                if (!ready.await()) return@withTimeoutOrNull
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "last") done.complete(Unit) }
                    @Deprecated("API 21 fallback") override fun onError(id: String?) { done.complete(Unit) }
                })
                lines.forEachIndexed { i, l -> tts.speak(l, TextToSpeech.QUEUE_ADD, null, if (i == lines.lastIndex) "last" else "line$i") }
                done.await()
            }
        } finally {
            tts.shutdown()
        }
    }
}
