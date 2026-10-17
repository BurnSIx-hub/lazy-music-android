package com.lazymusic.app

import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Сверка находок YouTube с треками чужого списка — для [YandexImport].
 * Та же, что в `imports.js` на ПК, и проверяется теми же образцами
 * (`src/test`). Отдельно от импорта, чтобы проверка шла без Android.
 */
object ImportMatch {

    /** Сколько находок смотрим на запрос. */
    private const val TOP = 3

    /** Столько раз пустой ответ «Песен» — и трек «не нашёлся». */
    private const val TRIES = 2

    enum class State { WAIT, OK, MISS }

    class Item(
        val artist: String,
        val title: String,
        val version: String,
        val ms: Long,
        var state: State = State.WAIT,
        var tries: Int = 0,
        var yt: String? = null,
        var ytTitle: String? = null,
        var video: Boolean = false,
        /** null — ещё не клали; false — тот же ролик уже был в списке. */
        var added: Boolean? = null
    )

    // ── Сверка находки ───────────────────────────────────────────────────────

    private val MARKS = Regex("""\p{M}+""")
    private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
    private val FEAT = Regex("""\s(?:feat|ft|featuring)\.?\s.*$""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val ARTISTS = Regex(""",|&| и | x """, RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\d+""")

    /** Слова названия без скобок, «feat.», знаков и диакритики: «Beggin'» → [beggin]. */
    fun words(text: String?): List<String> {
        var s = Normalizer.normalize(text.orEmpty().lowercase(), Normalizer.Form.NFKD)
        s = MARKS.replace(s, "").replace('ё', 'е')
        s = BRACKETS.replace(s, " ")
        s = FEAT.replace(s, " ")
        return s.split(NOT_WORD).filter { it.isNotEmpty() }
    }

    fun flat(text: String?) = words(text).joinToString("")

    /** Хвосты, после которых название то же: «Du hast - Remastered 2011». */
    private val TAIL = setOf(
        "remaster", "remastered", "remix", "version", "edit", "mix", "live", "mono", "stereo",
        "radio", "original", "album", "single", "acoustic", "instrumental", "extended", "explicit", "clean",
        "deluxe", "bonus", "official", "audio", "video", "lyrics", "ремастер", "версия"
    )

    private fun headOf(long: List<String>, short: List<String>): Boolean {
        if (short.size > long.size || short.indices.any { long[it] != short[it] }) return false
        val next = long.getOrNull(short.size) ?: return true
        return next in TAIL || NUMBER.matches(next)
    }

    /**
     * Название находки — то же, что искали? Слово в слово или с хвостом из
     * [TAIL]: «Believe» и «Believer» — разные песни, а «Du hast» и
     * «Du hast - Remastered» — одна.
     */
    fun sameTitle(found: String, wanted: String): Boolean {
        val a = words(found)
        val b = words(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.joinToString("") == b.joinToString("")) return true
        return headOf(a, b) || headOf(b, a)
    }

    /** Исполнитель где-то в названии ролика или в имени канала. */
    fun mentions(row: Radio.Row, artist: String): Boolean {
        val first = flat(artist.split(ARTISTS).first())
        if (first.length < 2) return false
        return flat(row.title).contains(first) || flat(row.uploader).contains(first)
    }

    fun query(item: Item) =
        "${item.artist} - ${item.title}${if (item.version.isNotEmpty()) " ${item.version}" else ""}".trim()

    private fun songsUrl(q: String) =
        "https://music.youtube.com/search?q=${URLEncoder.encode(q, "UTF-8").replace("+", "%20")}#songs"

    /**
     * Один заход: «Песни» по всем трекам пачки, потом обычный поиск по тем,
     * что не сошлись. Меняет `state` у трека. Бросает, если YouTube не
     * ответил вовсе.
     */
    suspend fun matchBatch(
        items: List<Item>,
        probe: suspend (List<String>, Int) -> List<Radio.Row> = { urls, per -> Radio.probe(urls, per, strict = true) }
    ) {
        val rows = probe(items.map { songsUrl(query(it)) }, TOP)
        val bySource = rows.groupBy { it.source }
        val second = mutableListOf<Item>()
        for (item in items) {
            val found = bySource["${query(item)} - songs"].orEmpty()
            val hit = found.firstOrNull { sameTitle(it.title, item.title) }
            when {
                hit != null -> {
                    item.state = State.OK; item.yt = hit.id; item.ytTitle = hit.title
                }
                // Пустой ответ на «Песни» — сбой, а не «нет такого»: «Песни» отвечают всегда
                found.isEmpty() && ++item.tries < TRIES -> item.state = State.WAIT
                else -> second += item
            }
        }
        if (second.isEmpty()) return
        val videos = try {
            // Три, а не один: «Аквариум — Город золотой» первым отдаёт трибьют,
            // а сам «Аквариум» — вторым
            probe(second.map { "ytsearch$TOP:${query(it)}" }, TOP)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Сеть пропала посреди захода — это не «не нашлось»: в очередь заново
            second.forEach { it.state = State.WAIT }
            return
        }
        for (item in second) {
            // Обычный поиск отдаёт ролики: «Rammstein - Du Hast (Official Video)».
            // Годится, если в нём и исполнитель, и название целиком
            val want = flat(item.title)
            val r = videos.firstOrNull { v ->
                v.source == query(item) && (sameTitle(v.title, item.title) ||
                    mentions(v, item.artist) && want.length >= 3 && flat(v.title).contains(want))
            }
            if (r != null) {
                item.state = State.OK; item.yt = r.id; item.ytTitle = item.title; item.video = true
            } else {
                item.state = State.MISS
            }
        }
    }
}
