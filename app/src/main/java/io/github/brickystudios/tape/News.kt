package io.github.brickystudios.tape

import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE

/**
 * One headline. [wire]: from a market feed (Wire tab), otherwise from the news search for one stock.
 * [tickers]: the watchlist stocks the headline names, set on the fly by [News.tag].
 */
data class Item(
    val id: String,
    val title: String,
    val link: String,
    val source: String,
    val time: Long,
    val tickers: List<String> = emptyList(),
    val wire: Boolean = false,
)

/**
 * A stock on the watchlist. [exchange]: where it trades, as Yahoo names it ("XETRA", "NasdaqGS"), to tell SAP.DE from SAP.
 * [own]: "I own this", listed under Holding; otherwise only watched.
 */
data class Favorite(val symbol: String, val name: String, val exchange: String = "", val own: Boolean = false) {
    /** The word headlines use for the company: "Amazon.com, Inc." → "Amazon", "Procter & Gamble Company (The)" → "Procter". */
    val keyword: String
        get() = name.trim().substringBefore(' ').substringBefore(',').removeSuffix(".com").ifEmpty { symbol }

    val words: List<String> get() = listOf(keyword) + ALIASES[symbol].orEmpty()
}

// shortcut: a few names headlines use instead of the company's; upgrade to user-editable words when people ask
private val ALIASES = mapOf("GOOGL" to listOf("Google"), "GOOG" to listOf("Google"), "PG" to listOf("P&G"), "META" to listOf("Facebook"))

data class Feed(val name: String, val url: String, val on: Boolean = true)

/** Everything about headlines that needs no phone: URLs, parsing, matching, timing. Tested in NewsTest. */
object News {
    const val FINANCIAL_JUICE = "https://www.financialjuice.com/feed.ashx?xy=rss"
    const val BLOOMBERG = "https://www.bloomberg.com/feeds/markets/news.rss"
    const val CNBC = "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258"
    const val SEEKING_ALPHA = "Seeking Alpha"
    // Bloomberg's own public headline feed: headline and link, the article opens on bloomberg.com. CNBC starts off: one more feed means more rings.
    val DEFAULT_FEEDS = listOf(Feed("FinancialJuice", FINANCIAL_JUICE), Feed("Bloomberg", BLOOMBERG), Feed("CNBC Markets", CNBC, on = false))

    /**
     * Setup's "German sources", off by default: onvista's market news (dpa-AFX and own analyses), Handelsblatt and FAZ finance.
     * Tested 25.11.2026: finanzen.net refuses apps (403); Der Aktionär is mostly hype ("100 Prozent möglich"); wallstreet-online has no dates.
     */
    val GERMAN_FEEDS = listOf(
        Feed("onvista", "https://www.onvista.de/news/feed/rss.xml"),
        Feed("Handelsblatt", "https://www.handelsblatt.com/contentexport/feed/finanzen"),
        Feed("FAZ Finanzen", "https://www.faz.net/rss/aktuell/finanzen/"),
    )

    /** Ads some feeds slip between the news ("Black Week: 50 % Rabatt auf alle Abos"). */
    private val PROMO = Regex("""Rabatt|Black Week""", RegexOption.IGNORE_CASE)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun stockUrl(symbol: String) = "https://query2.finance.yahoo.com/v1/finance/search?q=${enc(symbol)}&newsCount=10&quotesCount=0"
    fun lookupUrl(query: String) = "https://query2.finance.yahoo.com/v1/finance/search?q=${enc(query)}&newsCount=0&quotesCount=10"
    /** When Yahoo refuses or has nothing: Google News for the company name. */
    fun googleUrl(f: Favorite) = "https://news.google.com/rss/search?q=${enc(f.keyword + " stock")}&hl=en-US&gl=US&ceid=US:en"

    /** Seeking Alpha's feed for one stock, tagged to it by their editors: analyst ratings, insider trades, company news. */
    fun seekingAlphaUrl(symbol: String) = "https://seekingalpha.com/api/sa/combined/${enc(symbol)}.xml"

    /** Keeps Seeking Alpha's news ("MarketCurrent" ids), drops its contributors' opinion articles ("Article" ids). */
    fun parseSeekingAlpha(xml: String): List<Item> = parseRss(xml, SEEKING_ALPHA).filter { "MarketCurrent:" in it.id }.map { it.copy(wire = false) }

    /** RSS 2.0. Items without title, http link or date are dropped. [feedName] labels items that carry no <source>. */
    fun parseRss(xml: String, feedName: String): List<Item> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            // user-added feeds are untrusted: refuse DOCTYPE, so no external entities (Android's parser may not know the flag)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val nodes = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).getElementsByTagName("item")
        return (0 until nodes.length).mapNotNull { i ->
            val e = nodes.item(i) as Element
            fun text(tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val source = text("source").ifEmpty { feedName }
            // FinancialJuice starts every headline with its name, Google News ends each with " - Publisher"
            val title = text("title").removePrefix("FinancialJuice:").removeSuffix(" - $source").trim()
            val link = text("link")
            val time = runCatching { ZonedDateTime.parse(text("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            if (title.isEmpty() || !link.startsWith("http") || time == null || PROMO.containsMatchIn(title)) return@mapNotNull null
            Item(id = feedName + ":" + text("guid").ifEmpty { link }, title = title, link = link, source = source, time = time, wire = true)
        }
    }

    /** Yahoo's search answer for one stock. Many of its results only brush the stock; [tag] sorts them out. */
    fun parseStock(json: String): List<Item> {
        val news = JSONObject(json).optJSONArray("news") ?: return emptyList()
        return (0 until news.length()).mapNotNull { i ->
            val o = news.getJSONObject(i)
            val title = o.optString("title").trim()
            val link = o.optString("link")
            val time = o.optLong("providerPublishTime") * 1000
            if (title.isEmpty() || !link.startsWith("http") || time <= 0) return@mapNotNull null
            // same id for every stock it turns up for, so an article about AMZN and GOOGL shows once
            Item("yahoo:" + o.optString("uuid").ifEmpty { link }, title, link, o.optString("publisher").ifEmpty { "Yahoo Finance" }, time)
        }
    }

    /** Today's price and yesterday's close for up to 20 symbols in one small answer (about 1 KB per symbol). */
    fun sparkUrl(symbols: List<String>) = "https://query2.finance.yahoo.com/v7/finance/spark?symbols=${enc(symbols.joinToString(","))}&range=1d&interval=1d"

    /** Yahoo's spark answer. Unknown symbols are simply missing; a quote without a usable close is dropped. */
    fun parseSpark(json: String): List<Quote> {
        val results = JSONObject(json).optJSONObject("spark")?.optJSONArray("result") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val meta = results.getJSONObject(i).optJSONArray("response")?.optJSONObject(0)?.optJSONObject("meta") ?: return@mapNotNull null
            val price = meta.optDouble("regularMarketPrice")
            val close = meta.optDouble("chartPreviousClose")
            val time = meta.optLong("regularMarketTime") * 1000
            if (!(price > 0) || !(close > 0) || time <= 0) return@mapNotNull null
            Quote(meta.optString("symbol"), price, close, time, meta.optString("exchangeTimezoneName", "UTC"), meta.optString("currency"))
        }
    }

    /** Stocks and funds matching what was typed, for the add field. */
    fun parseLookup(json: String): List<Favorite> {
        val quotes = JSONObject(json).optJSONArray("quotes") ?: return emptyList()
        return (0 until quotes.length()).map { quotes.getJSONObject(it) }
            .filter { it.optString("quoteType") in setOf("EQUITY", "ETF") && it.optString("symbol").isNotEmpty() }
            .map { Favorite(it.getString("symbol"), it.optString("shortname").ifEmpty { it.optString("longname") }.replace(Regex("\\s+"), " ").trim().ifEmpty { it.getString("symbol") }, it.optString("exchDisp")) }
    }

    /**
     * Does a headline name this stock? By company word, any case ("Amazon", "Google"), or by symbol in capitals ("AMZN", "$NOW").
     * Symbols under 3 letters only count by name: "PG" or "GE" turn up inside too many abbreviations.
     * Headlines that name the company are the ones about it: of 80 Yahoo results for 8 stocks, the rest was mostly fund lists and market wraps.
     */
    fun mentions(title: String, f: Favorite): Boolean {
        for (word in f.words) {
            if (word.length >= 3 && rx("w:$word") { Regex("(?<![A-Za-z0-9])${Regex.escape(word)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE) }.containsMatchIn(title)) return true
        }
        return f.symbol.length >= 3 && rx("s:${f.symbol}") { Regex("(?<![A-Za-z0-9])\\$?${Regex.escape(f.symbol)}(?![A-Za-z0-9])") }.containsMatchIn(title)
    }

    /** Each stock's patterns built once: tagging runs over hundreds of headlines whenever they or the stocks change. */
    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun rx(key: String, make: () -> Regex) = patterns.getOrPut(key, make)

    fun tag(item: Item, favorites: List<Favorite>): Item = item.copy(tickers = favorites.filter { mentions(item.title, it) }.map { it.symbol })

    /** Newest first, one entry per id (the newest copy), at most [cap]. */
    fun merge(old: List<Item>, new: List<Item>, cap: Int = 600): List<Item> =
        (new + old).distinctBy { it.id }.sortedByDescending { it.time }.take(cap)

    /** Worth a notification: not on screen yet and at most [maxAge] old (feeds resend old items after a hiccup). */
    fun fresh(fetched: List<Item>, known: Set<String>, now: Long, maxAge: Long = 2 * HOUR): List<Item> =
        fetched.filter { it.id !in known && now - it.time in -5 * MINUTE..maxAge }

    /** How long to leave a server alone after HTTP 429: its Retry-After seconds, else 5 minutes, between 1 and 30 minutes. */
    fun pause(retryAfter: String?): Long = ((retryAfter?.trim()?.toLongOrNull() ?: 300L).coerceIn(60L, 1800L)) * 1000

    private val NEW_YORK = ZoneId.of("America/New_York")

    // shortcut: fixed hours, no exchange holidays; upgrade when someone asks for their market's calendar
    /** Weekdays 02:00 to 18:00 New York time, Europe's open through US after-hours. */
    fun busy(now: Long): Boolean {
        val t = Instant.ofEpochMilli(now).atZone(NEW_YORK)
        return t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY && t.hour in 2 until 18
    }

    /** The wire every minute while markets move, every 15 minutes otherwise. Stock searches at most every 5 minutes. */
    fun nextCheck(now: Long) = now + if (busy(now)) MINUTE else 15 * MINUTE
    const val STOCK_INTERVAL = 5 * MINUTE
}
