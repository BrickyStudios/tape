package io.github.brickystudios.tape

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** One stock's price today against yesterday's close, from Yahoo's spark answer. */
data class Quote(val symbol: String, val price: Double, val previousClose: Double, val time: Long, val zone: String, val currency: String) {
    /** percent, +31.5 for a 31.5 % rise */
    val change: Double get() = (price - previousClose) / previousClose * 100

    /** The trading day this price belongs to, in the exchange's own time zone, so a weekend keeps Friday's day. */
    val day: String get() = Instant.ofEpochMilli(time).atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC"))).toLocalDate().toString()
}

/** What a headline about a stock reports. Anything else (opinion, lists, recaps) waits quietly in the list. */
enum class Kind { EARNINGS, ANALYST, DEAL, MANAGEMENT, LEGAL, HALT, CAPITAL, MOVE, RELEASE }

/** Which news and prices are worth a sound. Pure, tested in AlertsTest. */
object Alerts {
    /** Headlines with these words ring even when they name none of your stocks. Editable under Setup. */
    val DEFAULT_WORDS = listOf("FOMC", "Rate Decision", "CPI", "PCE", "Nonfarm Payrolls", "GDP", "ECB", "Tariff", "Recession", "Circuit Breaker")
    const val MAX_WORDS = 30
    private const val MAX_WORD_LENGTH = 40
    val THRESHOLDS = listOf(3, 5, 10)
    const val DEFAULT_THRESHOLD = 5

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // shortcut: English keyword lists; upgrade when German or other-language sources come in
    private val KINDS = listOf(
        Kind.EARNINGS to rx("""\b(earnings|results|guidance|beats|misses|Q[1-4]|quarterly|revenue|EPS|profit warning)\b"""),
        Kind.ANALYST to rx("""\b(upgrades?|upgraded|downgrades?|downgraded)\b"""),
        Kind.DEAL to rx("""\b(acquires?|acquired|acquisition|merger|buyout|takeover|bid for)\b"""),
        Kind.MANAGEMENT to rx("""\b(CEO|CFO|chief executive)\b.*\b(resigns?|resignation|steps? down|appoints?|appointed|named|ousted|fired|departs?|departure|returns?|succeeds?|successor)\b|\b(resigns?|steps? down|appoints?|ousted|fired|returns?|back as)\b.*\b(CEO|CFO|chief executive)\b"""),
        Kind.LEGAL to rx("""\b(lawsuit|sues|sued|SEC|DOJ|FTC|FDA|probe|investigation|antitrust|subpoena|fined|recalls?|class action|indicted)\b"""),
        Kind.HALT to rx("""\b(halted|halts trading|trading halt|bankruptcy|bankrupt|chapter 11|delisted|delisting)\b"""),
        Kind.CAPITAL to rx("""\b(buybacks?|repurchase|stock split|share offering|secondary offering|dividend (cut|hike|increase|raise|suspension))\b"""),
        Kind.MOVE to rx("""\b(soars?|soared|plunges?|plunged|plummets?|plummeted|surges?|surged|tumbles?|tumbled|crash(es|ed)?|skyrockets?|skyrocketed|sinks|sank)\b"""),
    )

    /** Company press releases come over these wires; they always count. */
    private val RELEASE_WIRES = setOf("business wire", "pr newswire", "prnewswire", "globenewswire", "accesswire", "newsfile")

    /** "3 Growth Stocks To Watch", "5 Best ...": lists that mention a stock in passing. */
    private val LISTICLE = rx("""^\d+\s.*\b(stocks|reasons|things|ways|picks)\b""")

    /**
     * The event a headline reports, or null. Questions ("Can Revenues Accelerate?") and lists are opinion, even with an event word.
     * Measured on 80 Yahoo results for 8 stocks: most noise was listicles, questions and market wraps.
     */
    fun kind(title: String, source: String): Kind? {
        if (source.trim().lowercase() in RELEASE_WIRES) return Kind.RELEASE
        val t = title.trim()
        if (t.endsWith("?") || LISTICLE.containsMatchIn(t)) return null
        return KINDS.firstOrNull { it.second.containsMatchIn(t) }?.first
    }

    /**
     * Sorts new, tagged headlines into what rings. One push per headline: an event about a stock beats an alert word,
     * so a wire item "Fed rate decision hits Apple" about a watched stock rings once, as a stock event or, failing that, as market news.
     */
    fun decide(tagged: List<Item>, words: List<String>): Pair<List<Pair<Item, Kind>>, List<Pair<Item, String>>> {
        val unique = tagged.distinctBy { it.id }
        val events = unique.filter { it.tickers.isNotEmpty() }.mapNotNull { i -> kind(i.title, i.source)?.let { i to it } }
        val ringing = events.mapTo(HashSet()) { it.first.id }
        val market = unique.filter { it.wire && it.id !in ringing }.mapNotNull { i -> alertWord(i.title, words)?.let { i to it } }
        return events to market
    }

    /** The first alert word in a headline, as whole words in any case. */
    fun alertWord(title: String, words: List<String>): String? = words.firstOrNull { w ->
        w.isNotBlank() && Regex("(?<![A-Za-z0-9])${Regex.escape(w.trim())}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(title)
    }

    /** What the add field may store: trimmed, single spaces, 2 to 40 characters. */
    fun cleanWord(input: String): String? = input.trim().replace(Regex("\\s+"), " ").takeIf { it.length in 2..MAX_WORD_LENGTH }

    /** 0 below the threshold, 1 from one threshold, 2 from two (5 % → 1 at ±5 %, 2 at ±10 %). */
    fun step(change: Double, threshold: Int): Int = if (threshold <= 0 || change.isNaN() || change.isInfinite()) 0 else (abs(change) / threshold).toInt()

    /** Remembers the highest step that rang, per stock, trading day, direction and threshold. */
    fun stepKey(q: Quote, threshold: Int) = "${q.symbol}|${q.day}|${if (q.change >= 0) "up" else "down"}|$threshold"

    /** Stocks that crossed a step not rung yet today, with that step. A jump from 0 to 12 % rings once, for step 2. */
    fun priceAlerts(quotes: List<Quote>, threshold: Int, rung: Map<String, Int>): List<Pair<Quote, Int>> = quotes.mapNotNull { q ->
        val s = step(q.change, threshold)
        if (s >= 1 && s > (rung[stepKey(q, threshold)] ?: 0)) q to s else null
    }

    /** The rung steps worth keeping: today's for the current quotes, plus the new ones. Older days fall away. */
    fun keepRung(rung: Map<String, Int>, quotes: List<Quote>, threshold: Int, fired: List<Pair<Quote, Int>>): Map<String, Int> =
        rung.filterKeys { k -> quotes.any { k.startsWith(it.symbol + "|" + it.day + "|") } } + fired.associate { (q, s) -> stepKey(q, threshold) to s }

    // shortcut: fixed 22:00 to 07:00 phone time; upgrade to chosen hours when someone asks
    /** Night: notifications still arrive, without sound or reading aloud. */
    fun quiet(hour: Int) = hour >= 22 || hour < 7

    /** Headlines newer than the last look at that tab. */
    fun newCount(items: List<Item>, seen: Long) = if (seen <= 0) 0 else items.count { it.time > seen }
}
