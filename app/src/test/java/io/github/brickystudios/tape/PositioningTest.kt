package io.github.brickystudios.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositioningTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun `net contracts per group, newest week first`() {
        val c = Positioning.parse(res("cot.json"))
        assertEquals(12, c.size)
        assertEquals(Cot("2026-11-17", 895_049, -234_978), c[0])
    }

    @Test fun `a bet against its own history`() {
        val c = Positioning.parse(res("cot.json"))
        val managers = Positioning.read(c, Cot::managers)!!
        assertEquals(895_049L, managers.net)
        assertEquals(-0.007, managers.change!!, 0.001) // 0.7 % smaller than 4 weeks before (901.377 on 20 Oct)
        val funds = Positioning.read(c, Cot::funds)!!
        assertEquals(true, funds.rank <= 10) // the smallest short of these 12 weeks
        assertNull(Positioning.read(emptyList(), Cot::funds))
        // a flip from short to long has no percentage
        assertNull(Positioning.read(listOf(Cot("b", 0, 10), Cot("a", 0, -5), Cot("a", 0, -5), Cot("a", 0, -5), Cot("a", 0, -5)), Cot::funds)!!.change)
    }
}
