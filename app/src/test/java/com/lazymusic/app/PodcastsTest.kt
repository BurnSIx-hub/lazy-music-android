package com.lazymusic.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Сводка подкастов — те же случаи, что в `podcasts.rs` на ПК. */
class PodcastsTest {

    private fun ch(id: String, added: Long, checked: Long, vararg eps: Pair<String, Long>) = JSONObject()
        .put("id", id).put("name", id).put("added", added).put("checked", checked)
        .put("episodes", JSONArray().apply {
            eps.forEach { (e, a) -> put(JSONObject().put("id", e).put("title", e).put("dur", 60).put("added", a)) }
        })

    private fun state(vararg chans: JSONObject, progress: JSONObject = JSONObject(), removed: JSONObject = JSONObject()) =
        JSONObject().put("channels", JSONArray().apply { chans.forEach { put(it) } })
            .put("progress", progress).put("removed", removed)

    private fun ids(o: JSONObject) = (0 until o.getJSONArray("channels").length())
        .map { o.getJSONArray("channels").getJSONObject(it).getString("id") }

    @Test
    fun subscribeAndUnsubscribe() {
        val pc = state(ch("UCa", 100, 100)).put("speed", 1.5)
        val phone = state(ch("UCb", 200, 200), removed = JSONObject().put("UCa", 150))
        val m = Podcasts.merge(pc, phone)
        assertEquals(listOf("UCb"), ids(m))
        assertEquals(1.5, m.getDouble("speed"), 0.0)
        val m2 = Podcasts.merge(m, state(ch("UCa", 300, 300)))
        assertEquals(listOf("UCb", "UCa"), ids(m2))
        assertEquals(m2.toString(), Podcasts.merge(m2, m2).toString())
    }

    @Test
    fun newerListAndProgressWin() {
        val a = state(ch("UCa", 1, 100, "v1" to 0L), progress = JSONObject().put("v1", JSONObject().put("p", 10).put("at", 5)))
        val b = state(ch("UCa", 1, 200, "v2" to 200L, "v1" to 0L),
            progress = JSONObject().put("v1", JSONObject().put("p", 99).put("at", 9)).put("v2", JSONObject().put("p", 1).put("at", 1)))
        val m = Podcasts.merge(a, b)
        val eps = m.getJSONArray("channels").getJSONObject(0).getJSONArray("episodes")
        assertEquals(2, eps.length())
        assertEquals(200L, eps.getJSONObject(0).getLong("added"))
        assertEquals(99, m.getJSONObject("progress").getJSONObject("v1").getInt("p"))
        val c = state(ch("UCa", 1, 50, "v2" to 0L))
        assertEquals(0L, Podcasts.merge(m, c).getJSONArray("channels").getJSONObject(0)
            .getJSONArray("episodes").getJSONObject(0).getLong("added"))
    }

    @Test
    fun channelLinks() {
        val yt = { p: String -> "https://www.youtube.com/$p" }
        assertEquals(yt("@GEOSHOW"), Podcasts.channelBase("https://www.youtube.com/@GEOSHOW"))
        assertEquals(yt("@POLICEMAN1"), Podcasts.channelBase("youtube.com/@POLICEMAN1/videos?si=x"))
        assertEquals(yt("@GEOSHOW"), Podcasts.channelBase("@GEOSHOW"))
        assertEquals(yt("channel/UC8M5YVWQan_3Elm-URehz9w"), Podcasts.channelBase("https://www.youtube.com/channel/UC8M5YVWQan_3Elm-URehz9w/featured"))
        assertNull(Podcasts.channelBase("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(Podcasts.channelBase("https://example.com/@GEOSHOW"))
        assertNull(Podcasts.channelBase("https://www.youtube.com/channel/bad"))
    }

    @Test
    fun episodeLine() {
        val s = Podcasts.parseEpisode("IAB_XLOxI0s", "ИСКАЛИ\u00016104.0\u0001UCyjf5CxCNEc9ALYlalBiKDQ\u0001GEO\u0001GEO\n")!!
        assertEquals("UCyjf5CxCNEc9ALYlalBiKDQ", s.chId)
        assertEquals("GEO", s.chName)
        assertEquals(6104, s.e.dur)
        assertEquals("ИСКАЛИ", s.e.title)
        assertNull(Podcasts.parseEpisode("x", "a\u0001NA\u0001NA\u0001NA\u0001NA"))
    }
}
