package com.lazymusic.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ввоз списков файлом — то, чего не добыть ни ссылкой, ни поиском.
 *
 * Понравившееся в YouTube Music ссылки не имеет: поделиться этим списком
 * нельзя, и внутрь приложения он никак не попадал. Зато выгрузить его
 * можно — Google Takeout или `yt-dlp` с cookies, — а дальше нужен только
 * способ занести готовое.
 *
 * Способ тот же, что у ключа API ([Settings.importKeyFromFile]): положить
 * файл рядом с кэшем через `adb push`, приложение заберёт его при
 * следующем запуске.
 *
 * ```
 * adb push список.json /sdcard/Android/data/com.lazymusic.app/files/cache/
 * ```
 *
 * Формат — тот же, что приложение отдаёт компьютеру ([Share]), так что
 * выгрузка и ввоз читают друг друга:
 *
 * ```json
 * { "playlists": [ { "name": "Понравившееся",
 *                    "tracks": [ { "id": "...", "title": "...", "artist": "..." } ] } ] }
 * ```
 *
 * **Ввоз добавляет, а не замещает** — в отличие от переноса в модуль. Там
 * телефон источник и список должен совпасть один в один; здесь наоборот,
 * файл приехал со стороны, и стирать им собранное руками было бы дико.
 */
object Import {

    private const val TAG = "LazyMusic"

    /** Сколько треков приехало последним ввозом — чтобы сказать об этом вслух. */
    var arrived = 0
        private set

    fun clear() {
        arrived = 0
    }

    /**
     * Забирает все `*.json` из папки кэша. Разобранные файлы удаляет,
     * битые переименовывает в `.bad`: молча стирать непонятое нельзя —
     * человек будет ждать список и не поймёт, куда тот делся.
     *
     * @return сколько треков добавилось
     */
    fun fromFiles(context: Context): Int {
        val dir = Cache.dir(context)
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json", true) }
        if (files.isNullOrEmpty()) return 0

        var total = 0
        for (file in files) {
            val got = runCatching { one(file) }
                .onFailure {
                    Log.w(TAG, "ввоз не удался (${file.name}): ${it.message}")
                    file.renameTo(File(file.parentFile, "${file.name}.bad"))
                }
                .getOrNull() ?: continue
            total += got
            file.delete()
            Log.i(TAG, "ввезено из ${file.name}: $got треков")
        }
        arrived += total
        return total
    }

    // ── Разбор ───────────────────────────────────────────────────────────────

    private fun one(file: File): Int {
        val root = JSONObject(file.readText())
        // Файл может содержать и набор списков, и один-единственный
        val lists = root.optJSONArray("playlists") ?: JSONArray().put(root)

        var added = 0
        for (i in 0 until lists.length()) {
            val list = lists.optJSONObject(i) ?: continue
            val name = list.optString("name").trim().ifBlank { file.nameWithoutExtension }
            val tracks = list.optJSONArray("tracks").toTracks()
            if (tracks.isEmpty()) continue
            Settings.createPlaylist(name) // уже есть — просто дописываем
            added += Settings.addManyToPlaylist(name, tracks)
        }

        // Свои названия, если приехали вместе со списком
        root.optJSONObject("names")?.let { names ->
            for (id in names.keys()) {
                names.optString(id).takeIf { it.isNotBlank() }?.let { Settings.rename(id, it) }
            }
        }
        return added
    }

    private fun JSONArray?.toTracks(): List<Track> {
        val arr = this ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(
                id = id,
                title = o.optString("title").trim().ifBlank { id },
                artist = o.optString("artist").trim(),
                artworkUrl = o.optString("art").trim().ifBlank { Radio.thumbnailUrl(id) }
            )
        }
    }
}
