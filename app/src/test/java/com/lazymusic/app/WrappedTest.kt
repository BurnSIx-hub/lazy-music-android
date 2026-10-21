package com.lazymusic.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** «Итоги года»: сложение сводок телефона и ПК и расчёт экранов. */
class WrappedTest {

    private fun year(
        days: Map<String, Double>,
        tracks: Map<String, Triple<String, Int, Double>>,
        hours: Map<Int, Double> = emptyMap()
    ): JSONObject {
        val t = JSONObject()
        val a = JSONObject()
        for ((id, v) in tracks) {
            val (artist, n, s) = v
            t.put(id, JSONObject().put("t", "Трек $id").put("a", artist).put("n", n).put("s", s))
            val e = a.optJSONObject(artist) ?: JSONObject().put("n", 0).put("s", 0.0).also { a.put(artist, it) }
            e.put("n", e.optInt("n") + n).put("s", e.optDouble("s") + s)
        }
        return JSONObject()
            .put("sec", days.values.sum())
            .put("days", JSONObject(days))
            .put("hours", JSONArray(DoubleArray(24) { hours[it] ?: 0.0 }.toList()))
            .put("tracks", t)
            .put("artists", a)
    }

    @Test
    fun season() {
        assertEquals(2026, Wrapped.season(LocalDate.of(2026, 12, 1)))
        assertEquals(2026, Wrapped.season(LocalDate.of(2027, 1, 31)))
        assertNull(Wrapped.season(LocalDate.of(2026, 11, 30)))
        assertNull(Wrapped.season(LocalDate.of(2027, 2, 1)))
    }

    @Test
    fun summary() {
        val y = year(
            days = mapOf(
                "2026-10-20" to 600.0, "2026-10-21" to 1200.0, "2026-10-22" to 20.0,
                "2026-11-01" to 3000.0, "2026-11-02" to 60.0, "2026-11-03" to 60.0, "2026-11-04" to 60.0
            ),
            tracks = mapOf(
                "a" to Triple("Кино", 5, 1000.0),
                "b" to Triple("Кино", 5, 1200.0),
                "c" to Triple("Queen", 7, 2000.0),
                "d" to Triple("Queen", 0, 10.0)
            ),
            hours = mapOf(1 to 4000.0, 20 to 1000.0)
        )
        val s = Wrapped.summary(y, 2026, LocalDate.of(2026, 12, 5))
        assertEquals(83L, s.minutes)
        assertEquals(17, s.plays)
        assertEquals("непрослушанный (0 раз) не в счёте", 3, s.trackCount)
        assertEquals(listOf("c", "b", "a"), s.topTracks.map { it.id })
        assertEquals("по минутам, а не по числу раз", "Кино", s.topArtists.first().name)
        // 22 октября — 20 секунд: меньше прослушивания, день не в счёте
        assertEquals(6, s.days)
        assertEquals("с 20 октября по 5 декабря", 47, s.span)
        assertEquals("1–4 ноября", 4, s.streak)
        assertEquals(LocalDate.of(2026, 11, 1), s.bestDay)
        assertEquals(Wrapped.Part.NIGHT, s.part)
        assertEquals(1, s.peakHour)
        assertEquals(11, s.topMonth)
        assertEquals(30L, s.months[9])
        assertTrue(s.enough)
    }

    @Test
    fun addsPhoneAndPc() {
        val phone = year(mapOf("2026-12-01" to 100.0), mapOf("a" to Triple("Кино", 2, 100.0)), mapOf(23 to 100.0))
        val pc = year(mapOf("2026-12-01" to 50.0, "2026-12-02" to 70.0), mapOf("a" to Triple("Кино", 1, 50.0), "b" to Triple("", 1, 70.0)))
        pc.getJSONObject("tracks").getJSONObject("a").put("t", "")
        val sum = JSONObject().put("sec", 0.0).put("days", JSONObject())
            .put("hours", JSONArray(DoubleArray(24).toList())).put("tracks", JSONObject()).put("artists", JSONObject())
        Listening.addYear(sum, phone)
        Listening.addYear(sum, pc)
        assertEquals(220.0, sum.getDouble("sec"), 0.001)
        assertEquals(150.0, sum.getJSONObject("days").getDouble("2026-12-01"), 0.001)
        assertEquals(3, sum.getJSONObject("tracks").getJSONObject("a").getInt("n"))
        assertEquals("название — от того, у кого оно есть", "Трек a", sum.getJSONObject("tracks").getJSONObject("a").getString("t"))
        assertEquals(3, sum.getJSONObject("artists").getJSONObject("Кино").getInt("n"))
        assertEquals(100.0, sum.getJSONArray("hours").getDouble(23), 0.001)
        assertFalse(Wrapped.summary(sum, 2026).enough)
    }
}
