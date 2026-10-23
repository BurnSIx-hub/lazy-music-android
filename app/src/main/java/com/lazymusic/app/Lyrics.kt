package com.lazymusic.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Тексты песен — по кнопке «Текст» на «Сейчас», как в YouTube Music.
 *
 * Источников два, спрашиваются вместе:
 * 1. **LRCLIB** (lrclib.net) — открытая база, у многих песен строки
 *    размечены по времени: текст идёт вслед за музыкой. Ищется по
 *    исполнителю и названию, вычищенным из названия ролика. С разметкой
 *    берётся, только если длина совпала с треком (±3 с): у клипов бывают
 *    вступления, и подсветка разъехалась бы со словами.
 * 2. **YouTube Music** — текст к самому ролику (поставщик — Musixmatch),
 *    без разметки. Точно та песня, но есть не у всех роликов.
 *
 * Если ни того, ни другого — что нашлось в LRCLIB, простым текстом.
 * Найденное и «не нашлось» помнятся на диске: второй раз — сразу, и
 * скачанный трек показывает текст без сети. На ПК тот же порядок (`lyrics.js`).
 */
object Lyrics {

    /** Строка; [ms] — с какого места она звучит, null — разметки нет. */
    data class Line(val ms: Long?, val text: String)

    /**
     * Текст. [synced] — строки размечены по времени и совпали с треком;
     * [instrumental] — база знает песню и говорит, что слов в ней нет.
     * [artist] и [title] — как песня записана в LRCLIB: по ним можно
     * поискать ещё раз, уже с исполнителем.
     */
    data class Found(
        val lines: List<Line>,
        val synced: Boolean,
        val source: String,
        val instrumental: Boolean = false,
        val artist: String = "",
        val title: String = ""
    )

    private const val UA = "LazyMusic (https://github.com/BurnSIx-hub/lazy-music-android)"
    private const val TIMEOUT_MS = 8_000

    /** Насколько длина в базе может разойтись с треком, чтобы разметке верить. */
    private const val SLACK_S = 3.0

    /**
     * «Не нашлось» и простой текст помнятся неделю: базы пополняются, и
     * разметка могла появиться (или LRCLIB в тот раз был занят). Текст с
     * разметкой и «без слов» — навсегда: лучше уже не найти.
     */
    private const val RECHECK_MS = 7L * 24 * 3600 * 1000

    /** Сколько текстов держать на диске. */
    private const val KEEP = 400

    // ── Снаружи ──────────────────────────────────────────────────────────────

    /** Текст трека или null. [durationMs] — длина у плеера: по ней сверяется разметка. */
    suspend fun find(context: Context, track: Track, durationMs: Long): Found? = withContext(Dispatchers.IO) {
        val file = File(dir(context), "${track.id}.json")
        cached(file)?.let { return@withContext it.getOrNull() }
        val found = fetch(track, durationMs / 1000.0)
        remember(file, found)
        found
    }

    private suspend fun fetch(track: Track, durationS: Double): Found? = coroutineScope {
        val lr = async { runCatching { lrclib(track.title, track.artist, durationS) }.getOrNull() }
        val yt = async { runCatching { youtube(track.id) }.getOrNull() }
        val fromBase = lr.await()
        val fromYt = yt.await()
        when {
            // База говорит «без слов», а у YouTube Music текст есть — верим тексту
            fromBase?.instrumental == true -> fromYt ?: fromBase
            // Разметка — только если это та же песня: без исполнителя легко
            // попасть на чужую с тем же названием, а текст YouTube Music — точно от ролика
            fromBase?.synced == true && (fromYt == null || agree(fromBase, fromYt)) -> fromBase
            else -> fromYt ?: fromBase
        }
    }

    /** Два текста об одной песне: хотя бы треть строк совпадает. */
    internal fun agree(a: Found, b: Found): Boolean {
        fun lines(f: Found) = f.lines.map { norm(it.text) }.filter { it.length >= 4 }.toSet()
        val x = lines(a)
        val y = lines(b)
        if (x.isEmpty() || y.isEmpty()) return true
        return x.intersect(y).size * 3 >= minOf(x.size, y.size)
    }

    // ── Разбор названия ──────────────────────────────────────────────────────

    /** Названия роликов в мусоре: «(Official Video)», «[MV]», «| Ending», «ft. …». */
    internal fun cleanTitle(title: String): String {
        var t = title
            .replace(Regex("""\([^)]*\)|\[[^\]]*]|【[^】]*】"""), " ")
            .split(" | ", "｜", " // ").first()
        t = t.replace(Regex("""(?i)\b(official\s+(music\s+)?video|official\s+audio|lyric\s+video|lyrics?|visuali[sz]er|m/?v|hd|4k)\b"""), " ")
        t = t.replace(Regex("""(?i)\s(ft|feat)\.?\s.*$"""), "")
        return t.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', ' ', '"', '«', '»', ':')
    }

    /** Канал как исполнитель: без «- Topic» (это [artistOf]), «VEVO» и «Official». */
    internal fun cleanArtist(channel: String): String =
        artistOf(channel).replace(Regex("""(?i)\s*(vevo|official)$"""), "").trim()

    /**
     * Догадки «исполнитель — название» по порядку. У загрузок вида
     * «Depeche Mode - People Are People» исполнитель — до тире, а канал
     * может быть чей угодно. Бывает и наоборот — «Yes Sir, I Can Boogie -
     * Baccara»: пробуем обе стороны, лишнюю отсеет сверка названия.
     */
    internal fun guesses(title: String, channel: String): List<Pair<String, String>> {
        val t = cleanTitle(plainText(title))
        val artist = cleanArtist(plainText(channel))
        val out = mutableListOf<Pair<String, String>>()
        Regex("""\s+[-–—]\s+""").find(t)?.let { dash ->
            val left = t.substring(0, dash.range.first).trim()
            val right = cleanTitle(t.substring(dash.range.last + 1))
            if (left.isNotEmpty() && right.isNotEmpty()) {
                out += left to right
                out += right to left
            }
        }
        if (t.isNotEmpty()) out += artist to t
        // Название в кавычках — «…EDテーマ「Memento」MV(full size)»: исполнителя
        // в названии нет, ищем по одному названию. Последнее в кавычках —
        // обычно сама песня, раньше — аниме или альбом
        QUOTED.findAll(plainText(title)).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }
            .map(::cleanTitle).filter { it.isNotEmpty() }.toList().asReversed()
            .forEach { out += "" to it }
        return out.distinct()
    }

    private val QUOTED = Regex("""「([^」]+)」|『([^』]+)』|«([^»]+)»|"([^"]+)"|“([^”]+)”""")

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Название из базы — то же, что у нас: совпало или одно входит в другое. */
    internal fun sameTitle(found: String, ours: String): Boolean {
        val a = norm(found)
        val b = norm(ours)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (minOf(a.length, b.length) >= 4 && (a in b || b in a))
    }

    // ── Разметка по времени ──────────────────────────────────────────────────

    private val STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** Строки LRC: «[01:02.50]текст», у строки может быть несколько отметок. */
    internal fun parseLrc(lrc: String): List<Line> {
        val out = mutableListOf<Line>()
        for (raw in lrc.lines()) {
            val stamps = STAMP.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val (mm, ss, frac) = m.destructured
                val f = frac.padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
                out += Line(mm.toLong() * 60_000 + ss.toLong() * 1000 + f, text)
            }
        }
        return out.sortedBy { it.ms }
    }

    /** Какая строка звучит на [positionMs]: последняя начавшаяся; -1 — ещё ни одна. */
    fun current(lines: List<Line>, positionMs: Long): Int =
        lines.indexOfLast { it.ms != null && it.ms <= positionMs }

    // ── LRCLIB ───────────────────────────────────────────────────────────────

    /**
     * Лучший кандидат из LRCLIB: с разметкой и подходящей длиной; нет — с
     * ближайшей длиной, простым текстом. Название обязано совпасть.
     */
    internal fun pick(candidates: List<JSONObject>, title: String, durationS: Double): Found? {
        val fit = candidates.filter { sameTitle(it.optString("trackName"), title) }
        if (fit.isEmpty()) return null
        fun gap(c: JSONObject) = if (durationS > 0) abs(c.optDouble("duration", 0.0) - durationS) else Double.MAX_VALUE
        val close = fit.filter { gap(it) <= SLACK_S }
        fun found(c: JSONObject, lines: List<Line>, synced: Boolean, instrumental: Boolean = false) =
            Found(lines, synced, "LRCLIB", instrumental, c.optString("artistName"), c.optString("trackName"))
        close.firstOrNull { it.optBoolean("instrumental") }?.let {
            return found(it, emptyList(), synced = false, instrumental = true)
        }
        close.filter { it.optString("syncedLyrics").isNotBlank() }.minByOrNull(::gap)?.let { c ->
            val lines = parseLrc(c.optString("syncedLyrics"))
            if (lines.isNotEmpty()) return found(c, lines, synced = true)
        }
        val plain = fit.filter { it.optString("plainLyrics").isNotBlank() || it.optString("syncedLyrics").isNotBlank() }
            .minByOrNull(::gap) ?: return null
        val text = plain.optString("plainLyrics").ifBlank {
            parseLrc(plain.optString("syncedLyrics")).joinToString("\n") { it.text }
        }
        return found(plain, text.trim().lines().map { Line(null, it.trim()) }, synced = false)
    }

    /**
     * Догадки по очереди: размеченный текст — сразу, простой — про запас,
     * вдруг следующая догадка найдёт с разметкой.
     */
    private fun lrclib(title: String, channel: String, durationS: Double): Found? {
        val tries = guesses(title, channel)
        var spare: Found? = null
        for ((artist, name) in tries) {
            val q = "track_name=${enc(name)}" + if (artist.isNotEmpty()) "&artist_name=${enc(artist)}" else ""
            val got = pick(getArray("https://lrclib.net/api/search?$q"), name, durationS) ?: continue
            if (got.synced || got.instrumental) return got
            if (spare == null) spare = got
        }
        // Нашлось только простым текстом, а исполнителя мы не знали — теперь
        // знаем из базы: ищем ещё раз с ним, среди его версий может быть
        // размеченная нужной длины. Чужого исполнителя отсеет сверка с YouTube Music
        spare?.takeIf { s -> s.artist.isNotBlank() && tries.none { norm(it.first) == norm(s.artist) } }?.let { s ->
            val q = "track_name=${enc(s.title)}&artist_name=${enc(s.artist)}"
            val got = pick(getArray("https://lrclib.net/api/search?$q"), s.title, durationS)
            if (got != null && (got.synced || got.instrumental)) return got
        }
        // Ни по одной догадке — свободным запросом, как в строке поиска
        val (artist, name) = tries.firstOrNull { it.first.isNotEmpty() } ?: tries.firstOrNull() ?: return spare
        val got = pick(getArray("https://lrclib.net/api/search?q=${enc("$artist $name".trim())}"), name, durationS)
        return if (got != null && (got.synced || got.instrumental)) got else spare ?: got
    }

    // ── YouTube Music ────────────────────────────────────────────────────────

    /** Текст ролика у YouTube Music: вкладка «Текст» → её страница. */
    private fun youtube(videoId: String): Found? {
        val next = post("next", JSONObject().put("videoId", videoId))
        val browseId = find(next, "tabRenderer").mapNotNull {
            it.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
        }.firstOrNull { it.startsWith("MPLY") } ?: return null
        val page = post("browse", JSONObject().put("browseId", browseId))
        val shelf = find(page, "musicDescriptionShelfRenderer").firstOrNull() ?: return null
        val text = runs(shelf.optJSONObject("description"))
        if (text.isBlank()) return null
        // «Source: Musixmatch» — запрос по-английски, чтобы подпись разбиралась одинаково
        val source = runs(shelf.optJSONObject("footer")).substringAfter(':').trim().ifEmpty { "YouTube Music" }
        return Found(text.trim().lines().map { Line(null, it.trim()) }, synced = false, source = source)
    }

    private fun runs(o: JSONObject?): String {
        val arr = o?.optJSONArray("runs") ?: return ""
        return (0 until arr.length()).joinToString("") { arr.optJSONObject(it)?.optString("text").orEmpty() }
    }

    /** Все объекты под ключом [key] где угодно в дереве ответа. */
    private fun find(node: Any?, key: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject(key)?.let(out::add)
                    for (k in n.keys()) walk(n.opt(k))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(node)
        return out
    }

    // ── Сеть ─────────────────────────────────────────────────────────────────

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getArray(url: String): List<JSONObject> {
        // LRCLIB под нагрузкой отвечает «занят» (503) — через секунду обычно отвечает
        val text = request(url, null) ?: run { Thread.sleep(900); request(url, null) } ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun post(endpoint: String, body: JSONObject): JSONObject {
        body.put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX").put("clientVersion", "1.20250101.01.00").put("hl", "en")))
        val text = request("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false", body.toString())
        return text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    }

    private fun request(url: String, body: String?): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", if (body == null) UA else "Mozilla/5.0")
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Origin", "https://music.youtube.com")
            }
        }
        return try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── Память на диске ──────────────────────────────────────────────────────

    private fun dir(context: Context) = File(context.filesDir, "lyrics").apply { mkdirs() }

    /** null — не спрашивали или пора перепроверить; Result(null) — свежее «не нашлось». */
    private fun cached(file: File): Result<Found?>? {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val f = o.optJSONObject("found")
        val stale = System.currentTimeMillis() - o.optLong("at") >= RECHECK_MS
        if (f == null) return if (stale) null else Result.success(null)
        if (stale && !f.optBoolean("synced") && !f.optBoolean("instrumental")) return null
        val arr = f.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until arr.length()).mapNotNull { i ->
            val l = arr.optJSONArray(i) ?: return@mapNotNull null
            Line(l.optLong(0, -1).takeIf { it >= 0 }, l.optString(1))
        }
        return Result.success(Found(lines, f.optBoolean("synced"), f.optString("source"), f.optBoolean("instrumental")))
    }

    private fun remember(file: File, found: Found?) {
        val o = JSONObject().put("at", System.currentTimeMillis())
        if (found != null) {
            val lines = JSONArray()
            found.lines.forEach { lines.put(JSONArray().put(it.ms ?: -1).put(it.text)) }
            o.put("found", JSONObject().put("synced", found.synced).put("source", found.source)
                .put("instrumental", found.instrumental).put("lines", lines))
        }
        runCatching {
            file.writeText(o.toString())
            // Старое — вон, чтобы папка не росла без конца
            val all = file.parentFile?.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            all.take((all.size - KEEP).coerceAtLeast(0)).forEach { it.delete() }
        }
    }
}
