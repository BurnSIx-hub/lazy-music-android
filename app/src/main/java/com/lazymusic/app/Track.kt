package com.lazymusic.app

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Трек очереди. `id` — идентификатор видео YouTube, он же имя файла в кэше.
 *
 * Ссылка на звук здесь намеренно не хранится: она живёт считанные часы,
 * поэтому добывается в момент проигрывания — см. [YtDataSource].
 */
/**
 * Имя исполнителя из названия канала YouTube.
 *
 * «- Topic» — служебный суффикс автоканалов: в названии там стоит сам
 * исполнитель, а хвост мешает и глазу, и сверке с каталогом [[Deezer]],
 * и подсчёту разнообразия в очереди радио.
 */
fun artistOf(channelTitle: String?): String =
    channelTitle.orEmpty().trim().removeSuffix(" - Topic").trim()

private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to 160.toChar().toString())

/**
 * Названия без HTML-сущностей: поиск YouTube Data API отдаёт «We&#39;ll»
 * вместо «We'll». Как `plainText` на ПК — разбираются только сущности,
 * теги не трогаются: «I <3 you» должно остаться как есть.
 */
fun plainText(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e.lowercase()]
        } ?: m.value
    }
}

data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val artworkUrl: String? = null
) {
    /** Обратное к [MediaItem.toTrack]: всё, что там читается, кладётся здесь. */
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri("${YtDataSource.SCHEME}:$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .apply { artworkUrl?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                .build()
        )
        .build()

    companion object {
        /** Страница-переходник: в Lazy Music, если стоит, иначе на YouTube. Та же, что у ПК. */
        const val PAGE = "https://burnsix-hub.github.io/lazy-music-pc/play/"

        /**
         * Ссылка на трек, чтобы поделиться, — через переходник: у кого Lazy
         * Music стоит, откроется в ней, нет — на YouTube. Название и
         * исполнитель — чтобы страница показала трек без запросов.
         */
        fun link(track: Track): String {
            fun enc(s: String) = java.net.URLEncoder.encode(s.take(80), "UTF-8").replace("+", "%20")
            return "$PAGE?v=${track.id}&t=${enc(track.title)}&a=${enc(track.artist)}"
        }
    }
}

/**
 * Трек из того, что лежит в плеере.
 *
 * Нужно там, где очередь читают обратно: у плеера она хранится как
 * `MediaItem`, и всё, что мы о треке знаем, лежит в его метаданных.
 */
fun MediaItem.toTrack() = Track(
    id = mediaId,
    title = plainText(mediaMetadata.title?.toString().orEmpty()).ifBlank { mediaId },
    artist = plainText(mediaMetadata.artist?.toString().orEmpty()),
    artworkUrl = mediaMetadata.artworkUri?.toString()
)
