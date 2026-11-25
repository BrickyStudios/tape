package io.github.brickystudios.tape

import org.json.JSONObject
import java.net.URLEncoder

/** A year of daily closes boiled down to the levels long-term buyers watch. [avg200]: null with fewer than 200 trading days. */
data class Levels(val avg200: Double?, val low: Double, val high: Double, val rsi: Double?)

/** The rules of thumb behind "buying chance", in the order a row names them. */
enum class Sign { LOW, AVG200, OVERSOLD, DRAWDOWN }

/** Everything about buying chances that needs no phone. Tested in ChanceTest. */
object Chance {
    /**
     * From 5 % under to 3 % over the 200-day line counts as "at" it: a pullback to the line. Further under is a falling trend
     * (Nvidia at −28 % on 25.11.2026), which the distance from the high already counts.
     */
    const val AVG_OVER = 1.03
    const val AVG_UNDER = 0.95
    /** At most this far above the 52-week low counts as "near" it. */
    const val NEAR_LOW = 1.05
    /** At least this far below the 52-week high counts. */
    const val DRAWDOWN = 0.80
    const val OVERSOLD = 30.0
    /** Daily closes change once a day; a new stock is asked within 5 minutes. */
    const val INTERVAL = 6 * HOUR

    /** One year of daily closes for up to 20 symbols, about 6 KB each, crumb-free like the day prices. */
    fun url(symbols: List<String>) = "https://query2.finance.yahoo.com/v7/finance/spark?symbols=${URLEncoder.encode(symbols.joinToString(","), "UTF-8")}&range=1y&interval=1d"

    fun parse(json: String): Map<String, Levels> {
        val results = JSONObject(json).optJSONObject("spark")?.optJSONArray("result") ?: return emptyMap()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val r = o.optJSONArray("response")?.optJSONObject(0) ?: return@mapNotNull null
            val cs = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close") ?: return@mapNotNull null
            val closes = (0 until cs.length()).filterNot(cs::isNull).map(cs::getDouble).filter { it > 0 }
            if (closes.size < 20) return@mapNotNull null
            val m = r.optJSONObject("meta")
            // Yahoo's 52-week range counts intraday lows and highs, like the detail page shows it; the closes are the fallback
            val low = m?.optDouble("fiftyTwoWeekLow")?.takeIf { it > 0 } ?: closes.min()
            val high = m?.optDouble("fiftyTwoWeekHigh")?.takeIf { it > 0 } ?: closes.max()
            o.optString("symbol") to Levels(average(closes, 200), low, high, rsi(closes))
        }.filter { it.first.isNotEmpty() }.toMap()
    }

    fun average(closes: List<Double>, days: Int): Double? = if (closes.size < days) null else closes.takeLast(days).average()

    /** Wilder's RSI over [days]: 0 to 100, under 30 reads as oversold. Null without enough closes. */
    fun rsi(closes: List<Double>, days: Int = 14): Double? {
        if (closes.size <= days) return null
        val moves = closes.zipWithNext { a, b -> b - a }
        var gain = moves.take(days).sumOf { maxOf(it, 0.0) } / days
        var loss = moves.take(days).sumOf { maxOf(-it, 0.0) } / days
        for (m in moves.drop(days)) {
            gain = (gain * (days - 1) + maxOf(m, 0.0)) / days
            loss = (loss * (days - 1) + maxOf(-m, 0.0)) / days
        }
        return if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
    }

    /** The signs this price shows against its levels, in [Sign] order. */
    fun signs(l: Levels, price: Double): List<Sign> = buildList {
        if (price <= l.low * NEAR_LOW) add(Sign.LOW)
        if (l.avg200 != null && price in l.avg200 * AVG_UNDER..l.avg200 * AVG_OVER) add(Sign.AVG200)
        if (l.rsi != null && l.rsi < OVERSOLD) add(Sign.OVERSOLD)
        if (price <= l.high * DRAWDOWN) add(Sign.DRAWDOWN)
    }

    /** Percent from [base] to [price]: −4.2 when the price is 4.2 % under it. */
    fun distance(price: Double, base: Double) = (price - base) / base * 100
}
