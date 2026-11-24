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
}
