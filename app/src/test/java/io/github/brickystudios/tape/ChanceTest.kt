package io.github.brickystudios.tape

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChanceTest {
    private fun spark(symbol: String, closes: List<Double?>, meta: JSONObject = JSONObject()) = JSONObject().put(
        "spark", JSONObject().put(
            "result", JSONArray().put(
                JSONObject().put("symbol", symbol).put(
                    "response", JSONArray().put(
                        JSONObject().put("meta", meta).put("indicators", JSONObject().put("quote", JSONArray().put(JSONObject().put("close", JSONArray(closes.map { it ?: JSONObject.NULL }))))),
                    ),
                ),
            ),
        ),
    ).toString()

    @Test fun `average needs the full window`() {
        assertEquals(2.5, Chance.average(listOf(1.0, 2.0, 3.0, 4.0), 4)!!, 1e-9)
        assertEquals(3.5, Chance.average(listOf(1.0, 2.0, 3.0, 4.0), 2)!!, 1e-9)
        assertNull(Chance.average(listOf(1.0, 2.0), 3))
    }

    @Test fun `rsi is 100 without losses, 0 without gains, near 50 when they balance`() {
        val up = (1..30).map { it.toDouble() }
        assertEquals(100.0, Chance.rsi(up)!!, 1e-9)
        assertEquals(0.0, Chance.rsi(up.reversed())!!, 1e-9)
        val zigzag = (0..40).map { if (it % 2 == 0) 100.0 else 101.0 }
        assertEquals(50.0, Chance.rsi(zigzag)!!, 5.0)
        assertNull(Chance.rsi(up.take(14)))
    }

    @Test fun `signs follow the rules of thumb`() {
        val l = Levels(avg200 = 100.0, low = 80.0, high = 120.0, rsi = 45.0)
        assertEquals(emptyList<Sign>(), Chance.signs(l, 110.0))
        assertEquals(listOf(Sign.AVG200), Chance.signs(l, 103.0))
        assertEquals(listOf(Sign.AVG200, Sign.DRAWDOWN), Chance.signs(l, 95.0))
        assertEquals(listOf(Sign.DRAWDOWN), Chance.signs(l, 90.0)) // far under the line is a falling trend, not a pullback
        assertEquals(listOf(Sign.LOW, Sign.DRAWDOWN), Chance.signs(l, 84.0))
        assertEquals(listOf(Sign.OVERSOLD), Chance.signs(l.copy(rsi = 25.0), 110.0))
        assertEquals(emptyList<Sign>(), Chance.signs(l.copy(avg200 = null), 103.0))
    }

    @Test fun `parse takes the year range from meta, skips gaps and short histories`() {
        val closes = (1..250).map { 100.0 + it % 7 } + null + 120.0
        val l = Chance.parse(spark("SAP.DE", closes, JSONObject().put("fiftyTwoWeekLow", 90.0).put("fiftyTwoWeekHigh", 125.0))).getValue("SAP.DE")
        assertEquals(90.0, l.low, 0.0)
        assertEquals(125.0, l.high, 0.0)
        assertEquals((closes.filterNotNull().takeLast(200)).average(), l.avg200!!, 1e-9)
        val young = Chance.parse(spark("NEW", (1..60).map { it.toDouble() })).getValue("NEW")
        assertNull(young.avg200)
        assertEquals(1.0, young.low, 0.0)
        assertEquals(60.0, young.high, 0.0)
        assertEquals(emptyMap<String, Levels>(), Chance.parse(spark("IPO", listOf(1.0, 2.0))))
        assertEquals(emptyMap<String, Levels>(), Chance.parse("{}"))
    }

    @Test fun `distance is signed percent`() {
        assertEquals(-4.0, Chance.distance(96.0, 100.0), 1e-9)
        assertEquals(25.0, Chance.distance(125.0, 100.0), 1e-9)
    }

    @Test fun `sell signals mirror the buying ones`() {
        val l = Levels(avg200 = 100.0, low = 80.0, high = 140.0, rsi = 75.0, eps = 2.0)
        assertEquals(listOf(Sign.OVERBOUGHT, Sign.STRETCHED, Sign.PRICEY), Chance.signs(l, 130.0))
        assertEquals(65.0, Chance.pe(l, 130.0)!!, 1e-9)
        assertNull(Chance.pe(l.copy(eps = -1.0), 130.0)) // a loss has no P/E
        assertEquals(emptyList<Sign>(), Chance.signs(l.copy(rsi = 50.0, eps = 4.0), 115.0))
    }

    @Test fun `an owned stock leads with its sell signal, a watched one with its buying chance`() {
        val both = listOf(Sign.DRAWDOWN, Sign.OVERBOUGHT)
        assertEquals(Sign.OVERBOUGHT, Chance.lead(both, own = true))
        assertEquals(Sign.DRAWDOWN, Chance.lead(both, own = false))
        assertEquals(Sign.DRAWDOWN, Chance.lead(listOf(Sign.DRAWDOWN), own = true))
        assertNull(Chance.lead(emptyList(), own = true))
    }

    @Test fun `trailing earnings add up the last four reported quarters`() {
        fun e(q: Int, a: Double?) = Earning("AAPL", "Apple", q * 1000L, "TAS", "Q$q", null, 0, actual = a)
        val eps = Chance.trailingEps(listOf(e(1, 1.0), e(2, 2.0), e(3, 3.0), e(4, 4.0), e(5, 5.0), e(6, null)) + Earning("NEW", "New", 1, "", "", null, 0, actual = 1.0))
        assertEquals(mapOf("AAPL" to 14.0), eps)
    }
}

