package io.github.brickystudios.tape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedditTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun `mentions by rank with the day before`() {
        val m = Reddit.parseMentions(res("ape.json"))
        assertEquals(listOf("NVDA", "SPY", "QQQ", "HR"), m.map { it.symbol })
        assertEquals(Mention("NVDA", m[0].name, 1899, 435, 1), m[0])
        assertEquals(4.37, Reddit.factor(m[0])!!, 0.01)
        assertNull(Reddit.factor(m[0].copy(before = 0)))
        // numbers as strings, the way the API documents them
        assertEquals(2, Reddit.parseMentions("""{"results":[{"ticker":"ABC","name":"A","mentions":"2","mentions_24h_ago":"1","rank":"301"}]}""")[0].mentions)
    }

    @Test fun `solid posts only, most discussed first`() {
        val p = Reddit.parsePosts(res("posts.json"))
        // the meme, the removed strategy post and the removed copy of the MSW question drop out; the flair's leading space does not matter
        assertEquals(listOf("1y3qi6t", "1y3n3u9", "1y454t6"), p.map { it.id })
        assertEquals("Diskussion", p[2].flair)
        assertEquals(listOf("1y3qi6t", "1y3n3u9"), Reddit.top(p, "wallstreetbets").map { it.id })
        assertEquals(listOf("1y454t6"), Reddit.top(p, "mauerstrassenwetten", 1).map { it.id })
        assertEquals("https://www.reddit.com/r/wallstreetbets/comments/1y3qi6t/", p[0].link)
        val kalender = """{"data":[{"id":"k","subreddit":"mauerstrassenwetten","title":"\uD83C\uDF0E | \uD83E\uDD86 Erntenkalender - KW 48","link_flair_text":"Finanztermine","score":9,"num_comments":4,"created_utc":1,"selftext":""}]}"""
        assertEquals("Erntenkalender - KW 48", Reddit.parsePosts(kalender)[0].title)
    }

    @Test fun `archive asks for a flair and a time window`() {
        val u = Reddit.postsUrl("wallstreetbets", "DD", 1_795_500_000_000, 2)
        assertEquals(true, u.contains("subreddit=wallstreetbets") && u.contains("link_flair_text=DD") && u.contains("after=1795327200"))
    }
}
