package com.lazymusic.app

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * Смотрит вперёд по очереди и просит [StreamUrls] добыть ссылки заранее.
 *
 * Живёт при плеере в службе, а не в активности: подготовка нужна и тогда,
 * когда экран погашен, а приложение свёрнуто, — то есть как раз в обычном
 * случае.
 *
 * Следующий трек берётся не «индекс плюс один», а через таймлайн: при
 * перемешивании и повторе порядок другой, и греть не тот трек — впустую.
 */
class Prefetcher(context: Context, private val player: Player) : Player.Listener {

    private val app = context.applicationContext

    /** На сколько треков вперёд смотрим. Два — это запас примерно на песню. */
    private companion object { const val DEPTH = 2 }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()

    /** Очередь переписали — радио собралось, плейлист открыли. */
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refresh()

    override fun onRepeatModeChanged(repeatMode: Int) = refresh()

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) refresh()
    }

    /**
     * Собрать запас для ближайших треков. Открыта наружу: после смены сети
     * запас выбрасывается целиком ([Recovery]), и собрать его надо сразу,
     * а не к следующему переходу.
     */
    fun refresh() {
        StreamUrls.warm(app, upcoming())
    }

    /** Идентификаторы ближайших треков в том порядке, в каком их возьмёт плеер. */
    private fun upcoming(): List<String> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = mutableListOf<String>()
        var index = player.currentMediaItemIndex
        repeat(DEPTH) {
            index = timeline.getNextWindowIndex(
                index,
                player.repeatMode,
                player.shuffleModeEnabled
            )
            if (index == C.INDEX_UNSET) return out
            out += player.getMediaItemAt(index).mediaId
        }
        return out
    }
}
