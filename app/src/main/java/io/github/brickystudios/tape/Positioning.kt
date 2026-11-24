package io.github.brickystudios.tape

import org.json.JSONArray
import java.net.URLEncoder
import kotlin.math.abs

/** One week of the CFTC report for the S&P 500 E-mini future: net contracts of asset managers and of hedge funds ("leveraged funds"). */
data class Cot(val date: String, val managers: Long, val funds: Long)

/** A group's net bet read against its last 3 years: [change] over 4 weeks as a fraction, [rank] of its size from 0 (smallest) to 100. */
data class Bet(val net: Long, val change: Double?, val rank: Int)

/**
 * How the professionals are positioned, the free counterpart to the prime-broker charts banks keep to their clients.
 * The US regulator CFTC publishes it every Friday for the Tuesday before ("Traders in Financial Futures"). Tested in PositioningTest.
 */
object Positioning {
    const val INTERVAL = 6 * HOUR
    private const val FIELDS = "report_date_as_yyyy_mm_dd,lev_money_positions_long,lev_money_positions_short,asset_mgr_positions_long,asset_mgr_positions_short"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** 160 weeks, newest first, about 50 KB once every 6 hours. */
    val URL = "https://publicreporting.cftc.gov/resource/gpe5-46if.json?\$select=" + enc(FIELDS) +
        "&\$where=" + enc("market_and_exchange_names like 'E-MINI S&P 500 -%'") + "&\$order=" + enc("report_date_as_yyyy_mm_dd DESC") + "&\$limit=160"

    fun parse(json: String): List<Cot> {
        val a = JSONArray(json)
        return (0 until a.length()).map(a::getJSONObject).mapNotNull { o ->
            fun n(k: String) = o.optString(k).toLongOrNull()
            val date = o.optString("report_date_as_yyyy_mm_dd").take(10)
            val ml = n("asset_mgr_positions_long") ?: return@mapNotNull null
            val ms = n("asset_mgr_positions_short") ?: return@mapNotNull null
            val fl = n("lev_money_positions_long") ?: return@mapNotNull null
            val fs = n("lev_money_positions_short") ?: return@mapNotNull null
            if (date.length < 10) null else Cot(date, ml - ms, fl - fs)
        }.sortedByDescending { it.date }
    }

    /** The newest week for one group, with its 4-week change and how big the bet is next to the last 3 years. */
    fun read(history: List<Cot>, pick: (Cot) -> Long): Bet? {
        val now = history.firstOrNull() ?: return null
        val net = pick(now)
        val before = history.getOrNull(4)?.let(pick)
        val change = if (before != null && before != 0L && (before > 0) == (net > 0)) (abs(net) - abs(before)).toDouble() / abs(before) else null
        val rank = history.count { abs(pick(it)) <= abs(net) } * 100 / history.size
        return Bet(net, change, rank)
    }
}
