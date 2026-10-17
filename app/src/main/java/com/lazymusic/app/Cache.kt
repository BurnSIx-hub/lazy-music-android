package com.lazymusic.app

import android.content.Context
import java.io.File

/**
 * Папка со скачанными дорожками.
 *
 * Формат тот же, что у модуля Foundry: имя файла — `<videoId>.m4a`. Значит
 * треки, уже скачанные на ПК, можно просто скопировать сюда, и наоборот.
 *
 * Лежит во внешней папке приложения — туда можно положить файл руками
 * или через `adb push`, без root и без разрешений.
 */
object Cache {

    private val AUDIO_EXT = listOf("m4a", "webm", "opus", "mp3", "ogg", "aac")

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "cache")
            .apply { if (!exists()) mkdirs() }

    /** Файл дорожки, если она уже скачана. */
    fun find(context: Context, videoId: String): File? {
        val dir = dir(context)
        for (ext in AUDIO_EXT) {
            val f = File(dir, "$videoId.$ext")
            if (f.isFile && f.length() > 0) return f
        }
        return null
    }

    fun sizeBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun count(context: Context): Int =
        dir(context).listFiles()?.count { it.isFile } ?: 0
}
