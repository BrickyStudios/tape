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
    /**
     * Headlines with these words ring even when they name none of your stocks. Editable under Setup.
     * No "ECB": 10 of 100 FinancialJuice lines in 18 hours were ECB speakers; the decision itself rings through "Rate Decision".
     */
    val DEFAULT_WORDS = listOf("FOMC", "Rate Decision", "CPI", "PCE", "Nonfarm Payrolls", "GDP", "Tariff", "Recession", "Circuit Breaker")
    const val MAX_WORDS = 30
    private const val MAX_WORD_LENGTH = 40
    val THRESHOLDS = listOf(3, 5, 10)
    const val DEFAULT_THRESHOLD = 5

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    /** German words: letter boundaries of their own, since `\b` treats ä, ö, ü and ß differently on Android and in the JVM tests. */
    private fun de(p: String) = Regex("""(?<![\p{L}\d])(?:$p)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)

    // shortcut: English and German keyword lists; upgrade when other-language sources come in
    private val KINDS_EN = listOf(
        Kind.EARNINGS to rx("""\b(earnings|results|guidance|beats|misses|Q[1-4]|quarterly|revenue|EPS|profit warning)\b"""),
        Kind.ANALYST to rx("""\b(upgrades?|upgraded|downgrades?|downgraded)\b"""),
        Kind.DEAL to rx("""\b(acquires?|acquired|acquisition|merger|buyout|takeover|bid for)\b"""),
        Kind.MANAGEMENT to rx("""\b(CEO|CFO|chief executive)\b.*\b(resigns?|resignation|steps? down|appoints?|appointed|named|ousted|fired|departs?|departure|returns?|reinstated|reinstates|succeeds?|successor)\b|\b(resigns?|steps? down|appoints?|ousted|fired|returns?|back as|reinstated|reinstates)\b.*\b(CEO|CFO|chief executive)\b"""),
        Kind.LEGAL to rx("""\b(lawsuit|sues|sued|SEC|DOJ|FTC|FDA|probe|investigation|antitrust|subpoena|fined|recalls?|class action|indicted)\b"""),
        Kind.HALT to rx("""\b(halted|halts trading|trading halt|bankruptcy|bankrupt|chapter 11|delisted|delisting)\b"""),
        Kind.CAPITAL to rx("""\b(buybacks?|repurchase|stock split|share offering|secondary offering|dividend (cut|hike|increase|raise|suspension))\b"""),
        Kind.MOVE to rx("""\b(soars?|soared|plunges?|plunged|plummets?|plummeted|surges?|surged|tumbles?|tumbled|crash(es|ed)?|skyrockets?|skyrocketed|sinks|sank)\b"""),
    )

    /** The same events in German headlines (onvista, Handelsblatt, FAZ, see [News.GERMAN_FEEDS]). */
    private val KINDS_DE = mapOf(
        Kind.EARNINGS to de("""Quartalszahlen|Quartalsbericht\p{L}*|Zahlen|Bilanz|Umsatz|Jahresprognose|Prognose|Gewinnziele?|Gewinnwarnung|Gewinnerwartung\p{L}*|Gewinn|Quartal"""),
        Kind.ANALYST to de("""hochgestuft|herabgestuft|abgestuft|stuft \S+ (hoch|herab|ab)|Kursziel\p{L}*|Kaufempfehlung|Verkaufsempfehlung"""),
        Kind.DEAL to de("""Übernahme\p{L}*|übernimmt|Fusion|Zusammenschluss|Einstieg bei|steigt bei \S+ ein"""),
        Kind.MANAGEMENT to de("""(\p{L}*chef|CEO|Vorstand\p{L}*)[^.]*(tritt zurück|Rücktritt|hört [^.]*auf|Nachfolger\p{L}*|abgelöst|kehrt zurück|zurück als)|(zurück als|neuer|neue) (\p{L}*chef|CEO)"""),
        Kind.LEGAL to de("""Klage|Sammelklage|verklagt|Ermittlung\p{L}*|Razzia|Strafe|Bußgeld|Kartellamt|BaFin|Rückruf\p{L}*|Anklage"""),
        Kind.HALT to de("""Insolvenz\p{L}*|insolvent|Handel ausgesetzt|Handelsaussetzung|Delisting"""),
        Kind.CAPITAL to de("""Kapitalerhöhung|Aktienrückkauf\p{L}*|Rückkaufprogramm|Dividende (erhöht|gekürzt|gestrichen|gesenkt)|Dividendenerhöhung|Dividendenkürzung|Aktiensplit"""),
        Kind.MOVE to de("""springt|bricht [^.]{0,20}ein|sackt [^.]{0,20}ab|stürzt|Kurssturz|Kurssprung|im freien Fall|knickt [^.]{0,20}ein|haussiert"""),
    )

    /** "Nach Einstieg bei LEG: Aroundtown-Chef hört auf": what led up to it, before the colon, is not the event. */
    private val LEAD_DE = de("""nach [^:]{0,40}:""")

    /** English first, then German, kind by kind, so the order of [Kind] still decides between two matches. */
    private val KINDS = KINDS_EN.map { (k, en) -> k to listOfNotNull(en, KINDS_DE[k]) }

    /** Company press releases come over these wires; they always count. */
    private val RELEASE_WIRES = setOf("business wire", "pr newswire", "prnewswire", "globenewswire", "accesswire", "newsfile")

    /** "3 Growth Stocks To Watch", "5 Best ...": lists that mention a stock in passing. */
    private val LISTICLE = rx("""^\d+\s.*\b(stocks|reasons|things|ways|picks)\b""")

    /** "Apple vs. Broadcom: Comparing Revenue Trends" is opinion; "Epic vs. Apple: judge rules" is not, so it needs a comparing word. */
    private val VERSUS = rx("""\bvs\.?\s.*\b(which|better|comparing|compared?|buy)\b""")

    /**
     * The event a headline reports, or null. Questions ("Can Revenues Accelerate?"), lists and comparisons are opinion, even with an event word.
     * Measured on 80 Yahoo results for 8 stocks: most noise was listicles, questions and market wraps.
     */
    fun kind(title: String, source: String): Kind? {
        if (source.trim().lowercase() in RELEASE_WIRES) return Kind.RELEASE
        val t = LEAD_DE.replace(title.trim(), " ")
        if (t.endsWith("?") || LISTICLE.containsMatchIn(t) || VERSUS.containsMatchIn(t)) return null
        return KINDS.firstOrNull { (_, r) -> r.any { it.containsMatchIn(t) } }?.first
    }

    /**
     * Sorts new, tagged headlines into what rings. One push per headline: an event about a stock beats an alert word,
     * so a wire item "Fed rate decision hits Apple" about a watched stock rings once, as a stock event or, failing that, as market news.
     */
    fun decide(tagged: List<Item>, words: List<String>): Pair<List<Pair<Item, Kind>>, List<Pair<Item, String>>> {
        val unique = tagged.distinctBy { it.id }
        // a big move rings as a price alert already; its headline only gets the label
        val events = unique.filter { it.tickers.isNotEmpty() }.mapNotNull { i -> kind(i.title, i.source)?.takeIf { it != Kind.MOVE }?.let { i to it } }
        val ringing = events.mapTo(HashSet()) { it.first.id }
        val market = unique.filter { it.wire && it.id !in ringing }.mapNotNull { i -> alertWord(i.title, words)?.let { i to it } }
        return events to market
    }

    const val EVENT_PAUSE = 12 * HOUR
    const val WORD_PAUSE = HOUR

    /**
     * After [decide]: one push per stock and event kind in 12 hours, one per alert word in an hour. The same story from Reuters,
     * Seeking Alpha and Bloomberg rings once, the rest waits in the list. Alert words free at the start of a check ring together,
     * so the 3 or 4 CPI lines still come as one bundle. Returns what rings and the memory to keep.
     */
    fun pause(
        events: List<Pair<Item, Kind>>, market: List<Pair<Item, String>>, rang: Map<String, Long>, now: Long,
    ): Triple<List<Pair<Item, Kind>>, List<Pair<Item, String>>, Map<String, Long>> {
        val memory = rang.filterValues { now - it < EVENT_PAUSE }.toMutableMap()
        fun recent(key: String, wait: Long) = memory[key]?.let { now - it < wait } == true
        val ringing = events.filter { (i, k) ->
            val keys = i.tickers.map { "e:$it|$k" }
            keys.any { !recent(it, EVENT_PAUSE) }.also { ring -> if (ring) keys.forEach { memory[it] = now } }
        }
        val free = market.map { it.second.lowercase() }.distinct().filterNot { recent("w:$it", WORD_PAUSE) }.toSet()
        free.forEach { memory["w:$it"] = now }
        return Triple(ringing, market.filter { it.second.lowercase() in free }, memory)
    }

    private const val PCT = """\d+(?:[.,]\d+)?\s?%"""
    /**
     * Turns whose single words read the wrong way, scored first and then blanked out: "beaten-down" holds "beat",
     * "losses narrow" holds "losses", "says the selloff went too far" is an analyst who sees value.
     */
    private val PHRASES = listOf(
        de("""senkt [^.]{0,20}(Prognose|Ziel|Dividende|Kursziel)|unter Druck|im freien Fall|im Minus""") to -1,
        de("""(hebt|erhöht) [^.]{0,20}(Prognose|Ziel|Dividende|Kursziel)|besser als erwartet|im Plus""") to 1,
        rx("""\b(beat(en)?[- ]down|rally (is getting |was |gets )?wiped out|rally (fades|faded|fizzles|stalls|stalled)|(outlook|guidance|forecast|target|dividend) (cut|lowered|slashed))\b""") to -1,
        rx("""\b(loss(es)? narrow\w*|wins dismissal( of .{0,40}?(lawsuit|suit|case))?|(views?|guidance|outlook|forecast|target|dividend) (raised|lifted|hiked)|(selloff|sell-off|drop|decline|rout) (went|has gone|is|was) too far|overdone)\b""") to 1,
    )
    private val GOOD = rx(
        """\b(beats?|beating|tops (estimates|expectations)|raise[sd]? (\w+ ){0,2}(guidance|forecast|outlook|dividend|target|view)|hikes? (\w+ ){0,2}dividend|upgrades?|upgraded|""" +
            """record (revenue|profit|sales|high|backlog|quarter)|approv(al|es|ed)|wins|won|soars?|soared|soaring|surges?|surged|jumps?|jumped|rall(y|ies|ied)|skyrockets?|""" +
            """buybacks?|buys? back|rises?|rose|climbs?|climbed|gains?|gained|grows?|grew|rebounds?|rebounded|lifts?|lifted|higher|outperform|overweight|bullish|""" +
            """strong (sales|earnings|demand|results|quarter)|lands? .{0,40}?(order|deal|contract)|up( $PCT)? today|up $PCT)(?![A-Za-z])""",
    )
    private val BAD = rx(
        """\b(miss(es|ed)?|cuts? (\w+ ){0,2}(guidance|forecast|outlook|dividend|jobs|target|rating|view)|lowers? (\w+ ){0,2}(guidance|forecast|outlook|target|view)|""" +
            """downgrades?|downgraded|plunges?|plunged|tumbles?|tumbled|falls?|fell|falling|sinks?|sank|sinking|drops?|dropped|slumps?|slumped|slides?|slid|slips?|slipped|""" +
            """dives?|dived|skids?|craters?|cratered|declines|declined|lower|selloff|sell-off|rout|lawsuit|sues|sued|probe|investigation|recalls?|halted|bankrupt\w*|""" +
            """resigns?|steps? down|warns|warning|layoffs?|fraud|crash(es|ed)?|plummets?|plummeted|setbacks?|snag|delays?|delayed|stalls?|stalled|outages?|loss(es)?|loser|""" +
            """walloped|blow( out)?|blacklist\w*|underperform|underweight|bearish|weak(er|ens|ening)?|fears?|concerns?|doubts?|at risk|short seller|down( $PCT)? today|down $PCT)(?![A-Za-z])""",
    )
    private val GOOD_DE = de(
        """steigt|steigen|stieg|stiegen|legt [^.]{0,20}zu|legen [^.]{0,20}zu|zieht an|ziehen an|springt|erholt|erholen|Rekord\p{L}*|übertrifft|übertreffen|""" +
            """hochgestuft|Kaufempfehlung|Kurssprung|trumpf\p{L}* auf|glänzt|Gewinne|Comeback|haussiert""",
    )
    private val BAD_DE = de(
        """fällt|fallen|fiel|fielen|sinkt|sinken|sank|bricht [^.]{0,20}ein|brechen [^.]{0,20}ein|rutscht|rutschen|sackt [^.]{0,20}ab|verliert|verlieren|verlor|""" +
            """Gewinnwarnung|verfehlt|verfehlen|enttäuscht|enttäuschen|herabgestuft|abgestuft|\p{L}*verlust\p{L}*|Verlust\p{L}*|Klage|belasten|belastet|\p{L}*tief|""" +
            """Kursrutsch|Kurssturz|Ausverkauf|Abverkauf|knickt [^.]{0,20}ein|schwächer|Insolvenz\p{L}*|drohen|droht""",
    )
    /**
     * A question is a guess, not news: "What could lift Chipotle further?", "Jetzt Nvidia kaufen?". A why-question explains a move
     * that happened ("Why is Nike up today?"), so it stays. Only the question clause goes; a fact before its colon stays.
     */
    private val SPECULATION = rx("""(^|: |– |- |\. )(?!(why|warum|wieso|weshalb)\b)[^?:–.]*\?""")
    /** What follows "despite" is background; in German "trotz …" and "nach …" carry the cause in two or three words. */
    private val BACKGROUND = rx("""\bdespite\b.*""")
    private val BACKGROUND_DE = de("""(trotz|nach)( (der|des|dem|den|die|eines|einer|einem))?( \S+){1,2}""")

    /**
     * Rough reading of a stock headline: 1 rather good, -1 rather bad, 0 unclear. Shown as "▲ rather positive" next to the event,
     * never as advice. Mixed headlines ("beats estimates but cuts outlook") come out unclear; what follows "despite" is background
     * ("falls 7% despite record backlog"). Checked against 68 hand-labelled headlines from 25.11.2026 in ToneTest: 68 right, the
     * keyword list before 0.7.0 had 35.
     */
    fun tone(title: String): Int {
        // the question is dropped, a fact before it stays: "Super Micro fell 32%: bargain or toxic?" is still a fall
        var t = SPECULATION.replace(BACKGROUND_DE.replace(BACKGROUND.replace(title.trim(), ""), " "), " ")
        var score = 0
        for ((r, v) in PHRASES) { score += v * r.findAll(t).count(); t = r.replace(t, " ") }
        score += GOOD.findAll(t).count() + GOOD_DE.findAll(t).count() - BAD.findAll(t).count() - BAD_DE.findAll(t).count()
        return Integer.signum(score)
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
