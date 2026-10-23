package com.lazymusic.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тексты песен: разбор названий роликов, разметки LRC и выбор из LRCLIB.
 * Строки в образцах выдуманные — настоящих текстов здесь нет.
 */
class LyricsTest {

    @Test
    fun cleansTitles() {
        assertEquals("People Are People", Lyrics.cleanTitle("People Are People (Official Video)"))
        assertEquals("Song", Lyrics.cleanTitle("Song [MV] 【HD】"))
        assertEquals("Song", Lyrics.cleanTitle("Song | Ending 2"))
        assertEquals("Song", Lyrics.cleanTitle("Song ft. Someone Else"))
        assertEquals("Song", Lyrics.cleanTitle("Song (Lyric Video) 4K"))
        assertEquals("Band", Lyrics.cleanArtist("Band - Topic"))
        assertEquals("Band", Lyrics.cleanArtist("BandVEVO"))
    }

    @Test
    fun guessesArtistFromDash() {
        val g = Lyrics.guesses("Depeche Mode - People Are People (Official Video)", "Some Channel")
        assertEquals("Depeche Mode" to "People Are People", g.first())
        // Бывает «Песня - Исполнитель» — вторая догадка наоборот
        assertEquals("People Are People" to "Depeche Mode", g[1])
        assertEquals("Some Channel" to "Depeche Mode - People Are People", g[2])
        // Без тире — исполнитель из канала
        assertEquals(listOf("Band" to "Song"), Lyrics.guesses("Song (Official Audio)", "Band - Topic"))
        // Название песни в кавычках, исполнителя нет: последнее в кавычках — первым
        val q = Lyrics.guesses("Аниме「Сериал」2nd season ED「Песня」MV(full size)", "Studio")
        assertEquals("" to "Песня", q[1])
        assertEquals("" to "Сериал", q[2])
    }

    @Test
    fun agreesOnSameSong() {
        fun f(vararg l: String) = Lyrics.Found(l.map { Lyrics.Line(null, it) }, synced = false, source = "")
        val a = f("Первая выдуманная строка", "Вторая выдуманная строка", "Третья строка песни")
        assertTrue(Lyrics.agree(a, f("первая выдуманная строка!", "Совсем другая", "Ещё одна другая")))
        assertFalse(Lyrics.agree(a, f("Чужая песня раз", "Чужая песня два", "Чужая песня три")))
    }

    @Test
    fun parsesLrc() {
        val lines = Lyrics.parseLrc(
            "[ar:Кто-то]\n[00:05.20]Первая выдуманная строка\n[00:09.5]Вторая\n" +
                "[00:12.00][01:02.000]Припев дважды\n[00:15.00]\n"
        )
        assertEquals(listOf(5200L, 9500L, 12000L, 15000L, 62000L), lines.map { it.ms })
        assertEquals("Припев дважды", lines.last().text)
        assertEquals("", lines[3].text)
        assertEquals(-1, Lyrics.current(lines, 1000))
        assertEquals(1, Lyrics.current(lines, 9500))
        assertEquals(4, Lyrics.current(lines, 99_000))
    }

    private fun cand(title: String, duration: Double, synced: String = "", plain: String = "", instrumental: Boolean = false) =
        JSONObject().put("trackName", title).put("duration", duration)
            .put("syncedLyrics", synced).put("plainLyrics", plain).put("instrumental", instrumental)

    @Test
    fun picksSyncedOnlyWhenLengthFits() {
        val lrc = "[00:01.00]Раз\n[00:02.00]Два"
        val fits = Lyrics.pick(listOf(cand("Song", 200.0, synced = lrc, plain = "Раз\nДва")), "Song", 201.5)!!
        assertTrue(fits.synced)
        assertEquals(2, fits.lines.size)
        // У клипа вступление на 20 секунд — разметка разъедется, берём простым текстом
        val far = Lyrics.pick(listOf(cand("Song", 200.0, synced = lrc, plain = "Раз\nДва")), "Song", 220.0)!!
        assertFalse(far.synced)
        assertEquals(listOf("Раз", "Два"), far.lines.map { it.text })
    }

    @Test
    fun picksByTitleAndCloserLength() {
        val list = listOf(
            cand("Другая песня", 180.0, synced = "[00:01.00]Не та"),
            cand("Song (Live)", 260.0, synced = "[00:01.00]Концерт"),
            cand("Song", 181.0, synced = "[00:01.00]Та самая")
        )
        val got = Lyrics.pick(list, "Song", 180.0)!!
        assertTrue(got.synced)
        assertEquals("Та самая", got.lines.single().text)
        assertNull(Lyrics.pick(list.take(1), "Song", 180.0))
    }

    @Test
    fun knowsInstrumental() {
        val got = Lyrics.pick(listOf(cand("Theme", 120.0, instrumental = true)), "Theme", 121.0)!!
        assertTrue(got.instrumental)
        assertTrue(Lyrics.sameTitle("Theme", "Main Theme"))
        assertFalse(Lyrics.sameTitle("Ab", "Abc"))
    }
}
