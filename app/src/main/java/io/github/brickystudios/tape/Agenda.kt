package io.github.brickystudios.tape

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** One market-moving release: jobs, inflation, a central bank. [place] is the currency ("USD", "EUR") or "DE" / "FR" for German and French data. */
data class Indicator(val title: String, val place: String, val time: Long, val forecast: String, val previous: String)

/** A company's next quarterly report. [slot]: "BMO" before the open, "AMC" after the close, anything else not announced. */
data class Earning(val symbol: String, val name: String, val time: Long, val slot: String, val quarter: String, val eps: Double?, val rank: Long)

/**
 * The week ahead, what r/wallstreetbets ("Most Anticipated Earnings") and r/mauerstrassenwetten ("Erntenkalender") post every week:
 * economic releases from ForexFactory's public calendar export, earnings from Yahoo's calendar. Tested in AgendaTest.
 */
object Agenda {
    const val RELEASES_URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"
    const val INTERVAL = 3 * HOUR
    private const val EARNINGS_URL = "https://query2.finance.yahoo.com/ws/screeners/v1/finance/calendar-events?modules=earnings"

    /** The economies whose data moves the DAX and Wall Street; Australia, New Zealand and Canada stay out. */
    private val MAJOR = setOf("USD", "EUR", "GBP", "JPY", "CNY")

    /** High impact from the big economies, plus medium impact from the eurozone (Ifo, German and French PMIs), which moves the DAX. */
    fun parseReleases(json: String): List<Indicator> {
        val a = JSONArray(json)
        return (0 until a.length()).map(a::getJSONObject).filter { o ->
            val c = o.optString("country")
            val i = o.optString("impact")
            c in MAJOR && (i == "High" || (i == "Medium" && c == "EUR"))
        }.mapNotNull { o ->
            val time = runCatching { OffsetDateTime.parse(o.getString("date")).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val title = o.optString("title").trim()
            val place = when {
                title.startsWith("German ") -> "DE"
                title.startsWith("French ") -> "FR"
                else -> o.optString("country")
            }
            Indicator(title.removePrefix("German ").removePrefix("French "), place, time, o.optString("forecast"), o.optString("previous"))
        }.sortedBy { it.time }
    }

    /** The most watched reports per day; Yahoo sorts each day by its popularity rank. */
    fun weekUrl(from: Long, to: Long, perDay: Int = 6) = "$EARNINGS_URL&countPerDay=$perDay&startDate=$from&endDate=$to"

    /** The next reports of your stocks. Yahoo lists a date once the company has announced it. */
    fun stocksUrl(symbols: List<String>, from: Long, to: Long) =
        "$EARNINGS_URL&countPerDay=100&startDate=$from&endDate=$to&tickersFilter=${URLEncoder.encode(symbols.joinToString(","), "UTF-8")}"

    fun parseEarnings(json: String): List<Earning> {
        val days = JSONObject(json).optJSONObject("finance")?.optJSONObject("result")?.optJSONArray("earnings") ?: return emptyList()
        return (0 until days.length()).flatMap { i ->
            val r = days.getJSONObject(i).optJSONArray("records") ?: return@flatMap emptyList()
            (0 until r.length()).map(r::getJSONObject).mapNotNull { o ->
                val symbol = o.optString("ticker")
                val time = o.optLong("startDateTime")
                if (symbol.isEmpty() || time <= 0) return@mapNotNull null
                val eps = o.optDouble("epsEstimate").takeUnless { it.isNaN() }
                Earning(symbol, o.optString("companyShortName").replace(Regex("\\s+"), " ").trim(), time, o.optString("startDateTimeType"), o.optString("quarter"), eps, o.optLong("rank"))
            }
        }.sortedBy { it.time }
    }

    /** Midnight in [zone], [days] days after the day of [now]. */
    fun dayStart(now: Long, zone: ZoneId, days: Long = 0): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()

    /** A stock's next report from today on, or null. */
    fun next(earnings: List<Earning>, symbol: String, now: Long, zone: ZoneId): Earning? =
        earnings.firstOrNull { it.symbol == symbol && it.time >= dayStart(now, zone) }

    private class Name(val rx: Regex, val res: Int, val places: Set<String>)

    private fun n(pattern: String, res: Int, vararg places: String) = Name(Regex(pattern, RegexOption.IGNORE_CASE), res, places.toSet())

    /** Plain names for the common releases, first match wins; the calendar's titles are trader shorthand ("Prelim GDP q/q"). */
    private val NAMES = listOf(
        n("Press Conference", R.string.ev_fed_press, "USD"),
        n("Press Conference", R.string.ev_ecb_press, "EUR"),
        n("Federal Funds Rate|FOMC Statement", R.string.ev_fed),
        n("Main Refinancing Rate|Monetary Policy Statement", R.string.ev_ecb, "EUR"),
        n("Official Bank Rate|Monetary Policy Summary", R.string.ev_boe, "GBP"),
        n("BOJ Policy Rate", R.string.ev_boj),
        n("FOMC Meeting Minutes", R.string.ev_fed_minutes),
        n("Core PCE", R.string.ev_core_pce),
        n("PCE", R.string.ev_pce),
        n("Core CPI", R.string.ev_core_cpi),
        n("CPI", R.string.ev_cpi),
        n("Non-Farm Employment", R.string.ev_nfp),
        n("Average Hourly Earnings", R.string.ev_wages),
        n("Unemployment Claims", R.string.ev_claims),
        n("Unemployment Rate|Claimant Count", R.string.ev_unemployment),
        n("GDP", R.string.ev_gdp),
        n("PMI", R.string.ev_pmi),
        n("Retail Sales", R.string.ev_retail),
        n("Ifo", R.string.ev_ifo),
        n("ZEW", R.string.ev_zew),
        n("PPI", R.string.ev_ppi),
        n("JOLTS", R.string.ev_jolts),
        n("Consumer Confidence|Consumer Sentiment", R.string.ev_sentiment),
    )

    /** A plain name as a string resource, or null: then the calendar's own title shows. */
    fun nameRes(r: Indicator): Int? = NAMES.firstOrNull { it.rx.containsMatchIn(r.title) && (it.places.isEmpty() || r.place in it.places) }?.res

    /** "Powell" from "Fed Chair Powell Speaks". */
    fun speaker(r: Indicator): String? = Regex("""(\S+) Speaks""").find(r.title)?.groupValues?.get(1)

    /** Releases at the same minute and place, like the CPI's four lines, as one row. */
    fun rows(releases: List<Indicator>): List<List<Indicator>> = releases.groupBy { it.time to it.place }.values.toList()
}
