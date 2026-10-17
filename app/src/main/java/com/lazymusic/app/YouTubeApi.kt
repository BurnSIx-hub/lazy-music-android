package com.lazymusic.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouTube Data API v3 — порт `scripts/youtube-api.mjs` из модуля Foundry.
 *
 * Отдаёт только метаданные: названия, обложки, идентификаторы. Звука здесь
 * нет и быть не может — его добывает [YtDataSource] через yt-dlp.
 *
 * Бесплатная квота — 10 000 единиц в сутки. Поиск стоит 100 единиц за запрос,
 * страница плейлиста — 1, поэтому плейлисты можно листать спокойно, а поиском
 * лучше не молотить.
 */
object YouTubeApi {

    private const val TAG = "LazyMusic"
    private const val BASE = "https://www.googleapis.com/youtube/v3"

    /** Сколько записей отдаёт API за один запрос. Больше 50 он не умеет. */
    private const val PAGE = 50

    /** Все треки плейлиста, страницами по 50. Приватные и удалённые пропускаются. */
    suspend fun playlistItems(playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val key = requireKey()
        val out = mutableListOf<Track>()
        var pageToken = ""
        do {
            val url = buildString {
                append("$BASE/playlistItems?part=snippet&maxResults=$PAGE")
                append("&playlistId=").append(enc(playlistId))
                append("&key=").append(enc(key))
                if (pageToken.isNotEmpty()) append("&pageToken=").append(enc(pageToken))
            }
            val json = fetch(url)
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val snippet = items!!.getJSONObject(i).optJSONObject("snippet") ?: continue
                val videoId = snippet.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                if (videoId.isBlank()) continue
                val title = plainText(snippet.optString("title"))
                if (title == "Private video" || title == "Deleted video") continue
                out += Track(
                    id = videoId,
                    title = Settings.trackName(videoId) ?: title,
                    artist = artistOf(plainText(snippet.optString("videoOwnerChannelTitle"))),
                    artworkUrl = thumbnail(snippet)
                )
            }
            pageToken = json.optString("nextPageToken")
        } while (pageToken.isNotEmpty())
        out
    }

    /** Название плейлиста — чтобы было чем подписать очередь. */
    suspend fun playlistTitle(playlistId: String): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/playlists?part=snippet&id=${enc(playlistId)}&key=${enc(requireKey())}"
        fetch(url).optJSONArray("items")
            ?.optJSONObject(0)
            ?.optJSONObject("snippet")
            ?.optString("title")
            ?.let(::plainText)
            ?.takeIf { it.isNotBlank() }
    }

    /** Находки и закладка на следующую страницу. Пусто — страница последняя. */
    class Found(val tracks: List<Track>, val nextPage: String?)

    /**
     * Находки по запросу. Берём сразу [PAGE] — предел API за один запрос.
     *
     * Просить меньше незачем: запрос стоит свои 100 единиц квоты независимо
     * от того, сколько находок в нём попросили. Двадцать и пятьдесят
     * обходятся одинаково. А вот следующая страница — это ещё один такой же
     * запрос, то есть сотая доля суточной квоты за пролистывание; поэтому
     * она грузится по кнопке, а не сама.
     */
    suspend fun search(query: String, pageToken: String? = null): Found =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$BASE/search?part=snippet&type=video&maxResults=$PAGE")
                append("&q=").append(enc(query))
                append("&key=").append(enc(requireKey()))
                if (!pageToken.isNullOrBlank()) append("&pageToken=").append(enc(pageToken))
            }
            val json = fetch(url)
            val items = json.optJSONArray("items")
            val tracks = buildList {
                for (i in 0 until (items?.length() ?: 0)) {
                    val item = items!!.getJSONObject(i)
                    val videoId = item.optJSONObject("id")?.optString("videoId").orEmpty()
                    if (videoId.isBlank()) continue
                    val snippet = item.optJSONObject("snippet") ?: continue
                    add(
                        Track(
                            id = videoId,
                            title = Settings.trackName(videoId) ?: plainText(snippet.optString("title")),
                            artist = artistOf(plainText(snippet.optString("channelTitle"))),
                            artworkUrl = thumbnail(snippet)
                        )
                    )
                }
            }
            Found(tracks, json.optString("nextPageToken").takeIf { it.isNotBlank() })
        }

    /**
     * Идентификатор плейлиста из ссылки или из него самого.
     * Принимает и полный адрес с `?list=`, и голый идентификатор.
     */
    fun extractPlaylistId(urlOrId: String): String {
        val text = urlOrId.trim()
        return runCatching {
            val query = URL(text).query ?: return@runCatching text
            query.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "list" }
                ?.get(1)
                ?: text
        }.getOrDefault(text)
    }

    // ── Внутреннее ───────────────────────────────────────────────────────────

    private fun requireKey(): String = Settings.apiKey.ifBlank {
        throw IOException("не задан ключ YouTube Data API")
    }

    private fun thumbnail(snippet: JSONObject): String? {
        val thumbs = snippet.optJSONObject("thumbnails") ?: return null
        for (size in listOf("medium", "default", "high")) {
            thumbs.optJSONObject(size)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Отказ, пришедший настоящим ответом. Повторять его бессмысленно. */
    private class ApiError(message: String) : IOException(message)

    /**
     * Ответ API с одним повтором.
     *
     * Повтор здесь не от жадности. Android держит соединения в пуле, сервер
     * закрывает их молча, и первый же запрос по такому соединению падает
     * «unexpected end of stream», вообще не долетев до Google. Поймано
     * 2026-09-12: поиск переставал работать после получаса простоя и
     * оживал только с перезапуском приложения.
     *
     * Повторяем только обрывы связи. Отказ самого API — «ключ не тот»,
     * «квота кончилась» — приходит настоящим ответом, и второй запрос
     * даст ровно то же, только потратит ещё сотню единиц квоты.
     */
    private fun fetch(url: String): JSONObject = try {
        once(url)
    } catch (denied: ApiError) {
        throw denied
    } catch (broken: IOException) {
        Log.w(TAG, "повтор по свежему соединению: ${broken.message}")
        once(url)
    }

    /**
     * Ошибку разбираем из тела: она приходит с кодом 400/403 и внятным
     * текстом, который стоит показать целиком.
     */
    private fun once(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse { JSONObject() }
            json.optJSONObject("error")?.let { err ->
                throw ApiError(err.optString("message").ifBlank { "YouTube API ответил $code" })
            }
            if (code !in 200..299) throw ApiError("YouTube API ответил $code")
            return json
        } finally {
            conn.disconnect()
        }
    }
}
