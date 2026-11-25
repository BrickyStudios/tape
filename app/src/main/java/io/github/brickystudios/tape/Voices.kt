package io.github.brickystudios.tape

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** A YouTube channel ([youtube] true, [id] its channel id) or an X account ([id] its handle) whose posts Tape shows, quietly. */
data class Voice(val youtube: Boolean, val id: String, val name: String)

/**
 * Investors on YouTube and X, both off by default (Setup). Their videos and posts show under Market and, when they name one
 * of your stocks, on its page. They never ring. Tested in VoicesTest.
 */
object Voices {
    val DEFAULT = listOf(
        Voice(true, "UCNLPJZjElxcb6PSGkyeNeOg", "Maxim Investiert"),
        Voice(true, "UCeARcCUiZg79SQQ-2_XNlXQ", "Finanzfluss"),
        Voice(true, "UCpV9LpCg4uwYCQZ3qoEn_YQ", "Aktien mit Kopf"),
        Voice(true, "UCdYgvhtx9v7mWiSFxepLM2w", "Tim Schäfer"),
        Voice(true, "UCUvvj5lwue7PspotMDjk5UA", "Meet Kevin"),
        Voice(true, "UCGy7SkBjcIAgTiwkXEtPnYg", "Andrei Jikh"),
        Voice(true, "UCJwKCyEIFHwUOPQQ-4kC1Zw", "Tom Nash"),
        Voice(false, "MaximInvestiert", "Maxim Investiert"),
        Voice(false, "KobeissiLetter", "The Kobeissi Letter"),
        Voice(false, "unusual_whales", "Unusual Whales"),
        Voice(false, "LizAnnSonders", "Liz Ann Sonders"),
        Voice(false, "charliebilello", "Charlie Bilello"),
    )

    /**
     * Nitter servers that served RSS to apps on 25.11.2026, from FMHY's list and status.d420.de. X has been shutting such
     * servers down since August 2026, so Tape tries them in turn and starts with the last one that answered.
     */
    val NITTER = listOf("nitter.kareem.one", "nitter.meowing.monster", "nitter.netbub.com", "tw1tter.com", "nitter.pp.ua")

    /** YouTube every 30 minutes, X every 15: a channel posts a few videos a week, an account a few posts an hour. */
    const val YOUTUBE_EVERY = 30 * MINUTE
    const val X_EVERY = 15 * MINUTE
    const val KEEP = 120

    fun youtubeUrl(channel: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=${URLEncoder.encode(channel, "UTF-8")}"
    fun nitterUrl(host: String, handle: String) = "https://$host/${URLEncoder.encode(handle, "UTF-8")}/rss"
    fun channelPage(handle: String) = "https://www.youtube.com/@${URLEncoder.encode(handle, "UTF-8")}"

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    /** A channel's Atom feed: its 15 newest videos. */
    fun parseYouTube(xml: String, name: String): List<Item> {
        val entries = parse(xml).getElementsByTagName("entry")
        return (0 until entries.length).mapNotNull { i ->
            val e = entries.item(i) as Element
            fun text(tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val id = text("yt:videoId").ifEmpty { return@mapNotNull null }
            val title = text("title").ifEmpty { return@mapNotNull null }
            val time = runCatching { OffsetDateTime.parse(text("published")).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            Item("yt:$id", title, "https://www.youtube.com/watch?v=$id", name, time)
        }
    }

    /** An account's posts from a Nitter server; reposts and replies stay out, links open on x.com instead of the server. */
    fun parseNitter(xml: String, name: String, handle: String): List<Item> {
        val items = parse(xml).getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val e = items.item(i) as Element
            fun text(tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val title = text("title").replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty() || title.startsWith("RT by @") || title.startsWith("R to @")) return@mapNotNull null
            val id = Regex("/status/(\\d+)").find(text("link"))?.groupValues?.get(1) ?: return@mapNotNull null
            val time = runCatching { ZonedDateTime.parse(text("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            Item("x:$id", title, "https://x.com/$handle/status/$id", name, time)
        }
    }

    /** A pasted channel link or handle: "youtube.com/channel/UC…" gives the id at once, "@name" or "youtube.com/@name" needs the page. */
    fun channelId(input: String): String? = Regex("(UC[\\w-]{22})").find(input)?.value

    fun youtubeHandle(input: String): String? = Regex("""(?:youtube\.com/)?@([\w.-]{3,30})""").find(input.trim())?.groupValues?.get(1)

    /** "@MaximInvestiert", "x.com/MaximInvestiert" or "MaximInvestiert": X handles are 1 to 15 letters, digits and underscores. */
    fun xHandle(input: String): String? = Regex("""^(?:https?://)?(?:www\.)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})|^@?([A-Za-z0-9_]{1,15})$""").find(input.trim())
        ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

    /** The channel id and name a channel page names as its own. */
    fun parseChannelPage(html: String): Pair<String, String>? {
        val id = Regex("""<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[\w-]{22})"""").find(html)?.groupValues?.get(1) ?: return null
        val name = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&").orEmpty()
        return id to name.ifEmpty { id }
    }
}
