package com.lazymusic.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Импорт из Spotify — те же образцы, что в `tools/import-selftest.mjs` на ПК.
 * Страница встроенного плеера здесь — по её устройству на 2026-10-20.
 */
class SpotifyTest {

    private val id = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun links() {
        assertEquals(Spotify.Link.Page("playlist", id), Spotify.link("https://open.spotify.com/playlist/$id?si=abc123"))
        assertEquals(Spotify.Link.Page("album", id), Spotify.link("Послушай: https://open.spotify.com/intl-ru/album/$id?si=x"))
        assertEquals(Spotify.Link.Page("playlist", id), Spotify.link("spotify:playlist:$id"))
        assertTrue(Spotify.link("https://spotify.link/AbCdEf123") is Spotify.Link.Short)
        assertNull("один трек — не плейлист", Spotify.link("https://open.spotify.com/track/$id"))
        assertNull(Spotify.link("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    private fun page(type: String, name: String, tracks: List<JSONObject>): String {
        val entity = JSONObject().put("type", type).put("name", name).put("trackList", JSONArray(tracks))
        val data = JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("state",
            JSONObject().put("data", JSONObject().put("entity", entity)))))
        return """<html><script id="__NEXT_DATA__" type="application/json">$data</script></html>"""
    }

    private fun track(uri: String, title: String, subtitle: String, ms: Long = 0) =
        JSONObject().put("uri", uri).put("title", title).put("subtitle", subtitle).put("duration", ms)

    @Test
    fun parse() {
        val list = Spotify.parse(page("playlist", "Мой плейлист", listOf(
            track("spotify:track:1", "Du hast - Remastered 2011", "Rammstein", 234000),
            track("spotify:track:2", "Get Lucky", "Daft Punk, Pharrell Williams", 248000),
            track("spotify:episode:3", "Подкаст", "Кто-то")
        )))
        assertEquals("Мой плейлист", list.title)
        assertEquals("подкаст пропущен", 2, list.tracks.size)
        assertEquals("Daft Punk, Pharrell Williams", list.tracks[1].artist)
        assertEquals(234000L, list.tracks[0].ms)
        assertFalse(list.cut)
        val full = Spotify.parse(page("playlist", "Длинный", (0 until Spotify.CAP).map { track("spotify:track:$it", "T$it", "X") }))
        assertTrue("ровно ${Spotify.CAP} — обрезан", full.cut)
    }

    @Test(expected = Spotify.Failure::class)
    fun broken() {
        Spotify.parse("<html>нет данных</html>")
    }

    @Test
    fun shortLink() {
        val asked = mutableListOf<String>()
        val list = Spotify.fetch(Spotify.Link.Short("https://spotify.link/AbCdEf123")) { url ->
            asked += url
            when {
                url.startsWith("https://spotify.link/") ->
                    Yandex.Answer(200, """<meta property="og:url" content="https://open.spotify.com/playlist/$id">""")
                url == "https://open.spotify.com/embed/playlist/$id" ->
                    Yandex.Answer(200, page("playlist", "Из короткой", listOf(track("spotify:track:1", "A", "B"))))
                else -> Yandex.Answer(404, "")
            }
        }
        assertEquals("Из короткой", list.title)
        assertEquals(2, asked.size)
    }
}
