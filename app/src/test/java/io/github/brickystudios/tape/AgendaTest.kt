package io.github.brickystudios.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgendaTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun `releases keep the big economies and the eurozone's medium ones`() {
        val r = Agenda.parseReleases(res("ff.json"))
        // the Australian CPI and the low-impact Fed speech stay out
        assertEquals(listOf("Flash Manufacturing PMI", "Flash Services PMI", "Core PCE Price Index m/m", "Prelim GDP q/q"), r.map { it.title })
        assertEquals(listOf("DE", "DE", "USD", "USD"), r.map { it.place })
        assertEquals(at("2026-11-25T13:30:00Z"), r[2].time)
        assertEquals("0.3%", r[2].forecast)
        assertEquals(2, Agenda.rows(r).size) // the two German PMIs at 9:30 are one row, the two US releases at 14:30 another
    }

    @Test fun `plain names for the shorthand`() {
        fun r(title: String, place: String = "USD") = Indicator(title, place, 0, "", "")
        assertEquals(R.string.ev_core_pce, Agenda.nameRes(r("Core PCE Price Index m/m")))
        assertEquals(R.string.ev_gdp, Agenda.nameRes(r("Prelim GDP q/q")))
        assertEquals(R.string.ev_cpi, Agenda.nameRes(r("CPI y/y")))
        assertEquals(R.string.ev_core_cpi, Agenda.nameRes(r("Core CPI m/m")))
        assertEquals(R.string.ev_fed, Agenda.nameRes(r("Federal Funds Rate")))
        assertEquals(R.string.ev_ecb, Agenda.nameRes(r("Main Refinancing Rate", "EUR")))
        assertEquals(R.string.ev_ecb_press, Agenda.nameRes(r("ECB Press Conference", "EUR")))
        assertEquals(R.string.ev_ifo, Agenda.nameRes(r("Ifo Business Climate", "DE")))
        assertNull(Agenda.nameRes(r("Main Refinancing Rate", "GBP")))
        assertEquals("Powell", Agenda.speaker(r("Fed Chair Powell Speaks")))
        assertNull(Agenda.speaker(r("Prelim GDP q/q")))
    }

    @Test fun `earnings with time slot, estimate and popularity`() {
        val e = Agenda.parseEarnings(res("earnings.json"))
        assertEquals(6, e.size)
        val mrvl = e.first { it.symbol == "MRVL" }
        assertEquals("Marvell Technology, Inc.", mrvl.name)
        assertEquals("AMC", mrvl.slot)
        assertEquals(1.1, mrvl.eps!!, 0.0)
        assertEquals(e.sortedBy { it.time }, e)
        assertEquals(emptyList<Earning>(), Agenda.parseEarnings("""{"finance":{"result":null,"error":null}}"""))
    }

    @Test fun `next report from today on`() {
        val e = Agenda.parseEarnings(res("earnings.json"))
        val now = at("2026-11-30T10:00:00Z")
        assertEquals("MRVL", Agenda.next(e, "MRVL", now, berlin)?.symbol)
        assertNull(Agenda.next(e, "MRVL", at("2026-12-03T10:00:00Z"), berlin))
        assertEquals(at("2026-11-29T23:00:00Z"), Agenda.dayStart(now, berlin))
        assertEquals(at("2026-12-01T23:00:00Z"), Agenda.dayStart(now, berlin, 2))
    }

    @Test fun `a report after it is out says beat, met or missed and how the stock took it`() {
        val json = """{"finance":{"result":{"earnings":[{"records":[
            {"ticker":"DELL","companyShortName":"Dell","startDateTime":1795554300000,"startDateTimeType":"TAS","quarter":"Q3","epsActual":9.45,"epsEstimate":6.59,"surprisePercent":43.46,"rank":1},
            {"ticker":"NNOX","companyShortName":"Nano-X","startDateTime":1795611925000,"startDateTimeType":"TAS","quarter":"Q3","epsActual":-0.14,"epsEstimate":-0.14,"surprisePercent":0.0,"rank":2},
            {"ticker":"DE","companyShortName":"Deere","startDateTime":1795604447000,"startDateTimeType":"TAS","quarter":"Q4","epsActual":3.89,"epsEstimate":4.05,"surprisePercent":-4.04,"rank":3},
            {"ticker":"NEXT","companyShortName":"Later","startDateTime":1795700000000,"startDateTimeType":"AMC","quarter":"Q3","epsEstimate":1.0,"epsActual":null,"rank":4}]}]}}}"""
        val e = Agenda.parseEarnings(json).associateBy { it.symbol }
        assertEquals(1, e.getValue("DELL").verdict)
        assertEquals(0, e.getValue("NNOX").verdict)
        assertEquals(-1, e.getValue("DE").verdict)
        assertEquals(null, e.getValue("NEXT").verdict)
        assertEquals(9.45, e.getValue("DELL").actual!!, 0.0)

        // Dell reported after Monday's close (16:05 New York): Monday's close is the base, Tuesday's price the answer
        val day = 24 * HOUR
        val mon = 1795530600000L // Mon 24.11.2026 09:30 New York
        val d = Daily(listOf(mon - day, mon, mon + day), listOf(100.0, 110.0, 125.0), 126.5, mon + day + 3 * HOUR)
        val dell = Agenda.react(e.getValue("DELL"), d)
        assertEquals(110.0, dell.base!!, 0.0)
        assertEquals(126.5, dell.last!!, 0.0)
        // no session since the report yet: nothing to say
        assertEquals(null, Agenda.react(e.getValue("DELL"), Daily(listOf(mon), listOf(110.0), 110.0, mon + 6 * HOUR + 30 * MINUTE)).base)
        assertEquals(null, Agenda.react(e.getValue("NEXT"), d).base)
    }
}

