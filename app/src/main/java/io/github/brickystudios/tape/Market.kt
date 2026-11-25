package io.github.brickystudios.tape

import org.json.JSONObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/** A price chart for one stock and range, with the day's figures for the detail page. */
data class Chart(
    val times: List<Long>,
    val closes: List<Double>,
    val price: Double,
    val previousClose: Double,
    val dayHigh: Double,
    val dayLow: Double,
    val yearHigh: Double,
    val yearLow: Double,
    val volume: Long,
    val name: String,
    val exchange: String,
    val currency: String,
    val zone: String = "UTC",
)

/** Chart ranges on the detail page, each with the interval Yahoo serves in a few KB. */
enum class Range(val query: String) {
    D1("range=1d&interval=5m"), D5("range=5d&interval=15m"), M1("range=1mo&interval=1d"), M6("range=6mo&interval=1d"), Y1("range=1y&interval=1wk")
}

enum class MarketStatus { PRE, OPEN, AFTER, CLOSED }

/** Where an index trades, with its regular session in the exchange's own time (minutes after midnight), weekdays. */
enum class Region(val zone: ZoneId, val open: Int, val close: Int, val label: Int) {
    /** Xetra 9:00 to 17:30; London, Paris and Zurich trade the same hours. */
    EUROPE(ZoneId.of("Europe/Berlin"), 9 * 60, 17 * 60 + 30, R.string.region_europe),
    US(ZoneId.of("America/New_York"), 9 * 60 + 30, 16 * 60, R.string.region_us),
    /** Tokyo's open to Hong Kong's close (16:00 there, 17:00 in Tokyo); lunch breaks ignored. */
    ASIA(ZoneId.of("Asia/Tokyo"), 9 * 60, 17 * 60, R.string.region_asia),
}

/** Market overview rows by region. Before the open, the futures show how Wall Street is set to open. */
val MARKET_GROUPS = mapOf(
    Region.EUROPE to listOf("^GDAXI", "^STOXX50E", "^FTSE", "^FCHI", "^SSMI"),
    Region.US to listOf("^GSPC", "^IXIC", "^DJI", "ES=F", "NQ=F"),
    Region.ASIA to listOf("^N225", "^HSI", "000001.SS"),
)

/** Gold, oil, bitcoin, euro: trade around the clock, always last. */
val MARKET_OTHER = listOf("GC=F", "CL=F", "BTC-USD", "EURUSD=X")
/** The VIX, the US market's fear gauge: what MSW asked of its own app as "Fear & Greed" (CNN's index refuses apps). */
const val VIX = "^VIX"
val MARKET = MARKET_GROUPS.values.flatten() + MARKET_OTHER + VIX

/** Name and plain-words subtitle of each market row, as string resources. */
val MARKET_NAMES = mapOf(
    "^GDAXI" to (R.string.m_dax to R.string.m_dax_sub), "^STOXX50E" to (R.string.m_stoxx50 to R.string.m_stoxx50_sub),
    "^FTSE" to (R.string.m_ftse to R.string.m_ftse_sub), "^FCHI" to (R.string.m_cac to R.string.m_cac_sub), "^SSMI" to (R.string.m_smi to R.string.m_smi_sub),
    "^GSPC" to (R.string.m_sp500 to R.string.m_sp500_sub), "^IXIC" to (R.string.m_nasdaq to R.string.m_nasdaq_sub), "^DJI" to (R.string.m_dow to R.string.m_dow_sub),
    "ES=F" to (R.string.m_es to R.string.m_future_sub), "NQ=F" to (R.string.m_nq to R.string.m_future_sub),
    "^N225" to (R.string.m_nikkei to R.string.m_nikkei_sub), "^HSI" to (R.string.m_hsi to R.string.m_hsi_sub), "000001.SS" to (R.string.m_shanghai to R.string.m_shanghai_sub),
    "GC=F" to (R.string.m_gold to R.string.m_gold_sub), "CL=F" to (R.string.m_oil to R.string.m_oil_sub),
    "BTC-USD" to (R.string.m_bitcoin to R.string.m_bitcoin_sub), "EURUSD=X" to (R.string.m_eurusd to R.string.m_eurusd_sub),
    VIX to (R.string.m_vix to R.string.m_vix_sub),
)

/** A US stock's last trade outside the regular session, against the regular close before it. */
data class Ext(val price: Double, val base: Double, val time: Long, val pre: Boolean) {
    val change: Double get() = (price - base) / base * 100
}

/** One symbol's 5-minute closes with their times, and the regular session when the answer has pre-market and after-hours too. */
private class Day(val times: List<Long>, val closes: List<Double>, val regular: LongRange?, val close: Double)

object Market {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun chartUrl(symbol: String, range: Range) = "https://query2.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?${range.query}"

    /** Today's 5-minute closes for the small line next to each row (spark answers up to 20 symbols at once), US stocks with pre-market and after hours. */
    fun pointsUrl(symbols: List<String>) = "https://query2.finance.yahoo.com/v7/finance/spark?symbols=${enc(symbols.joinToString(","))}&range=1d&interval=5m&includePrePost=true"

    /** Closes with their times; gaps (null) are dropped. Null when the answer has no usable price. */
    fun parseChart(json: String): Chart? {
        val r = JSONObject(json).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val m = r.optJSONObject("meta") ?: return null
        val ts = r.optJSONArray("timestamp")
        val cs = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val times = mutableListOf<Long>()
        val closes = mutableListOf<Double>()
        if (ts != null && cs != null) for (i in 0 until minOf(ts.length(), cs.length())) {
            if (cs.isNull(i)) continue
            times += ts.getLong(i) * 1000
            closes += cs.getDouble(i)
        }
        val price = m.optDouble("regularMarketPrice")
        if (!(price > 0)) return null
        return Chart(
            times, closes, price, m.optDouble("chartPreviousClose"), m.optDouble("regularMarketDayHigh"), m.optDouble("regularMarketDayLow"),
            m.optDouble("fiftyTwoWeekHigh"), m.optDouble("fiftyTwoWeekLow"), m.optLong("regularMarketVolume"),
            m.optString("longName").ifEmpty { m.optString("shortName") }.replace(Regex("\\s+"), " ").trim(),
            m.optString("fullExchangeName").ifEmpty { m.optString("exchangeName") }, m.optString("currency"), m.optString("exchangeTimezoneName", "UTC"),
        )
    }

    private fun days(json: String): Map<String, Day> {
        val results = JSONObject(json).optJSONObject("spark")?.optJSONArray("result") ?: return emptyMap()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val r = o.optJSONArray("response")?.optJSONObject(0) ?: return@mapNotNull null
            val cs = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close") ?: return@mapNotNull null
            val ts = r.optJSONArray("timestamp")
            val times = mutableListOf<Long>()
            val closes = mutableListOf<Double>()
            for (k in 0 until cs.length()) {
                if (cs.isNull(k)) continue
                times += (ts?.optLong(k) ?: 0L) * 1000
                closes += cs.getDouble(k)
            }
            val meta = r.optJSONObject("meta")
            // only stocks with pre-market and after-hours trading get the {pre, regular, post} form; indices get a plain array
            val reg = meta?.optJSONObject("tradingPeriods")?.optJSONArray("regular")?.optJSONArray(0)?.optJSONObject(0)
            o.optString("symbol") to Day(times, closes, reg?.let { it.optLong("start") * 1000 until it.optLong("end") * 1000 }, meta?.optDouble("regularMarketPrice") ?: Double.NaN)
        }.filter { it.first.isNotEmpty() }.toMap()
    }

    /** Symbol → the day's closes in the regular session, for the small line next to each row. */
    fun parsePoints(json: String): Map<String, List<Double>> = days(json).mapValues { (_, d) ->
        val reg = d.regular ?: return@mapValues d.closes
        d.closes.filterIndexed { k, _ -> d.times[k] in reg }
    }.filter { it.value.size >= 2 }

    /** Symbol → its last trade before the open or after the close, when that is the newest one. During the session: nothing. */
    fun parseExt(json: String): Map<String, Ext> = days(json).mapNotNull { (s, d) ->
        val reg = d.regular ?: return@mapNotNull null
        val t = d.times.lastOrNull() ?: return@mapNotNull null
        if (t in reg || !(d.close > 0)) null else s to Ext(d.closes.last(), d.close, t, pre = t < reg.first)
    }.toMap()

    private fun weekday(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    // shortcut: fixed hours, no exchange holidays; upgrade when someone asks for a holiday calendar
    /** A region's session now. Only the US has pre-market (from 4:00) and after hours (to 20:00) worth showing. */
    fun status(region: Region, now: Long): MarketStatus {
        val t = Instant.ofEpochMilli(now).atZone(region.zone)
        if (!weekday(t.toLocalDate())) return MarketStatus.CLOSED
        val m = t.hour * 60 + t.minute
        return when {
            m in region.open until region.close -> MarketStatus.OPEN
            region != Region.US -> MarketStatus.CLOSED
            m in 4 * 60 until region.open -> MarketStatus.PRE
            m in region.close until 20 * 60 -> MarketStatus.AFTER
            else -> MarketStatus.CLOSED
        }
    }

    /** Every open and close in a window of days around [now], oldest first. */
    private fun changes(region: Region, now: Long, days: IntRange): List<Long> {
        val today = Instant.ofEpochMilli(now).atZone(region.zone).toLocalDate()
        return days.map { today.plusDays(it.toLong()) }.filter(::weekday).flatMap { d ->
            listOf(region.open, region.close).map { d.atStartOfDay(region.zone).plusMinutes(it.toLong()).toInstant().toEpochMilli() }
        }
    }

    /** When the region next opens or closes. */
    fun nextChange(region: Region, now: Long): Long = changes(region, now, 0..7).first { it > now }

    /** When the region last opened or closed. */
    fun lastChange(region: Region, now: Long): Long = changes(region, now, -7..0).last { it <= now }

    /** What trades now on top: open regions (the latest to open first), then US pre-market or after hours, then the latest to close. */
    fun order(now: Long): List<Region> = Region.entries.sortedWith(
        compareBy<Region> { when (status(it, now)) { MarketStatus.OPEN -> 0; MarketStatus.PRE, MarketStatus.AFTER -> 1; MarketStatus.CLOSED -> 2 } }
            .thenByDescending { lastChange(it, now) },
    )

    /** The region a price trades in, by its exchange's time zone. Null for futures, currencies and crypto: they trade around the clock. */
    fun region(symbol: String, zone: String): Region? = when {
        symbol.endsWith("=F") || symbol.endsWith("=X") || symbol.endsWith("-USD") -> null
        zone.startsWith("Europe/") -> Region.EUROPE
        zone.startsWith("America/") -> Region.US
        zone.startsWith("Asia/") -> Region.ASIA
        else -> null
    }

    /**
     * The day key when the morning overview is due: once per weekday, as soon as the DAX has prices from 10 minutes after the open
     * (Yahoo's European index prices lag about 15 minutes, so this lands around 9:25). A phone that wakes after noon skips the day.
     */
    fun briefDue(now: Long, market: List<Quote>, lastDay: String): String? {
        if (status(Region.EUROPE, now) != MarketStatus.OPEN) return null
        val day = Instant.ofEpochMilli(now).atZone(Region.EUROPE.zone).toLocalDate().toString()
        val open = lastChange(Region.EUROPE, now)
        if (now > open + 3 * HOUR) return null
        return day.takeIf { it != lastDay && market.any { q -> q.symbol == "^GDAXI" && q.time >= open + 10 * MINUTE } }
    }

    /** A price in the phone's format: "165,05 $" in German, "$165.05" in English; an unknown currency falls back to "165.05 XYZ". */
    fun money(value: Double, currency: String, locale: Locale = Locale.getDefault()): String {
        val c = runCatching { Currency.getInstance(currency) }.getOrNull() ?: return number(value, locale) + " " + currency
        return NumberFormat.getCurrencyInstance(locale).apply { this.currency = c; minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(value)
    }

    /** Index points and rates without a currency sign: 7.237,17 or 1,0906 (4 places under 10). */
    fun number(value: Double, locale: Locale = Locale.getDefault()): String {
        val places = if (abs(value) < 10) 4 else 2
        return NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = places; maximumFractionDigits = places }.format(value)
    }

    /** Volume short: "772,5 Mio." / "772.5M", "1,2 Mrd." / "1.2B". */
    fun compact(value: Long, locale: Locale = Locale.getDefault()): String {
        val de = locale.language == "de"
        val (div, suffix) = when {
            value >= 1_000_000_000 -> 1e9 to (if (de) " Mrd." else "B")
            value >= 1_000_000 -> 1e6 to (if (de) " Mio." else "M")
            value >= 1_000 -> 1e3 to (if (de) " Tsd." else "K")
            else -> return NumberFormat.getIntegerInstance(locale).format(value)
        }
        return NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(value / div) + suffix
    }
}
