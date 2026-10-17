package com.lazymusic.app

import com.lazymusic.app.ImportMatch.Item
import com.lazymusic.app.ImportMatch.State
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

/**
 * Импорт из Яндекс Музыки — те же образцы, что `tools/import-selftest.mjs`
 * на ПК. Ответы Яндекса здесь — по устройству его сервиса: из Испании он
 * отвечает 451, живой ответ отсюда не получить.
 *
 * ```
 * ./gradlew testDebugUnitTest
 * ```
 */
class YandexImportTest {

    // ── Ссылки ───────────────────────────────────────────────────────────────

    @Test
    fun links() {
        assertEquals(
            Yandex.Link.Shared("lk.e8f121e1-4e15-4435-847d-fe9fd964fa11"),
            Yandex.link("https://music.yandex.com/playlists/lk.e8f121e1-4e15-4435-847d-fe9fd964fa11?utm_medium=copy_link&ref_id=x")
        )
        assertEquals(Yandex.Link.Owned("some.login", "3"), Yandex.link("music.yandex.ru/users/some.login/playlists/3"))
        assertEquals(Yandex.Link.Album("123456"), Yandex.link("https://music.yandex.ru/album/123456"))
        assertNull("YouTube — не наше", Yandex.link("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull("исполнитель — не поддерживаем", Yandex.link("https://music.yandex.ru/artist/1"))
        assertNull("чужой сайт", Yandex.link("https://evil.example/playlists/x"))
        assertNull("ссылка на один трек — не альбом", Yandex.link("https://music.yandex.ru/album/5/track/7"))
        // Приложение Яндекса делится строкой с текстом вокруг ссылки
        assertEquals(
            Yandex.Link.Owned("u", "1000"),
            Yandex.link("Послушай плейлист «Дорога» https://music.yandex.ru/users/u/playlists/1000?utm_source=share")
        )
    }

    // ── Ответы Яндекса ───────────────────────────────────────────────────────

    private fun answer(status: Int, result: Any? = null) = Yandex.Answer(
        status,
        if (status == 200) JSONObject().put("invocationInfo", JSONObject()).put("result", result).toString()
        else JSONObject().put("error", JSONObject().put("name", "x")).toString()
    )

    private fun track(id: String, title: String?, vararg artists: String, version: Any? = null) = JSONObject().apply {
        put("id", id)
        if (title != null) put("title", title)
        if (version != null) put("version", version)
        put("artists", JSONArray().apply { artists.forEach { put(JSONObject().put("name", it)) } })
    }

    @Test
    fun richPlaylist() {
        val rich = JSONObject().put("title", "Мне нравится").put("tracks", JSONArray().apply {
            put(JSONObject().put("id", 1).put("track", track("1", "Группа крови", "Кино").put("durationMs", 285000)))
            put(JSONObject().put("id", 2).put("track", track("2", "Du hast", "Rammstein", version = "Remastered")))
            put(JSONObject().put("id", 3).put("track", track("3", "Get Lucky", "Daft Punk", "Pharrell Williams")))
            put(JSONObject().put("id", 4).put("track", track("4", null, "Без названия")))
            put(JSONObject().put("id", 5).put("track", track("5", "Без версии", "X", version = JSONObject.NULL)))
        })
        var asked = ""
        val list = Yandex.fetch(Yandex.link("https://music.yandex.ru/playlists/lk.abc")!!) { asked = it; answer(200, rich) }
        assertTrue(asked, asked.endsWith("/playlist/lk.abc?richTracks=true"))
        assertEquals("Мне нравится", list.title)
        assertEquals("без названия пропущен", 4, list.tracks.size)
        assertEquals(285000L, list.tracks[0].ms)
        assertEquals("Remastered", list.tracks[1].version)
        assertEquals("Daft Punk, Pharrell Williams", list.tracks[2].artist)
        assertEquals("null из JSON — пустая версия", "", list.tracks[3].version)
    }

    @Test
    fun bareIdsKeepOrder() {
        val bare = JSONObject().put("title", "Старый").put("tracks", JSONArray().apply {
            put(JSONObject().put("id", "30:9")); put(JSONObject().put("id", "10:9")); put(JSONObject().put("id", "20:9"))
        })
        val full = JSONArray().put(track("10", "Десять", "А")).put(track("20", "Двадцать", "Б")).put(track("30", "Тридцать", "В"))
        val urls = mutableListOf<String>()
        val list = Yandex.fetch(Yandex.link("https://music.yandex.ru/users/u/playlists/7")!!) {
            urls += it
            if ("/tracks?" in it) answer(200, full) else answer(200, bare)
        }
        assertTrue(urls[0], urls[0].endsWith("/users/u/playlists/7"))
        assertEquals("Тридцать,Десять,Двадцать", list.tracks.joinToString(",") { it.title })
    }

    @Test
    fun albumAndErrors() {
        val album = JSONObject().put("title", "Альбом").put("volumes", JSONArray()
            .put(JSONArray().put(track("1", "Раз", "X")))
            .put(JSONArray().put(track("2", "Два", "X"))))
        assertEquals("альбом из двух дисков", 2, Yandex.fetch(Yandex.Link.Album("5")) { answer(200, album) }.tracks.size)

        fun why(get: (String) -> Yandex.Answer) =
            runCatching { Yandex.fetch(Yandex.Link.Shared("lk.x"), get) }.exceptionOrNull().let { (it as? Yandex.Failure)?.why }
        assertEquals(Yandex.Why.REGION, why { answer(451) })
        assertEquals(Yandex.Why.MISSING, why { answer(404) })
        assertEquals(Yandex.Why.HTTP, why { answer(500) })
        assertEquals(Yandex.Why.FORMAT, why { Yandex.Answer(200, "<html>") })
        assertEquals(Yandex.Why.FORMAT, why { Yandex.Answer(200, "{\"error\":1}") })
    }

    // ── Сверка названий ──────────────────────────────────────────────────────

    @Test
    fun titles() {
        val same = listOf(
            "Beggin'" to "Beggin",
            "Get Lucky (feat. Pharrell Williams and Nile Rodgers)" to "Get Lucky",
            "ХОЧЕШЬ?" to "Хочешь?",
            "Du hast - Remastered 2011" to "Du hast",
            "Ёлка" to "Елка",
            "Måneskin" to "Maneskin",
            "Полковнику никто не пишет" to "Полковнику никто не пишет",
        )
        val differ = listOf(
            "Believer" to "Believe",
            "Dame Tu Cosita" to "Группа крови",
            "Каждый раз" to "Каждый",
            "Love Story" to "Love",
        )
        for ((a, b) in same) assertTrue("«$a» = «$b»", ImportMatch.sameTitle(a, b))
        for ((a, b) in differ) assertFalse("«$a» ≠ «$b»", ImportMatch.sameTitle(a, b))
        assertEquals("rock n roll", ImportMatch.words("Rock'n'Roll (Live)").joinToString(" "))
        assertTrue(ImportMatch.mentions(Radio.Row("x", "Rammstein - Du Hast (Official 4K Video)", "", ""), "Rammstein"))
        assertTrue(ImportMatch.mentions(Radio.Row("x", "Группа крови", "Группа КИНО", ""), "Кино"))
    }

    // ── Поиск пачкой ─────────────────────────────────────────────────────────

    private fun item(artist: String, title: String) = Item(artist, title, "", 0)

    /** Поисковая строка из адреса «Песен»: так её вернёт yt-dlp в `playlist_title`. */
    private fun songsQuery(url: String) = URLDecoder.decode(url.substringAfter("q=").substringBefore('#'), "UTF-8")

    @Test
    fun batch() = runBlocking {
        val items = listOf(
            item("Кино", "Группа крови"),
            item("Rammstein", "Du hast"),
            item("Неизвестный", "Несуществующее"),
            item("Сбой", "Пусто"),
        )
        val songs = mapOf(
            "Кино - Группа крови" to listOf("aaaaaaaaaaa" to "Группа крови"),
            // Первая находка — чужая, вторая наша: смотрим не только первую
            "Rammstein - Du hast" to listOf("xxxxxxxxxxx" to "Engel", "bbbbbbbbbbb" to "Du hast"),
            "Неизвестный - Несуществующее" to listOf("ccccccccccc" to "Dame Tu Cosita"),
        )
        val videos = mapOf("Неизвестный - Несуществующее" to Radio.Row("ddddddddddd", "Совсем другое", "кто-то", "Неизвестный - Несуществующее"))
        val probe: suspend (List<String>, Int) -> List<Radio.Row> = { urls, _ ->
            if (urls[0].startsWith("https://music.youtube.com/")) urls.flatMap { u ->
                val q = songsQuery(u)
                songs[q].orEmpty().map { (id, t) -> Radio.Row(id, t, "", "$q - songs") }
            } else urls.mapNotNull { videos[it.substringAfter(':')] }
        }
        ImportMatch.matchBatch(items, probe)
        assertEquals(State.OK, items[0].state); assertEquals("aaaaaaaaaaa", items[0].yt)
        assertEquals("взята совпавшая по названию", "bbbbbbbbbbb", items[1].yt)
        assertEquals("чужое и там, и там — не нашлось", State.MISS, items[2].state)
        assertEquals("пустой ответ «Песен» — сбой", State.WAIT, items[3].state)
        assertEquals(1, items[3].tries)
        ImportMatch.matchBatch(listOf(items[3]), probe)
        assertEquals("второй пустой ответ — не нашлось", State.MISS, items[3].state)
    }

    @Test
    fun secondStep() = runBlocking {
        val film = item("Rammstein", "Du hast")
        ImportMatch.matchBatch(listOf(film)) { urls, _ ->
            if (urls[0].startsWith("ytsearch")) listOf(Radio.Row("eeeeeeeeeee", "Rammstein - Du Hast (Official 4K Video)", "Rammstein Official", "Rammstein - Du hast"))
            else listOf(Radio.Row("fffffffffff", "Sonne", "", "Rammstein - Du hast - songs"))
        }
        assertEquals("ролик с исполнителем и названием", "eeeeeeeeeee", film.yt)
        assertTrue(film.video)

        val gold = item("Аквариум", "Город золотой")
        ImportMatch.matchBatch(listOf(gold)) { urls, _ ->
            if (urls[0].startsWith("ytsearch")) listOf(
                Radio.Row("ggggggggggg", "Асса — Город золотой (Аквариум) — Памяти", "кто-то", "Аквариум - Город золотой"),
                Radio.Row("hhhhhhhhhhh", "Аквариум (БГ) - Город Золотой", "кто-то", "Аквариум - Город золотой"),
            ) else listOf(Radio.Row("iiiiiiiiiii", "Город", "", "Аквариум - Город золотой - songs"))
        }
        assertEquals("трибьют пропущен, взят ролик исполнителя", "hhhhhhhhhhh", gold.yt)
    }

    @Test
    fun outages() = runBlocking {
        val flaky = item("Кто-то", "Что-то")
        ImportMatch.matchBatch(listOf(flaky)) { urls, _ ->
            if (urls[0].startsWith("ytsearch")) throw IOException("сеть пропала")
            listOf(Radio.Row("jjjjjjjjjjj", "Совсем другое", "", "Кто-то - Что-то - songs"))
        }
        assertEquals("сеть пропала на второй попытке — трек ждёт", State.WAIT, flaky.state)

        val threw = runCatching { ImportMatch.matchBatch(listOf(item("a", "b"))) { _, _ -> throw IOException("нет сети") } }.isFailure
        assertTrue("YouTube не ответил вовсе — заход бросает, импорт ждёт", threw)
    }

    @Test
    fun queryAndSongsUrl() {
        val i = Item("Rammstein", "Du hast", "Remastered", 0)
        assertEquals("Rammstein - Du hast Remastered", ImportMatch.query(i))
    }
}
