package io.github.brickystudios.tape

import org.json.JSONObject
import java.net.URLEncoder

/** How often Reddit's stock subreddits named a ticker in 24 hours, and the day before, from ApeWisdom. */
data class Mention(val symbol: String, val name: String, val mentions: Int, val before: Int, val rank: Int)

/** A post from r/wallstreetbets or r/mauerstrassenwetten. */
data class Post(val id: String, val sub: String, val title: String, val flair: String, val score: Int, val comments: Int, val time: Long) {
    val link: String get() = "https://www.reddit.com/r/$sub/comments/$id/"
}

/**
 * What the two communities talk about. The MSW Daily App, built by r/mauerstrassenwetten for itself, shows the 5 most named tickers
 * with their move; Tape does the same from ApeWisdom. Posts come from Arctic Shift, a free Reddit archive: Reddit blocks apps without a login.
 */
object Reddit {
    const val MENTIONS_URL = "https://apewisdom.io/api/v1.0/filter/all-stocks/page/1"
    const val INTERVAL = 30 * MINUTE
    private const val ARCHIVE = "https://arctic-shift.photon-reddit.com/api/posts/search"

    /** Flairs with substance: research, news, strategy, discussion, dates. Memes, gains, losses and YOLOs stay out. */
    private val SOLID = setOf("dd", "news", "fällige sorgfalt (dd)", "nachrichten", "strategie", "diskussion", "finanztermine")

    /** Sub, flair to ask for (null: all, filtered here) and how many days back. WSB posts 100 times in 8 hours, MSW in about 10 days. */
    val SOURCES = listOf(Triple("wallstreetbets", "DD", 2), Triple("wallstreetbets", "News", 2), Triple("mauerstrassenwetten", null, 4))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    val EMOJI = Regex("[\\p{So}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")

    fun postsUrl(sub: String, flair: String?, now: Long, days: Int): String =
        "$ARCHIVE?subreddit=$sub&after=${(now - days * 24 * HOUR) / 1000}&limit=100&fields=id,subreddit,title,link_flair_text,score,num_comments,created_utc,selftext" +
            (flair?.let { "&link_flair_text=" + enc(it) } ?: "")

    fun parseMentions(json: String): List<Mention> {
        val a = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map(a::getJSONObject).mapNotNull { o ->
            val symbol = o.optString("ticker").trim()
            // ApeWisdom sends the numbers as strings ("mentions": "1899")
            val mentions = o.optString("mentions").toIntOrNull() ?: return@mapNotNull null
            if (symbol.isEmpty()) null
            else Mention(symbol, o.optString("name").replace("&amp;", "&").trim(), mentions, o.optString("mentions_24h_ago").toIntOrNull() ?: 0, o.optString("rank").toIntOrNull() ?: 0)
        }.sortedBy { it.rank }
    }

    /** Posts with a solid flair; removed and deleted ones drop out. */
    fun parsePosts(json: String): List<Post> {
        val a = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).map(a::getJSONObject).mapNotNull { o ->
            val flair = o.optString("link_flair_text").trim()
            val body = o.optString("selftext")
            // "🌎 | 🦆 Erntenkalender - KW 48" → "Erntenkalender - KW 48": emoji and the separators left behind go, the words stay
            val title = o.optString("title").replace(EMOJI, "").replace(Regex("\\s+"), " ").trim().trimStart('|', ' ', '-').trim()
            if (flair.lowercase() !in SOLID || body == "[removed]" || body == "[deleted]" || title.isEmpty()) return@mapNotNull null
            Post(o.optString("id"), o.optString("subreddit"), title, flair, o.optInt("score"), o.optInt("num_comments"), o.optLong("created_utc") * 1000)
        }
    }

    /** The most discussed first: a comment counts twice an upvote, so a debated DD beats a quick headline. */
    fun top(posts: List<Post>, sub: String, n: Int = 3): List<Post> =
        posts.filter { it.sub.equals(sub, ignoreCase = true) }.distinctBy { it.id }.sortedByDescending { it.score + 2 * it.comments }.take(n)

    /** Mentions today against the day before: 4.4 for "4× as often", null when it was not named the day before. */
    fun factor(m: Mention): Double? = if (m.before > 0) m.mentions.toDouble() / m.before else null
}
