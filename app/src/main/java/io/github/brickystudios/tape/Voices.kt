package io.github.brickystudios.tape

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** A YouTube channel ([youtube] true, [id] its channel id) or an X account ([id] its handle) whose posts Tape shows, quietly. */
data class Voice(val youtube: Boolean, val id: String, val name: String)

/**
 * Investors on YouTube and X, both off by default (Setup). Their videos and posts show under Market and, when they name one
 * of your stocks, on its page. They never ring. Tested in VoicesTest.
 */
object Voices {
    val DEFAULT = listOf(
        Voice(true, "UCNLPJZjElxcb6PSGkyeNeOg", "Maxim Investiert"),
        Voice(true, "UCeARcCUiZg79SQQ-2_XNlXQ", "Finanzfluss"),
        Voice(true, "UCpV9LpCg4uwYCQZ3qoEn_YQ", "Aktien mit Kopf"),
        Voice(true, "UCdYgvhtx9v7mWiSFxepLM2w", "Tim Schäfer"),
        Voice(true, "UCJSlXY7qCCQqpbv_-WspoFQ", "aktienlust"),
        Voice(true, "UC62IIFhchBWQxLPSyUatD-A", "DER AKTIONÄR TV"),
        Voice(true, "UCUvvj5lwue7PspotMDjk5UA", "Meet Kevin"),
        Voice(true, "UCGy7SkBjcIAgTiwkXEtPnYg", "Andrei Jikh"),
        Voice(true, "UCJwKCyEIFHwUOPQQ-4kC1Zw", "Tom Nash"),
        Voice(false, "MaximInvestiert", "Maxim Investiert"),
        Voice(false, "KobeissiLetter", "The Kobeissi Letter"),
        Voice(false, "unusual_whales", "Unusual Whales"),
        Voice(false, "LizAnnSonders", "Liz Ann Sonders"),
        Voice(false, "charliebilello", "Charlie Bilello"),
    )

    /**
     * Nitter servers that served RSS to apps on 25.11.2026, from FMHY's list and status.d420.de. X has been shutting such
     * servers down since August 2026, so Tape tries them in turn and starts with the last one that answered.
     */
    val NITTER = listOf("nitter.kareem.one", "nitter.meowing.monster", "nitter.netbub.com", "tw1tter.com", "nitter.pp.ua")

    /** YouTube every 30 minutes, X every 15: a channel posts a few videos a week, an account a few posts an hour. */
    const val YOUTUBE_EVERY = 30 * MINUTE
    const val X_EVERY = 15 * MINUTE
    const val KEEP = 120

    fun youtubeUrl(channel: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=${URLEncoder.encode(channel, "UTF-8")}"
    fun nitterUrl(host: String, handle: String) = "https://$host/${URLEncoder.encode(handle, "UTF-8")}/rss"
    fun channelPage(handle: String) = "https://www.youtube.com/@${URLEncoder.encode(handle, "UTF-8")}"

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    // shortcut: a fixed list of big names, for videos and posts about stocks you do not follow; upgrade to a lookup if names go missing
    /** Symbol → the words videos and posts use for the company. Your own stocks match first, by their own names. */
    val KNOWN = mapOf(
        "NVDA" to listOf("Nvidia"), "AAPL" to listOf("Apple"), "MSFT" to listOf("Microsoft"), "AMZN" to listOf("Amazon"), "GOOGL" to listOf("Alphabet", "Google"),
        "META" to listOf("Meta", "Facebook"), "TSLA" to listOf("Tesla"), "NFLX" to listOf("Netflix"), "AMD" to listOf("AMD"), "INTC" to listOf("Intel"),
        "PLTR" to listOf("Palantir"), "AVGO" to listOf("Broadcom"), "TSM" to listOf("TSMC"), "ASML" to listOf("ASML"), "MU" to listOf("Micron"),
        "ORCL" to listOf("Oracle"), "CRM" to listOf("Salesforce"), "ADBE" to listOf("Adobe"), "COIN" to listOf("Coinbase"), "HOOD" to listOf("Robinhood"),
        "UBER" to listOf("Uber"), "DIS" to listOf("Disney"), "NKE" to listOf("Nike"), "SBUX" to listOf("Starbucks"), "KO" to listOf("Coca-Cola"),
        "MCD" to listOf("McDonald's"), "WMT" to listOf("Walmart"), "COST" to listOf("Costco"), "JPM" to listOf("JPMorgan"), "BRK-B" to listOf("Berkshire"),
        "V" to listOf("Visa"), "MA" to listOf("Mastercard"), "PYPL" to listOf("PayPal"), "BABA" to listOf("Alibaba"), "NVO" to listOf("Novo Nordisk"),
        "LLY" to listOf("Eli Lilly"), "PFE" to listOf("Pfizer"), "BA" to listOf("Boeing"), "SMCI" to listOf("Super Micro", "Supermicro"), "DELL" to listOf("Dell"),
        "CRWD" to listOf("CrowdStrike"), "SHOP" to listOf("Shopify"), "SPOT" to listOf("Spotify"), "AXON" to listOf("Axon"), "RKLB" to listOf("Rocket Lab"),
        "SAP.DE" to listOf("SAP"), "SIE.DE" to listOf("Siemens"), "ALV.DE" to listOf("Allianz"), "RHM.DE" to listOf("Rheinmetall"), "DTE.DE" to listOf("Telekom"),
        "DBK.DE" to listOf("Deutsche Bank"), "MBG.DE" to listOf("Mercedes"), "BMW.DE" to listOf("BMW"), "VOW3.DE" to listOf("Volkswagen", "VW"), "BAS.DE" to listOf("BASF"),
        "BAYN.DE" to listOf("Bayer"), "IFX.DE" to listOf("Infineon"), "ADS.DE" to listOf("Adidas"), "MUV2.DE" to listOf("Munich Re", "Münchener Rück"),
        "HNR1.DE" to listOf("Hannover Rück"), "CBK.DE" to listOf("Commerzbank"), "AIXA.DE" to listOf("Aixtron"), "ENR.DE" to listOf("Siemens Energy"),
        "AIR.DE" to listOf("Airbus"), "RENK.DE" to listOf("Renk"), "HAG.DE" to listOf("Hensoldt"), "ZAL.DE" to listOf("Zalando"), "LHA.DE" to listOf("Lufthansa"),
        "RWE.DE" to listOf("RWE"), "VNA.DE" to listOf("Vonovia"), "DHL.DE" to listOf("DHL"),
    )

    private val CASHTAG = Regex("""(?<![A-Za-z0-9])\${'$'}([A-Z]{1,5})(?![A-Za-z0-9])""")

    /**
     * The stocks a video or post is about: your own by their names, a cashtag ("$AXON") as written, then the big names in [KNOWN].
     * A word one of your stocks already took (SAP for SAP.DE) does not tag a second symbol.
     */
    fun symbols(title: String, favorites: List<Favorite>): List<String> {
        val mine = favorites.filter { News.mentions(title, it) }
        val taken = mine.flatMap { it.words }.mapTo(HashSet()) { it.lowercase() }
        val tagged = CASHTAG.findAll(title).map { it.groupValues[1] }.toList()
        val known = KNOWN.filter { (_, words) -> words.any { it.lowercase() !in taken && News.hasWord(title, it) } }.keys
        val mineSymbols = mine.map { it.symbol }
        // a cashtag for a stock you hold under another listing ($SAP for SAP.DE) stays with yours
        return (mineSymbols + tagged.filter { t -> mineSymbols.none { it.substringBefore('.') == t } } + known).distinct()
    }

    /** The symbols named most in the last day, most first: "NVDA 4 · TSLA 2" at the top of the list. */
    fun talk(items: List<Item>, now: Long, top: Int = 6): List<Pair<String, Int>> =
        items.filter { now - it.time in 0..24 * HOUR }.flatMap { it.tickers.distinct() }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(top).map { it.key to it.value }

    /** A channel's Atom feed: its 15 newest videos. */
    fun parseYouTube(xml: String, name: String): List<Item> {
        val entries = parse(xml).getElementsByTagName("entry")
        return (0 until entries.length).mapNotNull { i ->
            val e = entries.item(i) as Element
            fun text(tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val id = text("yt:videoId").ifEmpty { return@mapNotNull null }
            // "🚨 CRASH BEI GOLD" → "CRASH BEI GOLD": emoji go, as in Reddit titles
            val title = text("title").replace(Reddit.EMOJI, "").replace(Regex("\\s+"), " ").trim().ifEmpty { return@mapNotNull null }
            val time = runCatching { OffsetDateTime.parse(text("published")).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            Item("yt:$id", title, "https://www.youtube.com/watch?v=$id", name, time)
        }
    }

    /** An account's posts from a Nitter server; reposts and replies stay out, links open on x.com instead of the server. */
    fun parseNitter(xml: String, name: String, handle: String): List<Item> {
        val items = parse(xml).getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val e = items.item(i) as Element
            fun text(tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            // links and emoji go, the words stay: "long. 😂 https://finance.yahoo.com/…" → "long."
            val title = text("title").replace(Regex("""https?://\S+"""), "").replace(Reddit.EMOJI, "").replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty() || title.startsWith("RT by @") || title.startsWith("R to @") || News.PROMO.containsMatchIn(title)) return@mapNotNull null
            val id = Regex("/status/(\\d+)").find(text("link"))?.groupValues?.get(1) ?: return@mapNotNull null
            val time = runCatching { ZonedDateTime.parse(text("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            Item("x:$id", title, "https://x.com/$handle/status/$id", name, time)
        }
    }

    /** A pasted channel link or handle: "youtube.com/channel/UC…" gives the id at once, "@name" or "youtube.com/@name" needs the page. */
    fun channelId(input: String): String? = Regex("(UC[\\w-]{22})").find(input)?.value

    fun youtubeHandle(input: String): String? = Regex("""(?:youtube\.com/)?@([\w.-]{3,30})""").find(input.trim())?.groupValues?.get(1)

    /** "@MaximInvestiert", "x.com/MaximInvestiert" or "MaximInvestiert": X handles are 1 to 15 letters, digits and underscores. */
    fun xHandle(input: String): String? = Regex("""^(?:https?://)?(?:www\.)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})|^@?([A-Za-z0-9_]{1,15})$""").find(input.trim())
        ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

    /** The channel id and name a channel page names as its own. */
    fun parseChannelPage(html: String): Pair<String, String>? {
        val id = Regex("""<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[\w-]{22})"""").find(html)?.groupValues?.get(1) ?: return null
        val name = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&").orEmpty()
        return id to name.ifEmpty { id }
    }
}
