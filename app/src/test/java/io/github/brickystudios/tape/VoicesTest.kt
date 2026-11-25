package io.github.brickystudios.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicesTest {
    private val atom = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
 <title>Maxim Investiert</title>
 <entry>
  <id>yt:video:abc123</id>
  <yt:videoId>abc123</yt:videoId>
  <title>🚨 Der Aktienmarkt dreht komplett (Nvidia)</title>
  <published>2026-11-23T19:22:20+00:00</published>
  <media:group><media:title>Der Aktienmarkt dreht komplett (Nvidia)</media:title></media:group>
 </entry>
 <entry><yt:videoId>nodate</yt:videoId><title>Ohne Datum</title></entry>
</feed>"""

    private val nitter = """<rss><channel><title>Maxim / @MaximInvestiert</title>
<item><title>Dank Axon ist mein Portfolio grün 😂 https://finance.yahoo.com/x</title><pubDate>Wed, 25 Nov 2026 17:48:05 GMT</pubDate><link>https://nitter.kareem.one/MaximInvestiert/status/2125647729355280558#m</link></item>
<item><title>Black Friday sale ends Saturday! Up to 25% off the first year</title><pubDate>Wed, 25 Nov 2026 17:10:00 GMT</pubDate><link>https://nitter.kareem.one/x/status/3#m</link></item>
<item><title>RT by @MaximInvestiert: someone else</title><pubDate>Wed, 25 Nov 2026 17:00:00 GMT</pubDate><link>https://nitter.kareem.one/x/status/1#m</link></item>
<item><title>R to @someone: a reply</title><pubDate>Wed, 25 Nov 2026 16:00:00 GMT</pubDate><link>https://nitter.kareem.one/MaximInvestiert/status/2#m</link></item>
</channel></rss>"""

    @Test fun `a channel's videos open on YouTube, entries without a date are dropped`() {
        val v = Voices.parseYouTube(atom, "Maxim Investiert")
        assertEquals(1, v.size)
        assertEquals("yt:abc123", v[0].id)
        assertEquals("https://www.youtube.com/watch?v=abc123", v[0].link)
        assertEquals("Der Aktienmarkt dreht komplett (Nvidia)", v[0].title)
        assertEquals("Maxim Investiert", v[0].source)
    }

    @Test fun `posts from Nitter keep the account's own, open on x_com`() {
        val p = Voices.parseNitter(nitter, "Maxim Investiert", "MaximInvestiert")
        assertEquals(listOf("x:2125647729355280558"), p.map { it.id })
        assertEquals("https://x.com/MaximInvestiert/status/2125647729355280558", p[0].link)
        assertEquals("Dank Axon ist mein Portfolio grün", p[0].title)
    }

    @Test fun `handles and links are read the way people paste them`() {
        assertEquals("MaximInvestiert", Voices.xHandle("@MaximInvestiert"))
        assertEquals("MaximInvestiert", Voices.xHandle("https://x.com/MaximInvestiert"))
        assertEquals("unusual_whales", Voices.xHandle("twitter.com/unusual_whales"))
        assertNull(Voices.xHandle("not a handle at all"))
        assertEquals("UCNLPJZjElxcb6PSGkyeNeOg", Voices.channelId("https://www.youtube.com/channel/UCNLPJZjElxcb6PSGkyeNeOg"))
        assertEquals("MaximInvestiert", Voices.youtubeHandle("https://www.youtube.com/@MaximInvestiert"))
        assertEquals("Finanzfluss", Voices.youtubeHandle("@Finanzfluss"))
        val page = """<link rel="canonical" href="https://www.youtube.com/channel/UCeARcCUiZg79SQQ-2_XNlXQ"><meta property="og:title" content="Finanzfluss">"""
        assertEquals("UCeARcCUiZg79SQQ-2_XNlXQ" to "Finanzfluss", Voices.parseChannelPage(page))
    }

    @Test fun `videos and posts are tagged to every stock they name, yours first`() {
        val mine = listOf(Favorite("SAP.DE", "SAP SE"), Favorite("TTD", "The Trade Desk, Inc."))
        assertEquals(listOf("NVDA"), Voices.symbols("Der Aktienmarkt dreht komplett (Nvidia)", mine))
        assertEquals(listOf("AXON"), Voices.symbols("${'$'}AXON, tu das bitte nicht", mine))
        // SAP is yours as SAP.DE: no second SAP from the list or the cashtag
        assertEquals(listOf("SAP.DE"), Voices.symbols("SAP und ${'$'}SAP: was jetzt?", mine))
        assertEquals(listOf("RHM.DE", "IFX.DE"), Voices.symbols("Rheinmetall oder Infineon kaufen?", mine).sorted().reversed())
        // "The Trade Desk" matches by "Trade", never by "The"
        assertEquals(emptyList<String>(), Voices.symbols("The market is open", mine))
        assertEquals(emptyList<String>(), Voices.symbols("Intelligence is overrated", mine))
    }

    @Test fun `the talk line counts the last day, most named first`() {
        val now = 100 * HOUR
        fun v(t: Long, vararg s: String) = Item("x:$t", "t", "l", "s", now - t * HOUR, s.toList())
        assertEquals(listOf("NVDA" to 2, "TSLA" to 1), Voices.talk(listOf(v(1, "NVDA", "TSLA"), v(2, "NVDA"), v(30, "AAPL")), now))
    }
}

