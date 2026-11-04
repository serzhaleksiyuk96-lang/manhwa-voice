package com.manhwavoice.reader

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Голос из каталога Fish Audio. */
data class FishVoice(
    val id: String,
    val title: String,
    val description: String = "",
    val cover: String? = null,
    val author: String = "",
    val likes: Int = 0,
    val uses: Int = 0,
    val tags: List<String> = emptyList(),
    val sampleUrl: String? = null
)

/**
 * Каталог голосов Fish Audio (https://fish.audio/app/discovery/) через их API:
 * GET /model — список голосов с поиском, языком и сортировкой (нужен API-ключ).
 */
object FishCatalog {

    private const val API = "https://api.fish.audio"
    private const val CDN = "https://public-platform.r2.fish.audio/"

    enum class Sort(val api: String, val label: String) {
        SCORE("score", "🔥 Популярные"),
        USES("task_count", "▶ Чаще слушают"),
        NEW("created_at", "✨ Новые")
    }

    class Page(val items: List<FishVoice>, val hasMore: Boolean, val total: Int)

    /** Одна страница каталога. Блокирующий вызов — не из главного потока. */
    fun list(key: String, page: Int, query: String, sort: Sort, onlyRussian: Boolean, pageSize: Int = 20): Page {
        val params = ArrayList<String>()
        params += "page_size=$pageSize"
        params += "page_number=$page"
        params += "sort_by=${sort.api}"
        if (onlyRussian) params += "language=ru"
        if (query.isNotBlank()) params += "title=" + URLEncoder.encode(query.trim(), "UTF-8")
        val json = get("$API/model?" + params.joinToString("&"), key)
        val arr = json.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parse(it) } }
        val total = json.optInt("total", items.size)
        val hasMore = if (json.has("has_more") && !json.isNull("has_more")) json.optBoolean("has_more")
        else page * pageSize < total
        return Page(items, hasMore && items.isNotEmpty(), total)
    }

    /** Баланс API-кредитов — заодно проверка, что ключ рабочий. */
    fun credit(key: String): String {
        val json = get("$API/wallet/self/api-credit", key)
        val c = json.optString("credit", "")
        return c.ifBlank { "0" }
    }

    private fun get(url: String, key: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "ManhwaVoice-Android")
            val code = conn.responseCode
            if (code != 200) {
                throw FishTts.FishError(
                    when (code) {
                        401, 403 -> "ключ не подходит"
                        402 -> "на счёте нет денег"
                        429 -> "слишком много запросов, попробуйте позже"
                        else -> "сервер ответил $code"
                    }
                )
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Ссылка может прийти полной или как путь на их CDN. */
    fun absolute(u: String?): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("http://") || u.startsWith("https://") -> u
        else -> CDN + u.trimStart('/')
    }

    private fun parse(o: JSONObject): FishVoice? {
        val id = o.optString("_id")
        if (id.isBlank()) return null
        val s0 = o.optJSONArray("samples")?.optJSONObject(0)
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { t -> t.isNotBlank() } } }.orEmpty()
        return FishVoice(
            id = id,
            title = o.optString("title").ifBlank { "Без названия" },
            description = o.optString("description"),
            cover = absolute(o.optString("cover_image").ifBlank { "coverimage/$id" }),
            author = o.optJSONObject("author")?.optString("nickname").orEmpty(),
            likes = o.optInt("like_count"),
            uses = o.optInt("task_count"),
            tags = tags,
            sampleUrl = absolute(s0?.optString("audio"))
        )
    }

    // ---------------- Выбранный голос и недавние ----------------

    fun select(p: SharedPreferences, v: FishVoice) {
        p.edit()
            .putString(Prefs.KEY_ENGINE, FishTts.ENGINE_ID)
            .putString(Prefs.KEY_FISH_VOICE, v.id)
            .putString(Prefs.KEY_FISH_VOICE_TITLE, v.title)
            .putString(Prefs.KEY_FISH_VOICE_COVER, v.cover)
            .apply()
        val list = (listOf(v) + recents(p).filter { it.id != v.id }).take(10)
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject().put("id", r.id).put("title", r.title).put("cover", r.cover ?: ""))
        p.edit().putString(Prefs.KEY_FISH_RECENTS, arr.toString()).apply()
    }

    fun recents(p: SharedPreferences): List<FishVoice> = try {
        val arr = JSONArray(p.getString(Prefs.KEY_FISH_RECENTS, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else FishVoice(id, o.optString("title"), cover = o.optString("cover").ifBlank { null })
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** «1234» → «1.2K» */
    fun compact(n: Int): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000f)
        else -> n.toString()
    }
}
