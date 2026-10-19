package com.lazymusic.app

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Разбор ссылок YouTube и добыча сведений о ролике **без ключа API**.
 *
 * Это единственный путь, которым приложение полезно сразу после установки:
 * поиск и плейлисты требуют ключа из Google Cloud, а вставленная ссылка —
 * нет, её разбирает yt-dlp. Дальше от заигравшего трека работают и радио,
 * и скачивание, тоже без ключа.
 */
object Links {

    private const val ID_LENGTH = 11
    private val ID_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')

    /** Идентификатор плейлиста из ссылки, если он там есть. */
    fun playlistIdFrom(text: String): String? {
        val uri = asUri(text) ?: return null
        return uri.getQueryParameter("list")?.takeIf { it.isNotBlank() }
    }

    /**
     * Идентификатор ролика из чего угодно: полной ссылки, короткой `youtu.be`,
     * `/shorts/`, `/embed/`, адреса YouTube Music или просто голого номера.
     */
    fun videoIdFrom(text: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        if (looksLikeId(clean)) return clean

        val uri = asUri(clean) ?: return null
        val host = uri.host.orEmpty().removePrefix("www.")

        // youtu.be/<id>
        if (host == "youtu.be") {
            return uri.pathSegments.firstOrNull()?.takeIf(::looksLikeId)
        }

        // Обычный просмотр: ?v=<id> — сюда же попадают m. и music.
        uri.getQueryParameter("v")?.takeIf(::looksLikeId)?.let { return it }

        // /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
        val segments = uri.pathSegments.orEmpty()
        val marker = segments.indexOfFirst { it in setOf("shorts", "embed", "live", "v") }
        if (marker >= 0) {
            return segments.getOrNull(marker + 1)?.takeIf(::looksLikeId)
        }
        return null
    }

    /**
     * Название и исполнитель ролика. Идёт в yt-dlp, а не в Data API:
     * так работает и без ключа, и не тратит квоту.
     */
    suspend fun track(context: Context, videoId: String): Track = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--skip-download")
            YtLogin.jarIfNeeded(context)?.let { addOption("--cookies", it.absolutePath) }
        }
        val info = try {
            Engine.extracting { YoutubeDL.getInstance().getInfo(request) }
        } catch (t: Throwable) {
            throw IOException(tr("не удалось открыть ролик: {причина}", "причина" to t.message), t)
        }
        Track(
            id = videoId,
            title = Settings.trackName(videoId)
                ?: info.title?.takeIf { it.isNotBlank() } ?: videoId,
            artist = artistOf(info.uploader),
            artworkUrl = info.thumbnail?.takeIf { it.isNotBlank() }
                ?: Radio.thumbnailUrl(videoId)
        )
    }

    private fun looksLikeId(value: String) =
        value.length == ID_LENGTH && value.all { it in ID_CHARS }

    private fun asUri(text: String): Uri? = runCatching {
        // Без схемы Uri не разберёт ни хост, ни параметры
        val withScheme = if (text.startsWith("http")) text else "https://$text"
        Uri.parse(withScheme)
    }.getOrNull()
}
