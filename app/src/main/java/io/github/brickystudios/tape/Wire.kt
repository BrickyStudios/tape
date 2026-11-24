package io.github.brickystudios.tape

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** One fetch: its key for "first answer fills quietly", its items, and each source's result for the status under Setup. */
private class Answer(val key: String, val items: List<Item>?, val checks: List<Pair<String, String?>>)

private fun reason(e: Throwable) = e.message ?: e.javaClass.simpleName

/** Fetches every source, keeps the newest headlines and prices, and rings for what matters. No server in between. */
object Wire {
    const val PRICES = "Yahoo Finance prices"
    private const val YAHOO = "Yahoo Finance news"
    private const val GOOGLE = "Google News"

    /** null until read from the store */
    val items = MutableStateFlow<List<Item>?>(null)
    val quotes = MutableStateFlow<List<Quote>>(emptyList())
    val market = MutableStateFlow<List<Quote>>(emptyList())
    val points = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val ext = MutableStateFlow<Map<String, Ext>>(emptyMap())

    /** The app is on screen: market rows every check, otherwise every 5 minutes (they never ring, and 17 rows cost data). */
    @Volatile var foreground = false
    val lastCheck = MutableStateFlow(0L)
    val checking = MutableStateFlow(false)
    private val lock = Mutex()

    fun load(ctx: Context) {
        if (items.value != null) return
        val s = Store(ctx)
        items.value = s.items
        quotes.value = s.quotes
        market.value = s.market
        points.value = s.points
        ext.value = s.ext
        lastCheck.value = s.lastCheck
    }

    /**
     * [stocksNow]: search news for every stock now, not only every 5 minutes (pull to refresh, a new stock).
     * Returns what to read aloud; the caller speaks it after the lock is free (see [Squawk]).
     */
    suspend fun check(ctx: Context, stocksNow: Boolean = false): List<String> = lock.withLock {
        checking.value = true
        try {
            // 30 s leave room for reading aloud inside the alarm receiver's 60 s
            withContext(Dispatchers.IO) { withTimeoutOrNull(30_000) { run(ctx.applicationContext, stocksNow) } }.orEmpty()
        } finally {
            checking.value = false
        }
    }

    /*
     * One check:  feeds ─┐
     *       stock news ──┼─► new headlines ─► tag ─► event about a stock?   ─► Notify (stocks)
     *     (every 5 min) ─┘                         alert word on the wire? ─► Notify (market, bundled)
     *       prices ─► step crossed today? ─────────────────────────────────► Notify (prices)
     * A source's first answer only fills the list (primed), so adding a stock or feed does not ring.
     */
    private suspend fun run(ctx: Context, stocksNow: Boolean): List<String> = coroutineScope {
        val store = Store(ctx)
        val now = System.currentTimeMillis()
        val favorites = store.favorites
        val stocks = stocksNow || now - store.lastStocks >= News.STOCK_INTERVAL
        val feeds = store.feeds.filter { it.on }.map { f ->
            async {
                val r = runCatching { News.parseRss(get(f.url), f.name) }
                Answer(f.url, r.getOrNull(), listOf(f.name to r.exceptionOrNull()?.let(::reason)))
            }
        }
        // Seeking Alpha knows US symbols only; "SAP.DE" or "ASML.AS" would just fail
        val perStock = if (stocks) favorites.flatMap { f -> listOfNotNull(async { stockNews(f) }, if ('.' in f.symbol) null else async { seekingAlpha(f) }) } else emptyList()
        // one price request for your stocks (and the market rows), the small day lines every 5 minutes with the stock news
        val symbols = (favorites.map { it.symbol } + MARKET).distinct()
        val withMarket = stocks || foreground
        val prices = async { runCatching { (if (withMarket) symbols else favorites.map { it.symbol }).chunked(20).flatMap { c -> News.parseSpark(get(News.sparkUrl(c))) } } }
        val lines = if (stocks) async { runCatching { symbols.chunked(20).map { c -> get(Market.pointsUrl(c)) } } } else null
        val answers = (feeds + perStock).awaitAll()
        val all = prices.await()
        val fav = favorites.mapTo(HashSet()) { it.symbol }
        val priced = if (favorites.isEmpty()) null else all.map { q -> q.filter { it.symbol in fav } }
        val marketGot = if (withMarket) all.getOrNull()?.filter { it.symbol in MARKET } else null
        val linesJson = lines?.await()?.getOrNull()
        val linesGot = linesJson?.fold(emptyMap<String, List<Double>>()) { m, j -> m + Market.parsePoints(j) }
        val extGot = linesJson?.fold(emptyMap<String, Ext>()) { m, j -> m + Market.parseExt(j) }
        if (stocks) store.lastStocks = now

        val status = store.status.toMutableMap()
        answers.flatMap { it.checks }.groupBy({ it.first }, { it.second }).forEach { (name, results) ->
            val failed = results.filterNotNull()
            status[name] = Check(now, failed.firstOrNull()?.let { if (results.size > 1) "$it (${failed.size} of ${results.size})" else it })
        }
        status[PRICES] = Check(now, all.exceptionOrNull()?.let(::reason))
        for ((name, c) in status) if (c.time == now && c.error != null) Log.w("Tape", "$name: ${c.error}")

        val old = store.items
        val known = old.mapTo(HashSet()) { it.id }
        val primed = store.primed.toMutableSet()
        val fresh = mutableListOf<Item>()
        for (a in answers) {
            val got = a.items ?: continue
            if (a.key !in primed) primed += a.key else fresh += News.fresh(got, known, now)
        }
        // stock search results that name none of the stocks are noise, not kept
        val kept = answers.flatMap { it.items.orEmpty() }.filter { it.wire || News.tag(it, favorites).tickers.isNotEmpty() }
        val merged = News.merge(old, kept)

        val threshold = store.threshold
        val got = priced?.getOrNull()
        // like news: a stock's first price only sets the mark (adding a stock that is already up 8 % does not ring)
        val (seenPrice, firstPrice) = got.orEmpty().partition { "price:" + it.symbol in primed }
        val moves = Alerts.priceAlerts(seenPrice, threshold, store.rung)
        val marks = firstPrice.map { it to Alerts.step(it.change, threshold) }.filter { it.second > 0 }
        primed += firstPrice.map { "price:" + it.symbol }
        val shown = if (got.isNullOrEmpty()) store.quotes else got // a failed check keeps the last prices on screen
        if (got != null) store.rung = Alerts.keepRung(store.rung, got, threshold, moves + marks)

        store.items = merged
        store.quotes = shown
        if (!marketGot.isNullOrEmpty()) { store.market = marketGot; market.value = marketGot }
        // a US stock has no regular-session line yet in the early pre-market: it keeps yesterday's, which matches its price
        if (!linesGot.isNullOrEmpty()) { val p = store.points.filterKeys { it in symbols } + linesGot; store.points = p; points.value = p }
        if (extGot != null) { store.ext = extGot; ext.value = extGot }
        store.primed = primed
        store.status = status
        store.lastCheck = now
        items.value = merged
        quotes.value = shown
        lastCheck.value = now

        val tagged = News.merge(emptyList(), fresh).map { News.tag(it, favorites) }
        val (heard, heardWords) = Alerts.decide(tagged, store.words)
        val (events, market, rang) = Alerts.pause(heard, heardWords, store.rang, now)
        store.rang = rang
        val quiet = store.quiet && Alerts.quiet(java.time.LocalTime.now().hour)
        Notify.alerts(ctx, events, market, moves, quiet)
        if (store.tickerNote && tagged.any { it.wire }) Notify.wire(ctx, merged.filter { it.wire }.take(5))
        if (store.brief && marketGot != null) Market.briefDue(now, marketGot, store.briefDay)?.let { day ->
            store.briefDay = day
            Notify.brief(ctx, marketGot, now)
        }
        if (quiet || !store.squawk) emptyList() else Notify.spoken(events, market, moves)
    }

    /** Yahoo first; Google News when Yahoo refuses or has nothing. Each under its own key, so a switch does not ring. */
    private fun stockNews(f: Favorite): Answer {
        val yahoo = runCatching { News.parseStock(get(News.stockUrl(f.symbol))) }
        val yahooCheck = YAHOO to yahoo.exceptionOrNull()?.let(::reason)
        yahoo.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return Answer("stock:" + f.symbol, it, listOf(yahooCheck)) }
        val google = runCatching { News.parseRss(get(News.googleUrl(f)), GOOGLE) }
        return Answer("google:" + f.symbol, google.getOrNull()?.map { it.copy(wire = false) }, listOf(yahooCheck, GOOGLE to google.exceptionOrNull()?.let(::reason)))
    }

    /** Alongside Yahoo, not instead: Seeking Alpha has the analyst and insider items, Yahoo the wider press. */
    private fun seekingAlpha(f: Favorite): Answer {
        val r = runCatching { News.parseSeekingAlpha(get(News.seekingAlphaUrl(f.symbol))) }
        return Answer("sa:" + f.symbol, r.getOrNull(), listOf(News.SEEKING_ALPHA to r.exceptionOrNull()?.let(::reason)))
    }

    private val charts = ConcurrentHashMap<String, Pair<Long, Chart>>()

    /** The detail page's chart, at most one request per stock and range every 5 minutes. */
    suspend fun chart(symbol: String, range: Range): Result<Chart> = withContext(Dispatchers.IO) {
        val key = "$symbol|$range"
        charts[key]?.takeIf { System.currentTimeMillis() - it.first < 5 * MINUTE }?.let { return@withContext Result.success(it.second) }
        runCatching { Market.parseChart(get(Market.chartUrl(symbol, range))) ?: throw IOException("no chart data") }
            .onSuccess { charts[key] = System.currentTimeMillis() to it }
    }

    suspend fun lookup(query: String): Result<List<Favorite>> = withContext(Dispatchers.IO) {
        runCatching { News.parseLookup(get(News.lookupUrl(query))) }
    }

    /** null when the address gives headlines, otherwise the reason it cannot be added */
    suspend fun probeFeed(url: String): Int? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext R.string.feed_https
        val n = runCatching { News.parseRss(get(url), "probe").size }.getOrDefault(0)
        if (n == 0) R.string.feed_bad else null
    }

    private const val MAX_BYTES = 2_000_000

    /** Servers that answered 429, and until when Tape leaves them alone. In memory: a restarted app asks once more. */
    private val paused = ConcurrentHashMap<String, Long>()

    fun get(url: String): String {
        val host = URL(url).host
        paused[host]?.let { until ->
            if (System.currentTimeMillis() < until) throw IOException("paused after HTTP 429 until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))}")
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        // an honest name; Yahoo answers 429 to requests without any
        c.setRequestProperty("User-Agent", "Tape/${BuildConfig.VERSION_NAME} (Android; +https://github.com/BrickyStudios/tape)")
        try {
            if (c.responseCode == 429) paused[host] = System.currentTimeMillis() + News.pause(c.getHeaderField("Retry-After"))
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} from ${c.url.host}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("${c.url.host} sent more than 2 MB")
                }
            }
            // shortcut: reads every feed as UTF-8; upgrade if a Latin-1 feed shows broken umlauts
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }
}

/** The wake-up clock: one alarm at a time, set again after every check. */
object Tick {
    fun exact(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun arm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, TickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = News.nextCheck(System.currentTimeMillis())
        // exact needs "Alarms & reminders" (Setup asks for it); without it Android may hold a check back for minutes
        if (exact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync() // Android holds the alarm's wake lock until finish()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Squawk.say(ctx.applicationContext, Wire.check(ctx))
            } finally {
                Tick.arm(ctx)
                done.finish()
            }
        }
    }
}

/** After a reboot, an app update or a newly allowed exact alarm: start the clock again. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Tick.arm(ctx)
}
