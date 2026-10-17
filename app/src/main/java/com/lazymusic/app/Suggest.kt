package com.lazymusic.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Подсказки поиска — то, что выпадает под строкой, пока набираешь.
 *
 * Берутся у той же службы, что подсказывает в самом YouTube. Она открытая:
 * **ключа не просит и квоту Data API не тратит вовсе**. Это важно — квоты
 * хватает на сотню поисков в сутки, и тратить её на каждую набранную букву
 * было бы немыслимо.
 *
 * Ответ приходит в виде `["набранное", ["подсказка", ...], ...]`.
 *
 * **Про кодировку.** Без `oe=utf-8` служба отвечает в windows-1251, и
 * кириллица превращается в кашу. Проверено 2026-09-12: «король и» без этого
 * ключа приезжает нечитаемым.
 */
object Suggest {

    private const val TAG = "LazyMusic"
    private const val BASE = "https://suggestqueries.google.com/complete/search"

    /**
     * Сколько подсказок показываем.
     *
     * Не «сколько влезет», а сколько влезет **над клавиатурой**: она
     * занимает низ экрана, и лишние строки уходят под неё — не просто
     * некрасиво, а не нажать вовсе, касание достаётся клавиатуре.
     *
     * Замерено на S23 (1080×2340): край клавиатуры примерно на 1125, строка
     * подсказки — 108 точек, список начинается с 525. Пять помещаются
     * целиком, шестая обрезается.
     */
    private const val LIMIT = 5

    /**
     * Подсказки к набранному. Пустой список — не беда: подсказки
     * необязательны, и молчать лучше, чем мешать сообщением об ошибке.
     */
    suspend fun forQuery(query: String): List<String> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.length < 2) return@withContext emptyList()
        val url = "$BASE?client=firefox&ds=yt&oe=utf-8&hl=ru&q=${enc(text)}"
        runCatching { parse(fetch(url)) }
            .onFailure { Log.w(TAG, "подсказки не пришли: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun parse(body: String): List<String> {
        val root = JSONArray(body)
        val items = root.optJSONArray(1) ?: return emptyList()
        return (0 until items.length())
            .mapNotNull { items.optString(it).takeIf(String::isNotBlank) }
            .distinct()
            .take(LIMIT)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // Коротко: подсказка, которая приехала позже набранного слова,
            // уже не нужна
            connectTimeout = 4_000
            readTimeout = 4_000
        }
        try {
            if (conn.responseCode !in 200..299) error("ответ ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
