package io.github.brickystudios.tape

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Check(val time: Long, val error: String?)

/** Everything Tape keeps, in one SharedPreferences file that never leaves the phone (no backup, see data_extraction_rules). */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("tape", Context.MODE_PRIVATE)

    var favorites: List<Favorite>
        get() = array("favorites").map { Favorite(it.getString("s"), it.optString("n")) }
        set(v) = put("favorites", v.map { JSONObject().put("s", it.symbol).put("n", it.name) })

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
        get() = array("quotes").map { Quote(it.getString("s"), it.getDouble("p"), it.getDouble("c"), it.getLong("t"), it.optString("z", "UTC"), it.optString("cur")) }
        set(v) = put("quotes", v.map { JSONObject().put("s", it.symbol).put("p", it.price).put("c", it.previousClose).put("t", it.time).put("z", it.zone).put("cur", it.currency) })

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

    private fun array(key: String): List<JSONObject> {
        val a = runCatching { JSONArray(p.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map(a::getJSONObject)
    }

    private fun put(key: String, v: List<JSONObject>) = p.edit().putString(key, JSONArray(v).toString()).apply()
}
