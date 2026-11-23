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
        assertEquals(MarketStatus.PRE, Market.status(at("2026-11-23T13:00:00Z")))   // 8:00 NY
        assertEquals(MarketStatus.OPEN, Market.status(at("2026-11-23T14:30:00Z")))  // 9:30 NY
        assertEquals(MarketStatus.AFTER, Market.status(at("2026-11-23T21:00:00Z"))) // 16:00 NY
        assertEquals(MarketStatus.CLOSED, Market.status(at("2026-11-24T01:00:00Z"))) // 20:00 NY
        assertEquals(MarketStatus.CLOSED, Market.status(at("2026-11-28T16:00:00Z"))) // Saturday
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
