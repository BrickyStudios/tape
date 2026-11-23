package io.github.brickystudios.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AlertsTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()
    private fun quote(symbol: String, change: Double, time: String = "2026-11-23T20:00:00Z") =
        Quote(symbol, 100 + change, 100.0, at(time), "America/New_York", "USD")

    @Test fun `spark parses price, close and the exchange's day, unusable quotes drop out`() {
        val quotes = News.parseSpark(res("spark.json"))
        assertEquals(listOf("NVDA", "SAP.DE"), quotes.map { it.symbol })
        assertEquals(31.48, quotes[0].change, 0.01)
        assertEquals("2026-11-23", quotes[0].day)
        assertEquals("EUR", quotes[1].currency)
        assertEquals(emptyList<Quote>(), News.parseSpark("{}"))
    }

    @Test fun `events ring, opinion stays quiet`() {
        assertEquals(Kind.EARNINGS, Alerts.kind("Nvidia Q3 results beat estimates", "Reuters"))
        assertEquals(Kind.ANALYST, Alerts.kind("Morgan Stanley downgrades Tesla to Underweight", "Barron's"))
        assertEquals(Kind.DEAL, Alerts.kind("Microsoft to acquire gaming studio for \$2 billion", "Bloomberg"))
        assertEquals(Kind.MANAGEMENT, Alerts.kind("Huang returns as Nvidia CEO after board reversal", "Reuters"))
        assertEquals(Kind.MANAGEMENT, Alerts.kind("Jensen Huang Is Back As Nvidia CEO. Stock Jumps.", "IBD"))
        assertEquals(Kind.LEGAL, Alerts.kind("FTC sues Amazon over Prime cancellations", "AP"))
        assertEquals(Kind.HALT, Alerts.kind("Trading halted in shares of Example Corp", "Nasdaq"))
        assertEquals(Kind.CAPITAL, Alerts.kind("Apple announces \$110 billion buyback", "CNBC"))
        assertEquals(Kind.MOVE, Alerts.kind("Nvidia stock soars 30% on record orders", "MarketWatch"))
        assertEquals(Kind.RELEASE, Alerts.kind("Example Corp opens new plant", "Business Wire"))
        assertNull(Alerts.kind("Prediction: Here's What a \$5,000 Investment in Nvidia Stock Today Will Be Worth", "Motley Fool"))
        assertNull(Alerts.kind("PayPal's Member Base Rises: Can Revenues Accelerate?", "Zacks"))
        assertNull(Alerts.kind("3 Growth Stocks To Watch With Earnings Growth Up To 81%", "Simply Wall St"))
        assertNull(Alerts.kind("Nvidia CEO says demand is strong", "Reuters"))
        assertNull(Alerts.kind("Stock Market Today: Nasdaq rises with Nvidia", "IBD"))
    }

    @Test fun `one push per headline, stock events before alert words`() {
        val words = Alerts.DEFAULT_WORDS
        val event = Item("w1", "Tariff ruling: FTC sues Amazon", "https://e.com/1", "FinancialJuice", 0, listOf("AMZN"), wire = true)
        val macro = Item("w2", "Fed Interest Rate Decision Actual 4.50%", "https://e.com/2", "FinancialJuice", 0, wire = true)
        val quietStock = Item("y1", "Is Amazon a buy?", "https://e.com/3", "Motley Fool", 0, listOf("AMZN"))
        val quietWire = Item("w3", "US 2-Year Note Auction", "https://e.com/4", "FinancialJuice", 0, wire = true)
        val (events, market) = Alerts.decide(listOf(event, event, macro, quietStock, quietWire), words)
        assertEquals(listOf("w1" to Kind.LEGAL), events.map { it.first.id to it.second }) // duplicate dropped, alert word "Tariff" not a second push
        assertEquals(listOf("w2" to "Rate Decision"), market.map { it.first.id to it.second })
    }

    @Test fun `alert words match whole words in any case`() {
        val words = Alerts.DEFAULT_WORDS
        assertEquals("Rate Decision", Alerts.alertWord("Fed Interest Rate Decision Actual 4.50% (Forecast 4.50%)", words))
        assertEquals("CPI", Alerts.alertWord("US CPI YoY Actual 3.0% (Forecast 3.1%)", words))
        assertNull(Alerts.alertWord("Accurate rate of growth in pricing", words)) // no "Rate Decision", no "CPI" inside a word
        assertNull(Alerts.alertWord("US 2-Year Note Auction", words))
        assertEquals("a.b", Alerts.alertWord("headline with a.b inside", listOf("a.b"))) // regex characters are literal
        assertNull(Alerts.alertWord("headline with axb inside", listOf("a.b")))
    }

    @Test fun `added words are trimmed and bounded`() {
        assertEquals("rate cut", Alerts.cleanWord("  rate   cut "))
        assertNull(Alerts.cleanWord(" "))
        assertNull(Alerts.cleanWord("x"))
        assertNull(Alerts.cleanWord("y".repeat(41)))
    }

    @Test fun `a price step rings once per day and direction`() {
        val t = 5
        val first = Alerts.priceAlerts(listOf(quote("NVDA", 5.1)), t, emptyMap())
        assertEquals(listOf(1), first.map { it.second })
        val rung = Alerts.keepRung(emptyMap(), listOf(quote("NVDA", 5.1)), t, first)
        assertEquals(emptyList<Pair<Quote, Int>>(), Alerts.priceAlerts(listOf(quote("NVDA", 4.8)), t, rung)) // back under
        assertEquals(emptyList<Pair<Quote, Int>>(), Alerts.priceAlerts(listOf(quote("NVDA", 5.2)), t, rung)) // same step again
        assertEquals(listOf(2), Alerts.priceAlerts(listOf(quote("NVDA", 10.3)), t, rung).map { it.second }) // next step
        assertEquals(listOf(1), Alerts.priceAlerts(listOf(quote("NVDA", -5.5)), t, rung).map { it.second }) // other direction
        assertEquals(listOf(2), Alerts.priceAlerts(listOf(quote("TSLA", 12.0)), t, emptyMap()).map { it.second }) // jump rings once
        assertEquals(emptyList<Pair<Quote, Int>>(), Alerts.priceAlerts(listOf(quote("PG", 4.9)), t, emptyMap()))
        assertEquals(listOf(1), Alerts.priceAlerts(listOf(quote("NVDA", 5.2, "2026-11-24T20:00:00Z")), t, rung).map { it.second }) // new day
    }

    @Test fun `old days fall out of the remembered steps`() {
        val yesterday = mapOf("NVDA|2026-11-20|up|5" to 1)
        assertEquals(emptyMap<String, Int>(), Alerts.keepRung(yesterday, listOf(quote("NVDA", 1.0)), 5, emptyList()))
    }

    @Test fun `quiet from 22 to 7`() {
        assertTrue(Alerts.quiet(22) && Alerts.quiet(23) && Alerts.quiet(0) && Alerts.quiet(6))
        assertTrue(!Alerts.quiet(7) && !Alerts.quiet(15) && !Alerts.quiet(21))
    }

    @Test fun `new headlines count after the last look`() {
        val items = listOf(Item("a", "A", "https://e.com", "S", 2000), Item("b", "B", "https://e.com", "S", 1000))
        assertEquals(1, Alerts.newCount(items, 1500))
        assertEquals(0, Alerts.newCount(items, 0)) // never looked: no badge on first start
        assertEquals(0, Alerts.newCount(emptyList(), 1500))
    }
}
