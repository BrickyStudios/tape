package io.github.brickystudios.tape

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Check(val time: Long, val error: String?)

/** Everything Tape keeps, in one SharedPreferences file that never leaves the phone (no backup, see data_extraction_rules). */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("tape", Context.MODE_PRIVATE)

    var favorites: List<Favorite>
        get() = array("favorites").map { Favorite(it.getString("s"), it.optString("n"), it.optString("x"), it.optBoolean("o")) }
        set(v) = put("favorites", v.map { JSONObject().put("s", it.symbol).put("n", it.name).put("x", it.exchange).put("o", it.own) })

    /**
     * Watch a stock ([own] false), mark it as owned (true) or take it off the list (null); a stock keeps its place.
     * Taking one off forgets its first answers, so adding it again fills quietly instead of ringing.
     */
    fun follow(f: Favorite, own: Boolean?): List<Favorite> {
        val old = favorites
        val v = when {
            own == null -> old.filter { it.symbol != f.symbol }
            old.any { it.symbol == f.symbol } -> old.map { if (it.symbol == f.symbol) it.copy(own = own) else it }
            else -> old + f.copy(own = own)
        }
        if (own == null) primed = primed - "stock:${f.symbol}" - "google:${f.symbol}" - "sa:${f.symbol}" - "price:${f.symbol}"
        favorites = v
        return v
    }

    var feeds: List<Feed>
        get() = if (!p.contains("feeds")) News.DEFAULT_FEEDS
        else array("feeds").map { Feed(it.getString("name"), it.getString("url"), it.optBoolean("on", true)) }
        set(v) = put("feeds", v.map { JSONObject().put("name", it.name).put("url", it.url).put("on", it.on) })

    var items: List<Item>
        get() = array("items").map {
            val tk = it.optJSONArray("tk") ?: JSONArray()
            Item(it.getString("id"), it.getString("t"), it.getString("l"), it.getString("s"), it.getLong("at"), (0 until tk.length()).map(tk::getString), it.optBoolean("w"))
        }
        set(v) = put("items", v.map {
            JSONObject().put("id", it.id).put("t", it.title).put("l", it.link).put("s", it.source).put("at", it.time).put("tk", JSONArray(it.tickers)).put("w", it.wire)
        })

    /** Sources answered at least once. A source's first answer fills the screen without ringing. */
    var primed: Set<String>
        get() = p.getStringSet("primed", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("primed", v).apply()

    var lastStocks: Long
        get() = p.getLong("lastStocks", 0)
        set(v) = p.edit().putLong("lastStocks", v).apply()

    var lastCheck: Long
        get() = p.getLong("lastCheck", 0)
        set(v) = p.edit().putLong("lastCheck", v).apply()

    /** Last prices for the chips; kept when a check fails. */
    var quotes: List<Quote>
        get() = quotesAt("quotes")
        set(v) = putQuotes("quotes", v)

    private fun quotesAt(key: String) = array(key).map { Quote(it.getString("s"), it.getDouble("p"), it.getDouble("c"), it.getLong("t"), it.optString("z", "UTC"), it.optString("cur")) }
    private fun putQuotes(key: String, v: List<Quote>) =
        put(key, v.map { JSONObject().put("s", it.symbol).put("p", it.price).put("c", it.previousClose).put("t", it.time).put("z", it.zone).put("cur", it.currency) })

    /** Market overview prices (indices, gold, oil, bitcoin, euro), kept when a check fails. */
    var market: List<Quote>
        get() = quotesAt("market")
        set(v) = putQuotes("market", v)

    /** Today's 5-minute closes per symbol, for the small lines next to each row. */
    var points: Map<String, List<Double>>
        get() = runCatching { JSONObject(p.getString("points", "{}")!!) }.getOrDefault(JSONObject()).let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map(a::getDouble) } }
        }
        set(v) = p.edit().putString("points", JSONObject(v.mapValues { JSONArray(it.value) }).toString()).apply()

    /** US stocks' last pre-market or after-hours trade, replaced with every 5-minute check. */
    var ext: Map<String, Ext>
        get() = runCatching { JSONObject(p.getString("ext", "{}")!!) }.getOrDefault(JSONObject()).let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Ext(it.getDouble("p"), it.getDouble("b"), it.getLong("t"), it.getBoolean("pre")) } }
        }
        set(v) = p.edit().putString("ext", JSONObject(v.mapValues { (_, e) -> JSONObject().put("p", e.price).put("b", e.base).put("t", e.time).put("pre", e.pre) }).toString()).apply()

    /** When each stock event ("e:NVDA|EARNINGS") and alert word ("w:cpi") last rang, see [Alerts.pause]. */
    var rang: Map<String, Long>
        get() = runCatching { JSONObject(p.getString("rang", "{}")!!) }.getOrDefault(JSONObject()).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
        set(v) = p.edit().putString("rang", JSONObject(v).toString()).apply()

    /** The always-updating ticker notification; off by default, it showed every headline. */
    var tickerNote: Boolean
        get() = p.getBoolean("tickerNote", false)
        set(v) = p.edit().putBoolean("tickerNote", v).apply()

    var brief: Boolean
        get() = p.getBoolean("brief", true)
        set(v) = p.edit().putBoolean("brief", v).apply()

    /** The Frankfurt day of the last morning overview, "2026-11-24". */
    var briefDay: String
        get() = p.getString("briefDay", "")!!
        set(v) = p.edit().putString("briefDay", v).apply()

    /** Highest price step that rang, by [Alerts.stepKey]. */
    var rung: Map<String, Int>
        get() = runCatching { JSONObject(p.getString("rung", "{}")!!) }.getOrDefault(JSONObject()).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
        set(v) = p.edit().putString("rung", JSONObject(v).toString()).apply()

    var threshold: Int
        get() = p.getInt("threshold", Alerts.DEFAULT_THRESHOLD)
        set(v) = p.edit().putInt("threshold", v).apply()

    var words: List<String>
        get() = if (!p.contains("words")) Alerts.DEFAULT_WORDS
        else runCatching { JSONArray(p.getString("words", "[]")) }.getOrDefault(JSONArray()).let { a -> (0 until a.length()).map(a::getString) }
        set(v) = p.edit().putString("words", JSONArray(v).toString()).apply()

    var quiet: Boolean
        get() = p.getBoolean("quiet", true)
        set(v) = p.edit().putBoolean("quiet", v).apply()

    var squawk: Boolean
        get() = p.getBoolean("squawk", false)
        set(v) = p.edit().putBoolean("squawk", v).apply()

    /** When a tab was last open, for its "new" count. */
    fun seen(tab: Int) = p.getLong("seen$tab", 0)
    fun seen(tab: Int, time: Long) = p.edit().putLong("seen$tab", time).apply()

    /** Each source's last result, shown under Setup: null error means it worked. */
    var status: Map<String, Check>
        get() = runCatching { JSONObject(p.getString("status", "{}")!!) }.getOrDefault(JSONObject()).let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Check(it.getLong("t"), it.optString("e").ifEmpty { null }) } }
        }
        set(v) = p.edit().putString("status", JSONObject(v.mapValues { (_, c) -> JSONObject().put("t", c.time).put("e", c.error ?: "") }).toString()).apply()

    var releases: List<Indicator>
        get() = array("releases").map { Indicator(it.getString("ti"), it.getString("p"), it.getLong("t"), it.optString("f"), it.optString("pr")) }
        set(v) = put("releases", v.map { JSONObject().put("ti", it.title).put("p", it.place).put("t", it.time).put("f", it.forecast).put("pr", it.previous) })

    var earnings: List<Earning>
        get() = array("earnings").map { Earning(it.getString("s"), it.optString("n"), it.getLong("t"), it.optString("sl"), it.optString("q"), if (it.has("e")) it.getDouble("e") else null, it.optLong("r")) }
        set(v) = put("earnings", v.map { e -> JSONObject().put("s", e.symbol).put("n", e.name).put("t", e.time).put("sl", e.slot).put("q", e.quarter).put("r", e.rank).also { o -> e.eps?.let { o.put("e", it) } } })

    var mentions: List<Mention>
        get() = array("mentions").map { Mention(it.getString("s"), it.optString("n"), it.getInt("m"), it.optInt("b"), it.optInt("r")) }
        set(v) = put("mentions", v.map { JSONObject().put("s", it.symbol).put("n", it.name).put("m", it.mentions).put("b", it.before).put("r", it.rank) })

    var posts: List<Post>
        get() = array("posts").map { Post(it.getString("i"), it.getString("u"), it.getString("ti"), it.optString("f"), it.optInt("sc"), it.optInt("c"), it.getLong("t")) }
        set(v) = put("posts", v.map { JSONObject().put("i", it.id).put("u", it.sub).put("ti", it.title).put("f", it.flair).put("sc", it.score).put("c", it.comments).put("t", it.time) })

    /** Each watched stock's year of daily closes, boiled down for the buying chances. */
    var levels: Map<String, Levels>
        get() = runCatching { JSONObject(p.getString("levels", "{}")!!) }.getOrDefault(JSONObject()).let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Levels(if (it.has("a")) it.getDouble("a") else null, it.getDouble("lo"), it.getDouble("hi"), if (it.has("r")) it.getDouble("r") else null) } }
        }
        set(v) = p.edit().putString("levels", JSONObject(v.mapValues { (_, l) -> JSONObject().put("lo", l.low).put("hi", l.high).also { o -> l.avg200?.let { o.put("a", it) }; l.rsi?.let { o.put("r", it) } } }).toString()).apply()

    var cot: List<Cot>
        get() = array("cot").map { Cot(it.getString("d"), it.getLong("m"), it.getLong("f")) }
        set(v) = put("cot", v.map { JSONObject().put("d", it.date).put("m", it.managers).put("f", it.funds) })

    /** When each slow source was last asked: calendar and CFTC every few hours, Reddit every 30 minutes. */
    fun last(source: String) = p.getLong("last:$source", 0)
    fun last(source: String, time: Long) = p.edit().putLong("last:$source", time).apply()

    /** Setup's "German sources", see [News.GERMAN_FEEDS]. */
    var german: Boolean
        get() = p.getBoolean("german", false)
        set(v) = p.edit().putBoolean("german", v).apply()

    /** Setup's "Reddit posts": off by default, the posts come from a volunteer-run archive. */
    var redditPosts: Boolean
        get() = p.getBoolean("redditPosts", false)
        set(v) = p.edit().putBoolean("redditPosts", v).apply()

    private fun array(key: String): List<JSONObject> {
        val a = runCatching { JSONArray(p.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map(a::getJSONObject)
    }

    private fun put(key: String, v: List<JSONObject>) = p.edit().putString(key, JSONArray(v).toString()).apply()
}
