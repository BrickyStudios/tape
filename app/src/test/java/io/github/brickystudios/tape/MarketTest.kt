package io.github.brickystudios.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime
import java.util.Locale

class MarketTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun `chart parses closes without gaps and the day figures`() {
        val c = Market.parseChart(res("chart.json"))!!
        assertEquals(listOf(160.1, 162.5, 165.05), c.closes)
        assertEquals(3, c.times.size)
        assertEquals(1795441500000L, c.times.last())
        assertEquals(125.53, c.previousClose, 0.0)
        assertEquals(156.93, c.dayLow, 0.0)
        assertEquals(243.37, c.yearHigh, 0.0)
        assertEquals(772485268L, c.volume)
        assertEquals("NVIDIA Corporation", c.name)
        assertEquals("NasdaqGS", c.exchange)
        assertNull(Market.parseChart("""{"chart":{"result":[{"meta":{}}]}}"""))
        assertNull(Market.parseChart("{}"))
    }

    @Test fun `points keep symbols with at least two closes`() {
        val p = Market.parsePoints(res("points.json"))
        assertEquals(setOf("^GSPC"), p.keys)
        assertEquals(listOf(7100.5, 7180.2, 7237.17), p["^GSPC"])
    }

    @Test fun `US session by New York time`() {
        assertEquals(MarketStatus.PRE, Market.status(Region.US, at("2026-11-23T13:00:00Z")))   // 8:00 NY
        assertEquals(MarketStatus.OPEN, Market.status(Region.US, at("2026-11-23T14:30:00Z")))  // 9:30 NY
        assertEquals(MarketStatus.AFTER, Market.status(Region.US, at("2026-11-23T21:00:00Z"))) // 16:00 NY
        assertEquals(MarketStatus.CLOSED, Market.status(Region.US, at("2026-11-24T01:00:00Z"))) // 20:00 NY
        assertEquals(MarketStatus.CLOSED, Market.status(Region.US, at("2026-11-28T16:00:00Z"))) // Saturday
    }

    @Test fun `what trades now comes first`() {
        val morning = at("2026-11-24T08:36:00Z") // Tuesday 9:36 Frankfurt, 3:36 New York, 17:36 Tokyo
        assertEquals(MarketStatus.OPEN, Market.status(Region.EUROPE, morning))
        assertEquals(MarketStatus.CLOSED, Market.status(Region.US, morning))
        assertEquals(MarketStatus.CLOSED, Market.status(Region.ASIA, morning))
        assertEquals(listOf(Region.EUROPE, Region.ASIA, Region.US), Market.order(morning)) // Asia closed at 9:00 Frankfurt, the US last night
        assertEquals(listOf(Region.EUROPE, Region.US, Region.ASIA), Market.order(at("2026-11-24T09:05:00Z"))) // US pre-market from 10:00 Frankfurt
        assertEquals(listOf(Region.US, Region.EUROPE, Region.ASIA), Market.order(at("2026-11-24T14:45:00Z"))) // both open, the US opened last
        assertEquals(Region.ASIA, Market.order(at("2026-11-25T01:00:00Z")).first()) // 10:00 Tokyo
        assertEquals(at("2026-11-24T16:30:00Z"), Market.nextChange(Region.EUROPE, morning)) // closes 17:30 Frankfurt
        assertEquals(at("2026-11-30T08:00:00Z"), Market.nextChange(Region.EUROPE, at("2026-11-28T12:00:00Z"))) // Saturday: Monday 9:00
        assertEquals(at("2026-11-23T21:00:00Z"), Market.lastChange(Region.US, morning)) // Monday's close
    }

    @Test fun `region by exchange time zone, none for what trades around the clock`() {
        assertEquals(Region.EUROPE, Market.region("SAP.DE", "Europe/Berlin"))
        assertEquals(Region.US, Market.region("AAPL", "America/New_York"))
        assertEquals(Region.ASIA, Market.region("^N225", "Asia/Tokyo"))
        assertNull(Market.region("ES=F", "America/New_York"))
        assertNull(Market.region("EURUSD=X", "Europe/London"))
        assertNull(Market.region("BTC-USD", "UTC"))
    }

    @Test fun `one answer gives the session line and the price before or after it`() {
        val json = res("prepost.json")
        val p = Market.parsePoints(json)
        assertEquals(listOf(100.0, 101.0, 102.0), p["AAPL"]) // pre-market 99 and after-hours 103 stay off the line
        assertEquals(listOf(25300.0, 25310.0), p["^GDAXI"])
        assertNull(p["PRE"]) // nothing in the session yet
        val e = Market.parseExt(json)
        assertEquals(setOf("AAPL", "PRE"), e.keys) // MID trades in the session, the index has no extra hours
        val after = e.getValue("AAPL")
        assertEquals(Ext(103.0, 102.0, 2_100_000L, pre = false), after)
        assertEquals(0.98, after.change, 0.01)
        assertEquals(Ext(51.0, 49.0, 600_000L, pre = true), e.getValue("PRE"))
    }

    @Test fun `morning overview once a weekday, when the DAX has prices from after the open`() {
        val now = at("2026-11-24T08:26:00Z") // 9:26 Frankfurt
        val dax = Quote("^GDAXI", 25300.0, 25337.0, at("2026-11-24T08:11:00Z"), "Europe/Berlin", "EUR")
        assertEquals("2026-11-24", Market.briefDue(now, listOf(dax), ""))
        assertNull(Market.briefDue(now, listOf(dax), "2026-11-24")) // sent today
        assertNull(Market.briefDue(now, listOf(dax.copy(time = at("2026-11-23T16:30:00Z"))), "")) // still yesterday's close
        assertNull(Market.briefDue(now, emptyList(), ""))
        assertNull(Market.briefDue(at("2026-11-28T09:30:00Z"), listOf(dax), "")) // Saturday
        assertNull(Market.briefDue(at("2026-11-24T13:00:00Z"), listOf(dax), "")) // 14:00: too late for a morning overview
    }

    @Test fun `numbers follow the phone language`() {
        assertEquals("165,05 $", Market.money(165.05, "USD", Locale.GERMANY).replace(' ', ' '))
        assertEquals("$165.05", Market.money(165.05, "USD", Locale.US))
        assertEquals("186,34 €", Market.money(186.34, "EUR", Locale.GERMANY).replace(' ', ' '))
        assertEquals("12.34 XYZ", Market.money(12.34, "XYZ", Locale.US))
        assertEquals("7.237,17", Market.number(7237.17, Locale.GERMANY))
        assertEquals("1.0906", Market.number(1.0906, Locale.US))
        assertEquals("772,5 Mio.", Market.compact(772_485_268, Locale.GERMANY))
        assertEquals("772.5M", Market.compact(772_485_268, Locale.US))
        assertEquals("1.2B", Market.compact(1_234_000_000, Locale.US))
        assertEquals("950", Market.compact(950, Locale.US))
    }

    @Test fun `percent has a sign, except when the move rounds to zero`() {
        assertEquals("+31.5\u202F%", percent(31.48, Locale.US))
        assertEquals("−0.6\u202F%", percent(-0.6, Locale.US))
        assertEquals("0.0\u202F%", percent(-0.04, Locale.US))
        assertEquals("0,0\u202F%", percent(0.03, Locale.GERMANY))
    }

    @Test fun `tone reads rather good, rather bad or unclear`() {
        assertEquals(1, Alerts.tone("Nvidia beats estimates and raises guidance"))
        assertEquals(1, Alerts.tone("Morgan Stanley upgrades Apple"))
        assertEquals(-1, Alerts.tone("Tesla misses delivery estimates"))
        assertEquals(-1, Alerts.tone("FTC sues Amazon over Prime"))
        assertEquals(0, Alerts.tone("Microsoft beats estimates but cuts outlook"))
        assertEquals(0, Alerts.tone("Huang returns as Nvidia CEO"))
    }
}
