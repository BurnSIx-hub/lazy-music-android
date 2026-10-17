package com.lazymusic.app

import androidx.media3.common.util.UnstableApi

/**
 * Кнопка 👎 — как на ПК. Играющий трек сразу перелистывается; из очереди
 * уходит он сам, а в радио — и часть похожих. Пять секунд можно передумать:
 * «Отменить» вернёт и 👍, и очередь, и место в треке.
 */
@UnstableApi
object Dislike {

    private const val UNDO_MS = 5_000L

    /** Ставит дизлайк, а на дизлайкнутом — снимает. */
    fun toggle(host: PlayerHost, track: Track) {
        if (track.id in Taste) {
            Taste.remove(track.id)
            Plaques.toast("Дизлайк снят: трек снова может попасться в радио")
        } else {
            dislike(host, track)
        }
    }

    private fun dislike(host: PlayerHost, track: Track) {
        val mark = host.mark()
        val playing = host.current?.id == track.id

        // Сердце и дизлайк не уживаются: из понравившегося трек уходит
        var likedAt = -1
        if (track.id in Likes) {
            likedAt = Settings.playlist(Likes.LIST).indexOfFirst { it.id == track.id }
            Likes.toggle(track)
        }
        val rec = Taste.add(track)

        val thinned = thin(host)
        if (playing) {
            if (host.hasNext) host.next() else host.pause()
        }
        // Из очереди уходит и он сам — чтобы «назад» и повтор его не вернули
        host.drop { it.id == track.id }
        val after = host.queueKey()

        val notes = listOfNotNull(
            "убран из понравившегося".takeIf { likedAt >= 0 },
            "похожих в радио: −$thinned".takeIf { thinned > 0 }
        )
        Plaques.undoable(
            text = "Не нравится: «${track.title}»",
            sub = notes.joinToString(" · ").ifEmpty { "Больше не попадётся в радио и подборке" },
            ms = UNDO_MS,
            onUndo = {
                Taste.remove(track.id)
                if (likedAt >= 0) {
                    Settings.insertIntoPlaylist(Likes.LIST, track, likedAt)
                    Likes.load()
                }
                // Очередь с тех пор не трогали — возвращаем как было, с того же места
                if (mark != null && host.queueKey() == after) host.rewind(mark)
            },
            // Дизлайк устоялся — узнаём соседей исполнителя и ещё раз прореживаем
            onDone = { Taste.settle(rec) { thin(host) } }
        )
    }

    /** Прореживает то, что радио ещё сыграет. @return сколько убрано */
    private fun thin(host: PlayerHost): Int {
        val seed = RadioJob.seedArtist ?: return 0
        val ahead = host.upcoming().distinctBy { it.id }
        val kept = Taste.screen(ahead, seed, Taste.MIN_AHEAD).mapTo(HashSet()) { it.id }
        val gone = ahead.map { it.id }.filterNot { it in kept }.toSet()
        if (gone.isEmpty()) return 0
        return host.drop { it.id in gone }
    }
}
