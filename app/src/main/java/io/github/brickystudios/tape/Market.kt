package io.github.brickystudios.tape

import org.json.JSONObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
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
)

/** Chart ranges on the detail page, each with the interval Yahoo serves in a few KB. */
enum class Range(val query: String) {
    D1("range=1d&interval=5m"), D5("range=5d&interval=15m"), M1("range=1mo&interval=1d"), M6("range=6mo&interval=1d"), Y1("range=1y&interval=1wk")
}

enum class MarketStatus { PRE, OPEN, AFTER, CLOSED }

/** Market overview rows: indices, gold, oil, bitcoin, euro. Names come from string resources. */
val MARKET = listOf("^GSPC", "^IXIC", "^DJI", "^GDAXI", "GC=F", "CL=F", "BTC-USD", "EURUSD=X")

object Market {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun chartUrl(symbol: String, range: Range) = "https://query2.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?${range.query}"

    /** Today's 5-minute closes for the small line next to each row (spark answers up to 20 symbols at once). */
    fun pointsUrl(symbols: List<String>) = "https://query2.finance.yahoo.com/v7/finance/spark?symbols=${enc(symbols.joinToString(","))}&range=1d&interval=5m"

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
            m.optString("fullExchangeName").ifEmpty { m.optString("exchangeName") }, m.optString("currency"),
        )
    }

    /** Symbol → today's closes from a 5-minute spark answer. */
    fun parsePoints(json: String): Map<String, List<Double>> {
        val results = JSONObject(json).optJSONObject("spark")?.optJSONArray("result") ?: return emptyMap()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val closes = o.optJSONArray("response")?.optJSONObject(0)?.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
                ?: return@mapNotNull null
            o.optString("symbol") to (0 until closes.length()).filterNot(closes::isNull).map(closes::getDouble)
        }.filter { it.first.isNotEmpty() && it.second.size >= 2 }.toMap()
    }

    // shortcut: fixed US hours, no exchange holidays; upgrade when someone asks for a holiday calendar
    /** US session by New York time: pre-market 4:00, open 9:30, after-hours 16:00 to 20:00. */
    fun status(now: Long): MarketStatus {
        val t = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York"))
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return MarketStatus.CLOSED
        val m = t.hour * 60 + t.minute
        return when {
            m < 4 * 60 -> MarketStatus.CLOSED
            m < 9 * 60 + 30 -> MarketStatus.PRE
            m < 16 * 60 -> MarketStatus.OPEN
            m < 20 * 60 -> MarketStatus.AFTER
            else -> MarketStatus.CLOSED
        }
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
