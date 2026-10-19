package com.lazymusic.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * Список треков из Яндекс Музыки — по ссылке «Поделиться», без входа.
 * То же, что `yandex.js` на ПК.
 *
 * Яндекс отдаёт только «исполнитель — название», звук ищется потом на
 * YouTube ([YandexImport]). Ходим в тот же сервис, что и сайт Яндекса
 * (`api.music.yandex.net`).
 *
 * **Только с адреса из СНГ.** Из Испании сервис отвечает 451
 * «Unavailable For Legal Reasons» — проверено 2026-10-17, и со входом в
 * аккаунт тоже. Поэтому импорт в два шага: список берётся, пока адрес
 * подходящий, а поиск на YouTube идёт, когда тот доступен (часто — уже
 * через VPN). Ответ сервиса вживую отсюда не виден: разбор написан по
 * устройству ответов этого сервиса и проверяется на образцах.
 */
object Yandex {

    private const val API = "https://api.music.yandex.net"
    private val HOST = Regex("""^music\.yandex\.(ru|com|by|kz|uz|net)$""")

    /** Ссылка внутри текста: приложение Яндекса делится и строкой вида «Слушайте… https://…». */
    private val IN_TEXT = Regex("""(?:https?://)?(?:www\.)?music\.yandex\.(?:ru|com|by|kz|uz|net)/\S+""")

    /** Сколько треков дозапрашивать за раз, если плейлист пришёл одними номерами. */
    private const val CHUNK = 100

    /**
     * Что за ссылка:
     *  - `/playlists/<uuid>` — новые ссылки «Поделиться» (`lk.…` — «Мне нравится»);
     *  - `/users/<владелец>/playlists/<номер>` — старые;
     *  - `/album/<номер>` — альбом.
     */
    sealed class Link {
        data class Shared(val uuid: String) : Link()
        data class Owned(val owner: String, val id: String) : Link()
        data class Album(val id: String) : Link()
    }

    enum class Why { REGION, MISSING, HTTP, FORMAT }

    class Failure(val why: Why, message: String) : IOException(message)

    class Answer(val status: Int, val body: String)

    /** Трек Яндекса → то, что ищем на YouTube. */
    data class Entry(val artist: String, val title: String, val version: String, val ms: Long)

    class Listing(val title: String, val tracks: List<Entry>)

    /** null — не Яндекс Музыка. */
    fun link(text: String?): Link? {
        val raw = IN_TEXT.find(text.orEmpty())?.value ?: return null
        val uri = runCatching { URI(if (raw.startsWith("http")) raw else "https://$raw") }.getOrNull() ?: return null
        if (!HOST.matches(uri.host.orEmpty().removePrefix("www."))) return null
        // getPath уже раскодирован
        val p = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val digits = Regex("""\d+""")
        return when {
            p.getOrNull(0) == "playlists" && p.size >= 2 -> Link.Shared(p[1])
            p.getOrNull(0) == "users" && p.size >= 4 && p[2] == "playlists" && digits.matches(p[3]) ->
                Link.Owned(p[1], p[3])
            // `/album/<номер>/track/<номер>` — ссылка на один трек, не на альбом
            p.getOrNull(0) == "album" && p.size == 2 && digits.matches(p[1]) -> Link.Album(p[1])
            else -> null
        }
    }

    /**
     * Список из ссылки. [get] — подмена сети для проверки на образцах.
     * Блокирует — звать не с главного потока.
     */
    fun fetch(link: Link, get: (String) -> Answer = ::httpGet): Listing {
        if (link is Link.Album) {
            val a = api("/albums/${link.id}/with-tracks", get) as? JSONObject ?: throw format()
            val tracks = mutableListOf<Entry>()
            a.optJSONArray("volumes")?.let { volumes ->
                for (v in 0 until volumes.length()) {
                    val disc = volumes.optJSONArray(v) ?: continue
                    for (i in 0 until disc.length()) entry(disc.optJSONObject(i))?.let(tracks::add)
                }
            }
            return Listing(text(a, "title").ifBlank { tr("Альбом") }, tracks)
        }
        val pl = when (link) {
            is Link.Shared -> api("/playlist/${enc(link.uuid)}?richTracks=true", get)
            is Link.Owned -> api("/users/${enc(link.owner)}/playlists/${link.id}", get)
            else -> null
        } as? JSONObject ?: throw format()

        // Обычно у каждой строки списка есть сам трек. Пришли одни номера —
        // дозапрашиваем сведения пачками, порядок списка сохраняем
        val rows = pl.optJSONArray("tracks") ?: JSONArray()
        val list = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val bare = list.filter { it.optJSONObject("track") == null && !it.has("title") }
            .map { text(it, "id") }.filter { it.isNotEmpty() }
        val full = HashMap<String, JSONObject>()
        for (part in bare.chunked(CHUNK)) {
            val found = api("/tracks?track-ids=${enc(part.joinToString(","))}", get) as? JSONArray ?: continue
            for (i in 0 until found.length()) found.optJSONObject(i)?.let { full[text(it, "id")] = it }
        }
        val tracks = list.mapNotNull { r ->
            val t = r.optJSONObject("track")
                ?: r.takeIf { it.has("title") }
                ?: text(r, "id").let { id -> full[id.substringBefore(':')] ?: full[id] }
            entry(t)
        }
        return Listing(text(pl, "title").ifBlank { tr("Яндекс Музыка") }, tracks)
    }

    fun entry(t: JSONObject?): Entry? {
        val title = text(t, "title")
        if (t == null || title.isEmpty()) return null
        val names = t.optJSONArray("artists") ?: JSONArray()
        val artists = (0 until names.length()).map { text(names.optJSONObject(it), "name") }.filter { it.isNotEmpty() }
        return Entry(
            artist = artists.joinToString(", "),
            // Основное название — для сверки с находкой; версия («Remix», «Live») —
            // в запрос и в имя, чтобы искать именно её
            title = title,
            version = text(t, "version"),
            ms = t.optLong("durationMs")
        )
    }

    /**
     * Строка из ответа. Не `optString`: у org.json в Android он отдаёт
     * `null` из JSON строкой «null», и версия трека стала бы словом в поиске.
     */
    private fun text(o: JSONObject?, key: String): String =
        if (o == null || o.isNull(key)) "" else o.optString(key).trim()

    // ── Внутреннее ───────────────────────────────────────────────────────────

    private fun format() = Failure(Why.FORMAT, tr("Некорректный ответ Яндекса"))

    private fun api(path: String, get: (String) -> Answer): Any? {
        val r = get(API + path)
        when (r.status) {
            200 -> Unit
            451 -> throw Failure(Why.REGION, "Яндекс Музыка недоступна с этого адреса")
            404 -> throw Failure(Why.MISSING, "Плейлист не найден или закрыт")
            else -> throw Failure(Why.HTTP, "Яндекс ответил ${r.status}")
        }
        val data = runCatching { JSONObject(r.body) }.getOrNull() ?: throw format()
        if (!data.has("result")) throw format()
        return data.opt("result")
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Основа адреса — подменяется только в отладочной сборке, см. [YandexImport.debugApi]. */
    private fun httpGet(url: String): Answer {
        val target = YandexImport.debugApi?.let { url.replaceFirst(API, it) } ?: url
        // Одна попытка повтора — на обрыв соединения. Ошибку самой службы
        // не повторяем: она придёт ответом, а не обрывом
        var last: IOException? = null
        repeat(2) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "ru")
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                return Answer(code, body)
            } catch (e: IOException) {
                last = e
            } finally {
                conn.disconnect()
            }
        }
        throw last ?: IOException("Яндекс не ответил")
    }
}
