package com.lazymusic.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Список треков из Spotify — по ссылке «Поделиться», без входа и без ключа.
 * То же, что `spotify.js` на ПК.
 *
 * Официальный API Spotify для нас закрыт: с февраля 2026 приложение
 * разработчика работает только у владельца с Premium и не больше чем у
 * пяти человек, чужие плейлисты из него убрали. Поэтому берём то же, что
 * показывает встроенный плеер Spotify на чужих сайтах
 * (`open.spotify.com/embed/…`): в странице лежит JSON со списком —
 * название, исполнители, длительность. Звук ищется потом на YouTube
 * ([YandexImport] — импорт у обоих источников общий).
 *
 * **Не больше 100 треков** — столько отдаёт встроенный плеер (проверено
 * 2026-10-20). И только открытые плейлисты: «Любимые треки» ссылкой не
 * делятся.
 */
object Spotify {

    /** Столько отдаёт встроенный плеер; ровно столько — значит, список обрезан. */
    const val CAP = 100

    private val URI_FORM = Regex("""spotify:(playlist|album):([A-Za-z0-9]{22})""")
    private val IN_TEXT = Regex("""(?:https?://)?(?:open\.spotify\.com|spotify\.link|spotify\.app\.link)/\S+""")
    private val ID = Regex("""[A-Za-z0-9]{22}""")
    private val IN_PAGE = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album)/([A-Za-z0-9]{22})""")
    private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Что за ссылка:
     *  - `open.spotify.com/playlist/<id>` (бывает с `/intl-ru/` впереди), `/album/<id>`;
     *  - `spotify:playlist:<id>` — так копирует приложение на компьютере;
     *  - `spotify.link/<код>` — короткая ссылка из телефона, её раскрываем.
     */
    sealed class Link {
        data class Page(val kind: String, val id: String) : Link()
        data class Short(val url: String) : Link()
    }

    class Failure(message: String) : IOException(message)

    /** null — не Spotify. */
    fun link(text: String?): Link? {
        val raw = text.orEmpty()
        URI_FORM.find(raw)?.let { return Link.Page(it.groupValues[1], it.groupValues[2]) }
        val found = IN_TEXT.find(raw)?.value ?: return null
        val uri = runCatching { URI(if (found.startsWith("http")) found else "https://$found") }.getOrNull() ?: return null
        val host = uri.host.orEmpty()
        if (host == "spotify.link" || host == "spotify.app.link") return Link.Short(uri.toString())
        val p = uri.path.orEmpty().split('/').filter { it.isNotEmpty() && !it.startsWith("intl-") }
        val kind = p.getOrNull(0)
        val id = p.getOrNull(1).orEmpty()
        return if ((kind == "playlist" || kind == "album") && ID.matches(id)) Link.Page(kind, id) else null
    }

    /** Список по ссылке. [get] — подмена сети для проверки. Блокирует — не с главного потока. */
    fun fetch(link: Link, get: (String) -> Yandex.Answer = ::httpGet): Yandex.Listing {
        val page = when (link) {
            is Link.Page -> link
            is Link.Short -> {
                val m = IN_PAGE.find(body(link.url, get)) ?: throw Failure(tr("Плейлист не найден или закрыт"))
                Link.Page(m.groupValues[1], m.groupValues[2])
            }
        }
        return parse(body("https://open.spotify.com/embed/${page.kind}/${page.id}", get))
    }

    /** Список из страницы встроенного плеера; [Yandex.Listing.title] — имя будущего плейлиста. */
    fun parse(html: String): Yandex.Listing {
        val entity = runCatching {
            JSONObject(NEXT_DATA.find(html)!!.groupValues[1])
                .getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
                .getJSONObject("data").getJSONObject("entity")
        }.getOrNull() ?: throw Failure(tr("Некорректный ответ Spotify"))
        val list = entity.optJSONArray("trackList") ?: throw Failure(tr("Некорректный ответ Spotify"))
        val tracks = (0 until list.length()).mapNotNull { entry(list.optJSONObject(it)) }
        val title = text(entity, "name").ifEmpty { text(entity, "title") }
            .ifEmpty { if (text(entity, "type") == "album") tr("Альбом") else "Spotify" }
        return Yandex.Listing(title, tracks, cut = list.length() >= CAP)
    }

    /** Строка встроенного плеера → то, что ищем на YouTube. */
    fun entry(t: JSONObject?): Yandex.Entry? {
        val title = text(t, "title")
        val uri = text(t, "uri")
        // Эпизоды подкастов и пропавшие треки — без названия или не трек
        if (t == null || title.isEmpty() || (uri.isNotEmpty() && !uri.startsWith("spotify:track:"))) return null
        return Yandex.Entry(
            // Исполнители через запятую с неразрывным пробелом: «A, B»
            artist = text(t, "subtitle").replace(Regex("""\s+"""), " ").replace(Regex("""\s*,\s*"""), ", "),
            // Хвосты вроде «- Remastered 2011» сверка понимает сама ([ImportMatch])
            title = title,
            version = "",
            ms = t.optLong("duration")
        )
    }

    private fun text(o: JSONObject?, key: String): String =
        if (o == null || o.isNull(key)) "" else o.optString(key).replace('\u00A0', ' ').trim()

    private fun body(url: String, get: (String) -> Yandex.Answer): String {
        val r = get(url)
        return when (r.status) {
            200 -> r.body
            404 -> throw Failure(tr("Плейлист не найден или закрыт"))
            else -> throw Failure(tr("Spotify ответил {код ответа}", "код ответа" to r.status))
        }
    }

    private fun httpGet(url: String): Yandex.Answer {
        // Одна попытка повтора — на обрыв соединения
        var last: IOException? = null
        repeat(2) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                return Yandex.Answer(code, body)
            } catch (e: IOException) {
                last = e
            } finally {
                conn.disconnect()
            }
        }
        throw last ?: IOException("Spotify не ответил")
    }

    // ── Выгрузка данных Spotify ──────────────────────────────────────────────
    //
    // Для длинных плейлистов и «Любимых треков»: на сайте Spotify «Аккаунт →
    // Конфиденциальность → Скачать ваши данные», «Данные аккаунта». Через
    // несколько дней на почту приходит архив my_spotify_data.zip, в нём JSON:
    //   Playlist1.json (2, 3…) — { playlists: [{ name, items: [{ track:
    //     { trackName, artistName, albumName, trackUri }, episode, localTrack }] }] };
    //   YourLibrary.json — { tracks: [{ artist, album, track, uri }] } — «Любимые треки».
    // Имена полей — по устройству выгрузки на 2026 год; разбор терпит и
    // соседние варианты (name/track, artist/artistName): свой архив человек
    // увидит только через несколько дней, угадать надо сразу. Тот же разбор,
    // что `spotify.js` на ПК.

    /** Список из выгрузки; [liked] — «Любимые треки»: идут в свой плейлист, а не в «Понравившуюся музыку». */
    class ExportList(val title: String, val tracks: List<Yandex.Entry>, val liked: Boolean = false)

    private val EXPORT_FILE = Regex("""(^|/)(Playlist\d*|YourLibrary)\.json$""", RegexOption.IGNORE_CASE)

    /** Трек из выгрузки → то, что ищем на YouTube. Эпизоды и свои файлы — мимо. */
    fun exportTrack(t: JSONObject?): Yandex.Entry? {
        if (t == null) return null
        val title = listOf("trackName", "track", "name").firstNotNullOfOrNull { k -> t.optString(k).takeIf { t.opt(k) is String && it.isNotBlank() } }
            ?.trim() ?: return null
        val artist = listOf("artistName", "artist").firstNotNullOfOrNull { k -> t.optString(k).takeIf { t.opt(k) is String && it.isNotBlank() } }
            ?.trim().orEmpty()
        return Yandex.Entry(artist = artist, title = title, version = "", ms = 0)
    }

    /** Списки из одного JSON выгрузки. */
    fun exportLists(name: String, text: String): List<ExportList> {
        val data = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ExportList>()
        val playlists = data.optJSONArray("playlists") ?: JSONArray()
        for (n in 0 until playlists.length()) {
            val p = playlists.optJSONObject(n) ?: continue
            val items = p.optJSONArray("items") ?: JSONArray()
            val tracks = (0 until items.length()).mapNotNull { exportTrack(items.optJSONObject(it)?.optJSONObject("track")) }
            if (tracks.isNotEmpty()) out += ExportList(p.optString("name").trim().ifEmpty { "Spotify" }, tracks)
        }
        val library = data.optJSONArray("tracks")
        if (name.contains("YourLibrary", ignoreCase = true) && library != null) {
            val tracks = (0 until library.length()).mapNotNull { exportTrack(library.optJSONObject(it)) }
            if (tracks.isNotEmpty()) out.add(0, ExportList(tr("Любимые треки"), tracks, liked = true))
        }
        return out
    }

    /**
     * Выбранный человеком файл — архив или JSON из него — → списки:
     * «Любимые треки» первыми, плейлисты по порядку файлов. Блокирует.
     */
    fun readExport(input: InputStream, name: String): List<ExportList> {
        val parts = mutableListOf<Pair<String, String>>()
        val head = input.buffered().apply { mark(4) }
        val sig = ByteArray(4).also { head.read(it); head.reset() }
        if (sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte()) {
            ZipInputStream(head).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && EXPORT_FILE.containsMatchIn(e.name)) parts += e.name to zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            if (parts.isEmpty()) throw Failure(tr("Это не архив выгрузки Spotify"))
        } else {
            parts += name to head.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val num = Regex("""\d+""")
        parts.sortBy { (n, _) -> num.find(n.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }
        return parts.flatMap { (n, t) -> exportLists(n, t) }.sortedByDescending { it.liked }
    }
}
