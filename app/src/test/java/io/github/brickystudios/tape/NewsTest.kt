package io.github.brickystudios.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class NewsTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    private val watchlist = listOf(
        Favorite("INTC", "Intel Corporation"),
        Favorite("NOW", "ServiceNow, Inc."),
        Favorite("AMZN", "Amazon.com, Inc."),
        Favorite("GOOGL", "Alphabet Inc."),
        Favorite("PYPL", "PayPal Holdings, Inc."),
        Favorite("MSFT", "Microsoft Corporation"),
        Favorite("LMT", "Lockheed Martin Corporation"),
        Favorite("PG", "Procter & Gamble Company (The)"),
    )
    private fun fav(symbol: String) = watchlist.first { it.symbol == symbol }

    @Test fun `company keyword from Yahoo's short names`() {
        assertEquals(
            listOf("Intel", "ServiceNow", "Amazon", "Alphabet", "PayPal", "Microsoft", "Lockheed", "Procter"),
            watchlist.map { it.keyword },
        )
        assertEquals("XYZ", Favorite("XYZ", "").keyword)
    }

    @Test fun `wire feed parses, strips the FinancialJuice prefix and the Google publisher suffix`() {
        val items = News.parseRss(res("wire.xml"), "FinancialJuice")
        assertEquals(3, items.size) // no date and javascript: link are dropped
        assertEquals("Amazon shares rise 3% after cloud revenue beats estimates", items[0].title)
        assertEquals("FinancialJuice:101", items[0].id)
        assertEquals(at("2026-11-23T18:41:19Z"), items[0].time)
        assertEquals("FinancialJuice", items[0].source)
        assertTrue(items.all { it.wire })
        assertEquals("Nvidia stock soars as CEO returns", items[2].title)
        assertEquals("Example Wire", items[2].source)
    }

    @Test fun `Seeking Alpha keeps news items, drops opinion articles`() {
        val items = News.parseSeekingAlpha(res("sa.xml"))
        assertEquals(1, items.size)
        assertEquals("Example Corp upgraded to Buy at a broker", items[0].title)
        assertEquals("Seeking Alpha:https://seekingalpha.com/MarketCurrent:111", items[0].id)
        assertEquals(at("2026-11-23T20:09:08Z"), items[0].time)
        assertEquals("Seeking Alpha", items[0].source)
        assertFalse(items[0].wire)
    }

    @Test fun `feed with a DOCTYPE is refused`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title>
            <link>https://e.com</link><pubDate>Mon, 23 Nov 2026 17:00:00 GMT</pubDate></item></channel></rss>"""
        val parsed = runCatching { News.parseRss(evil, "x") }.getOrNull().orEmpty()
        assertTrue(parsed.none { "root:" in it.title })
    }

    @Test fun `stock news from Yahoo parses, and only headlines naming the stock count for it`() {
        val items = News.parseStock(res("stock.json"))
        assertEquals(2, items.size) // the one without a title is dropped
        assertEquals("yahoo:a1", items[0].id)
        assertEquals(emptyList<String>(), News.tag(items[0], watchlist).tickers) // "Who is buying AI chips": brushes AMZN only
        assertEquals(listOf("AMZN"), News.tag(items[1], watchlist).tickers)
        assertEquals(1795458967000L, items[0].time)
        assertEquals("Yahoo Finance", items[1].source) // empty publisher
        assertFalse(items[0].wire)
    }

    @Test fun `lookup keeps stocks and funds, drops options`() {
        assertEquals(listOf("AMZN", "AMZW"), News.parseLookup(res("stock.json")).map { it.symbol })
        assertEquals(listOf("NASDAQ", ""), News.parseLookup(res("stock.json")).map { it.exchange })
        assertEquals("Amazon.com, Inc.", News.parseLookup(res("stock.json"))[0].name)
    }

    @Test fun `wire headlines name stocks by company word or capital symbol`() {
        assertTrue(News.mentions("Amazon shares rise 3%", fav("AMZN")))
        assertTrue(News.mentions("AMZN, MSFT lead the Nasdaq higher", fav("AMZN")))
        assertTrue(News.mentions("NOW raises subscription guidance", fav("NOW")))
        assertTrue(News.mentions("\$PYPL jumps premarket", fav("PYPL")))
        assertTrue(News.mentions("Procter & Gamble cuts outlook", fav("PG")))
        assertTrue(News.mentions("Lockheed wins Army drone contract", fav("LMT")))
        assertTrue(News.mentions("Jim Cramer sees a Google bargain", fav("GOOGL")))
        assertTrue(News.mentions("P&G plans to cut factory jobs", fav("PG")))
        assertFalse(News.mentions("Markets now price two Fed cuts", fav("NOW")))
        assertFalse(News.mentions("PG&E files for rate increase", fav("PG")))
        assertFalse(News.mentions("Intelligence agencies warn of cyber attack", fav("INTC")))
        assertFalse(News.mentions("MSFTX fund rebalances", fav("MSFT")))
    }

    @Test fun `tag lists every stock a headline names`() {
        val wire = Item("w", "Alphabet and Amazon expand AI pact", "https://e.com", "FJ", 0, wire = true)
        assertEquals(listOf("AMZN", "GOOGL"), News.tag(wire, watchlist).tickers)
        assertEquals(emptyList<String>(), News.tag(wire.copy(title = "Fed holds rates"), watchlist).tickers)
    }

    @Test fun `merge keeps one copy per article, newest first`() {
        val a = Item("yahoo:1", "Chips", "https://e.com/1", "Y", 1000)
        val c = Item("yahoo:2", "Later", "https://e.com/2", "Y", 2000)
        assertEquals(listOf("yahoo:2", "yahoo:1"), News.merge(listOf(a), listOf(a, c)).map { it.id })
        assertEquals(1, News.merge(emptyList(), listOf(a, c), cap = 1).size)
    }

    @Test fun `lookup names lose Yahoo's padding`() {
        val json = """{"quotes":[{"quoteType":"EQUITY","symbol":"TCS.HA","shortname":"Example Holdings Inc.        R"}]}"""
        assertEquals("Example Holdings Inc. R", News.parseLookup(json)[0].name)
    }

    @Test fun `fresh skips what is on screen and what is old`() {
        val now = at("2026-11-23T18:45:00Z")
        val new = Item("n", "New", "https://e.com", "FJ", now - 3 * MINUTE)
        val known = Item("k", "Known", "https://e.com", "FJ", now - MINUTE)
        val old = Item("o", "Old", "https://e.com", "FJ", now - 3 * HOUR)
        assertEquals(listOf("n"), News.fresh(listOf(new, known, old), setOf("k"), now).map { it.id })
    }

    @Test fun `every minute on weekdays while markets move, every 15 minutes otherwise`() {
        val monday = at("2026-11-23T18:41:00Z") // 13:41 in New York
        assertTrue(News.busy(monday))
        assertEquals(monday + MINUTE, News.nextCheck(monday))
        assertFalse(News.busy(at("2026-11-23T23:30:00Z"))) // 18:30 in New York
        assertFalse(News.busy(at("2026-11-28T15:00:00Z"))) // Saturday
        assertEquals(at("2026-11-28T15:15:00Z"), News.nextCheck(at("2026-11-28T15:00:00Z")))
    }

    @Test fun `after HTTP 429 a server rests for its Retry-After, 5 minutes by default, 1 to 30 minutes`() {
        assertEquals(300_000L, News.pause(null))
        assertEquals(120_000L, News.pause(" 120 "))
        assertEquals(60_000L, News.pause("5"))
        assertEquals(1_800_000L, News.pause("86400"))
        assertEquals(300_000L, News.pause("Wed, 21 Oct 2026 07:28:00 GMT")) // date form: default
    }

    @Test fun `release versions compare by number`() {
        assertTrue(Releases.isNewer("v0.10.0", "0.9.2"))
        assertFalse(Releases.isNewer("v0.1.0", "0.1.0"))
    }
}
