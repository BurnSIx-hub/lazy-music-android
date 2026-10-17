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

    /**
     * Версия правил сверки. Выросла — законченные импорты при запуске ищут
     * ненайденное заново ([YandexImport.restore]): новые правила могут найти
     * то, что не нашли старые.
     *
     * 2 (2026-10-18) — по первой живой проверке, 11 ненайденных из 1202:
     * артикль в начале, хвост после « - », иероглифы, названия из одних
     * знаков, скобки и минус в запросе, пустой обычный поиск — сбой.
     */
    const val RULES = 2

    private val MARKS = Regex("""\p{M}+""")
    private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
    private val FEAT = Regex("""\s(?:feat|ft|featuring)\.?\s.*$""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val ARTISTS = Regex(""",|&| и | x """, RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\d+""")
    private val MINUS = Regex("""(^|\s)-+(?=\S)""")
    private val SPACES = Regex("""\s+""")

    /** Артикли, без которых название то же: «The House of the Rising Sun» = «House of the Rising Sun». */
    private val ARTICLES = setOf("the", "a", "an")

    private val CJK = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL
    )

    /** Слова названия без скобок, «feat.», знаков и диакритики: «Beggin'» → [beggin]. */
    fun words(text: String?): List<String> {
        var s = Normalizer.normalize(text.orEmpty().lowercase(), Normalizer.Form.NFKD)
        s = MARKS.replace(s, "").replace('ё', 'е')
        s = BRACKETS.replace(s, " ")
        s = FEAT.replace(s, " ")
        return s.split(NOT_WORD).filter { it.isNotEmpty() }
    }

    fun flat(text: String?) = words(text).joinToString("")

    /** Слова без артикля в начале. */
    private fun core(list: List<String>) =
        if (list.size > 1 && list[0] in ARTICLES) list.drop(1) else list

    /** Название из одних знаков («∞») — сами знаки, без пробелов и скобок. */
    private fun symbols(text: String) = SPACES.replace(BRACKETS.replace(text.lowercase(), ""), "")

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
     * «Du hast - Remastered» — одна. Артикль в начале не в счёт, а хвост
     * после « - » в искомом необязателен: в Яндексе «Est-ce que tu m'aimes? -
     * Pilule bleue», на YouTube — «Est-ce que tu m'aimes ? (Pilule bleue)».
     */
    fun sameTitle(found: String, wanted: String): Boolean {
        if (alike(found, wanted)) return true
        val head = wanted.substringBefore(" - ", "")
        return flat(head).length >= 4 && alike(found, head)
    }

    private fun alike(found: String, wanted: String): Boolean {
        val b = core(words(wanted))
        // «∞» у Земфиры — ни буквы, ни цифры: сравниваем сами знаки
        if (b.isEmpty()) return symbols(wanted).isNotEmpty() && symbols(found) == symbols(wanted)
        val a = core(words(found))
        if (a.isEmpty()) return false
        if (a.joinToString("") == b.joinToString("")) return true
        return headOf(a, b) || headOf(b, a)
    }

    /** Исполнитель где-то в названии ролика или в имени канала. */
    fun mentions(row: Radio.Row, artist: String): Boolean {
        val first = flat(artist.split(ARTISTS).first())
        if (first.length < 2) return false
        return flat(row.title).contains(first) || flat(row.uploader).contains(first)
    }

    /**
     * Название для запроса — без скобок и без минуса перед словом. Скобки
     * «(из фильма «Бриджит Джонс 3»)» сбивают поиск: «Песни» отвечают
     * пусто, а без них сразу находят. Минус YouTube читает как «исключить»:
     * «Земфира - -140» выкидывает саму «-140».
     */
    fun searchTitle(title: String): String =
        SPACES.replace(MINUS.replace(BRACKETS.replace(title, " "), "\$1"), " ").trim().ifEmpty { title.trim() }

    fun query(item: Item) =
        "${item.artist} - ${searchTitle(item.title)}${if (item.version.isNotEmpty()) " ${item.version}" else ""}".trim()

    private fun songsUrl(q: String) =
        "https://music.youtube.com/search?q=${URLEncoder.encode(q, "UTF-8").replace("+", "%20")}#songs"

    /**
     * Годится ли ролик обычного поиска: «Rammstein - Du Hast (Official Video)».
     * Нужны исполнитель и название целиком; название короче трёх букв не в
     * счёт — «War» нашлось бы где угодно. Кроме иероглифов и каны: «怪物» у
     * YOASOBI — два знака, и это целое слово.
     */
    private fun fits(v: Radio.Row, item: Item): Boolean {
        if (sameTitle(v.title, item.title)) return true
        val want = core(words(item.title)).joinToString("")
        val long = want.length >= 3 || want.length >= 2 && want.any { Character.UnicodeScript.of(it.code) in CJK }
        return long && mentions(v, item.artist) && flat(v.title).contains(want)
    }

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
            val mine = videos.filter { it.source == query(item) }
            if (mine.isEmpty()) {
                // Обычный поиск тоже отвечает всегда — пустой ответ значит сбой
                // сети («Intelligency — August» не нашёлся в живой проверке,
                // а с первого же повтора находится). Ещё заход, потом сдаёмся
                item.state = if (++item.tries < TRIES * 2) State.WAIT else State.MISS
                continue
            }
            val r = mine.firstOrNull { fits(it, item) }
            if (r != null) {
                item.state = State.OK; item.yt = r.id; item.ytTitle = item.title; item.video = true
            } else {
                item.state = State.MISS
            }
        }
    }
}
