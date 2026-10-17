package com.lazymusic.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал, который переживает перезапуск и читается с компьютера.
 *
 * Системный журнал Android для этого не годится: он кольцевой, общий на
 * весь телефон, и строки приложения вымываются оттуда за минуты. Когда
 * пришла жалоба «постоянно вылазит 403», в нём нашлось четыре строки за
 * последние три минуты — и ни одной ошибки. Разбираться пришлось по коду
 * и догадкам, а не по фактам.
 *
 * Поэтому всё, что касается добычи звука, — отказы, повторы, смены сети,
 * обновления yt-dlp — пишется ещё и в файл:
 *
 * ```
 * adb shell cat /sdcard/Android/data/com.lazymusic.app/files/log/lazy-music.log
 * ```
 *
 * **Чего здесь нет намеренно:** ссылок на поток и адресов. В ссылке зашит
 * адрес телефона и подпись, по которой звук отдают; папка приложения
 * открыта для `adb` и резервных копий, и класть туда такое незачем. Для
 * разбора хватает того, что от ссылки остаётся: сколько ей минут, какой
 * клиент её выдал и к какому семейству адресов она привязана.
 */
object Diag {

    private const val TAG = "LazyMusic"

    /** Больше этого файл не растёт: старое уезжает в соседний, тот затирается. */
    private const val LIMIT = 256 * 1024L

    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun attach(context: Context) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "log")
        dir.mkdirs()
        file = File(dir, "lazy-music.log")
    }

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    /**
     * Суть отказа yt-dlp — строка «ERROR: …». Его текст — весь вывод в
     * ошибки, и предупреждения идут первыми: у знакомой 2026-10-18 экран
     * заняли «ffmpeg-location … does not exist» и «No title found», а сама
     * причина не влезла. Нет такой строки — вся цепочка, как раньше.
     */
    fun cause(error: Throwable, limit: Int = 300): String {
        val all = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        return all.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("ERROR:") }?.take(limit)
            ?: chain(error, limit)
    }

    /** Вся цепочка причин одной строкой: у ExoPlayer суть обычно на дне. */
    fun chain(error: Throwable, limit: Int = 400): String =
        generateSequence(error) { it.cause }
            .mapNotNull { e -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName }
            .distinct()
            .joinToString(" <- ")
            .take(limit)

    @Synchronized
    private fun write(level: String, message: String) {
        if (level == "W") Log.w(TAG, message) else Log.i(TAG, message)
        val target = file ?: return
        runCatching {
            if (target.length() > LIMIT) {
                val old = File(target.parentFile, "lazy-music.1.log")
                old.delete()
                target.renameTo(old)
            }
            target.appendText("${stamp.format(Date())} $level $message" + System.lineSeparator())
        }
    }
}
