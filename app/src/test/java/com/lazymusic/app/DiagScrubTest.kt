package com.lazymusic.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Вычистка журнала перед отправкой ([Diag.scrub]). Ключ и адреса — выдуманные. */
class DiagScrubTest {

    @Test
    fun linkKeepsOnlyServer() {
        val line = "abc — отказ: https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&ip=192.0.2.10&sig=xyz"
        assertEquals("abc — отказ: https://rr3---sn-abc.googlevideo.com/…", Diag.scrub(line))
    }

    @Test
    fun keyMailAndAddressesGo() {
        val key = "AIza" + "x".repeat(35)
        val out = Diag.scrub("ключ $key, почта someone@example.com, адрес 192.0.2.10 и 2001:db8::1, ещё 2a00:1450:4003:80b:0:0:0:200e")
        assertFalse(out.contains(key))
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("192.0.2.10"))
        assertFalse(out.contains("2001:db8"))
        assertFalse(out.contains("2a00"))
    }

    @Test
    fun timesAndVersionsStay() {
        val line = "10-18 17:56:03 I запуск приложения 1.11.2, yt-dlp 2025.10.22, через 30 с"
        assertEquals(line, Diag.scrub(line))
    }

    @Test
    fun computerNameGoes() {
        assertEquals("сопряжён с компьютером «‹компьютер›»", Diag.scrub("сопряжён с компьютером «DESKTOP-TEST»", "DESKTOP-TEST"))
        // Короткое имя не трогаем — слишком легко задеть чужое слово
        assertEquals("PC и PC", Diag.scrub("PC и PC", "PC"))
    }
}
