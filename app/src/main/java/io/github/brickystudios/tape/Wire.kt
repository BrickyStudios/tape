package io.github.brickystudios.tape

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

/** Fetches every source, keeps the newest headlines and rings for new ones about the watchlist. No server in between. */
object Wire {
    /** null until read from the store */
    val items = MutableStateFlow<List<Item>?>(null)
    val lastCheck = MutableStateFlow(0L)
    val checking = MutableStateFlow(false)
    private val lock = Mutex()

    fun load(ctx: Context) {
        if (items.value != null) return
        val s = Store(ctx)
        items.value = s.items
        lastCheck.value = s.lastCheck
    }

    /** [stocksNow]: search news for every stock now, not only every 5 minutes (pull to refresh, a new stock). */
    suspend fun check(ctx: Context, stocksNow: Boolean = false) = lock.withLock {
        checking.value = true
        try {
            withContext(Dispatchers.IO) { withTimeoutOrNull(40_000) { run(ctx.applicationContext, stocksNow) } }
        } finally {
            checking.value = false
        }
    }

    private suspend fun run(ctx: Context, stocksNow: Boolean) = coroutineScope {
        val store = Store(ctx)
        val now = System.currentTimeMillis()
        val favorites = store.favorites
        val stocks = stocksNow || now - store.lastStocks >= News.STOCK_INTERVAL
        val wire = store.feeds.filter { it.on }.map { f -> async { f.url to runCatching { News.parseRss(get(f.url), f.name) }.getOrNull() } }
        val perStock = if (stocks) favorites.map { f -> async { stockNews(f) } } else emptyList()
        val answers = (wire + perStock).awaitAll()
        if (stocks) store.lastStocks = now

        val old = store.items
        val known = old.mapTo(HashSet()) { it.id }
        val primed = store.primed.toMutableSet()
        val ring = mutableListOf<Item>()
        var wireMoved = false
        for ((source, got) in answers) {
            if (got == null) continue
            if (source !in primed) { primed += source; continue }
            for (item in News.fresh(got, known, now).map { News.tag(it, favorites) }) {
                if (item.tickers.isNotEmpty()) ring += item else if (item.wire) wireMoved = true
            }
        }
        // stock search results that name none of the stocks are noise, not kept
        val kept = answers.flatMap { it.second.orEmpty() }.filter { it.wire || News.tag(it, favorites).tickers.isNotEmpty() }
        val merged = News.merge(old, kept)
        store.items = merged
        store.primed = primed
        store.lastCheck = now
        items.value = merged
        lastCheck.value = now
        Notify.stocks(ctx, News.merge(emptyList(), ring))
        if (wireMoved) Notify.wire(ctx, merged.filter { it.wire }.take(5))
    }

    /** Yahoo first; Google News when Yahoo refuses or has nothing. Each under its own key, so a switch does not ring. */
    private fun stockNews(f: Favorite): Pair<String, List<Item>?> {
        val yahoo = runCatching { News.parseStock(get(News.stockUrl(f.symbol))) }.getOrNull()
        if (!yahoo.isNullOrEmpty()) return "stock:" + f.symbol to yahoo
        val google = runCatching { News.parseRss(get(News.googleUrl(f)), "Google News") }.getOrNull()
        return "google:" + f.symbol to google?.map { it.copy(wire = false) }
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

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        // an honest name; Yahoo answers 429 to requests without any
        c.setRequestProperty("User-Agent", "Tape/${BuildConfig.VERSION_NAME} (Android; +https://github.com/BrickyStudios/tape)")
        try {
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
                Wire.check(ctx)
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
