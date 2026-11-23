package io.github.brickystudios.tape

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    private fun array(key: String): List<JSONObject> {
        val a = runCatching { JSONArray(p.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map(a::getJSONObject)
    }

    private fun put(key: String, v: List<JSONObject>) = p.edit().putString(key, JSONArray(v).toString()).apply()
}
