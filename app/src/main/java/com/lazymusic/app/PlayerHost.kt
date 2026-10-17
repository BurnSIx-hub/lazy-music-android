package com.lazymusic.app

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Пульт к [PlaybackService], пригодный для Compose.
 *
 * Держит текущее состояние проигрывания в наблюдаемых полях и переводит
 * нажатия в команды сессии. Сама музыка живёт в службе, поэтому здесь нет
 * ничего, что жалко потерять при повороте экрана или закрытии активности.
 *
 * Очередь читается у плеера, а не хранится своим списком: менять её может
 * не только этот экран. Ничего при этом не теряется — всё, что мы о треке
 * знаем, лежит в метаданных `MediaItem`.
 */
@UnstableApi
class PlayerHost(private val context: Context) : Player.Listener {

    var connected by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var current by mutableStateOf<Track?>(null); private set
    var position by mutableStateOf(0L); private set
    var duration by mutableStateOf(0L); private set
    var shuffle by mutableStateOf(false); private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF); private set
    var queue by mutableStateOf<List<Track>>(emptyList()); private set

    /** Последняя неудача проигрывания. Обнуляется при новом запуске. */
    var failure by mutableStateOf<String?>(null)

    private var controller: MediaController? = null

    // ── Жизненный цикл ───────────────────────────────────────────────────────

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(this)
            connected = true
            syncFromPlayer(c)
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(this)
        controller?.release()
        controller = null
        connected = false
    }

    // ── Команды ──────────────────────────────────────────────────────────────

    /** Ставит список очередью и играет с выбранного места. */
    fun play(tracks: List<Track>, index: Int = 0) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        failure = null
        queue = tracks
        // Своя очередь — уже не радио: прореживать по дизлайкам нечего
        RadioJob.forgetSeed()
        // Новая очередь — хороший повод проверить, не вышел ли свежий yt-dlp.
        // В сеть это ходит не чаще раза в несколько часов и игре не мешает
        Engine.refreshIfDue(context)
        c.setMediaItems(tracks.map { it.toMediaItem() }, index.coerceIn(tracks.indices), 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            return
        }
        // После ошибки плеер стоит в состоянии «не готов», и одно play() его
        // не будит: кнопка нажималась, а музыка не шла. Поднимаем явно
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun next() = controller?.seekToNextMediaItem()

    /**
     * «Назад» так, как это делают все плееры: первое нажатие возвращает
     * текущий трек к началу, и только если он едва начался — переключает
     * на предыдущий.
     *
     * Это `seekToPrevious`, а не `seekToPreviousMediaItem`: у первого
     * внутри как раз этот порог, `maxSeekToPreviousPosition`, и по
     * умолчанию он три секунды — столько же, сколько у Spotify.
     */
    fun previous() = controller?.seekToPrevious()

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        position = ms
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        shuffle = c.shuffleModeEnabled
    }

    /** Порядок обхода: выключено → вся очередь → один трек. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        repeatMode = c.repeatMode
    }

    fun pause() = controller?.pause()

    /** Поставить трек сразу за играющим. Ничего не играет — заиграет он. */
    fun playNext(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(track), 0)
        // Уже стоит в очереди — переставляем, а не дублируем
        val at = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == track.id }
        val cur = c.currentMediaItemIndex
        if (at == cur) return
        if (at != null) c.moveMediaItem(at, if (at < cur) cur else cur + 1)
        else c.addMediaItem(cur + 1, track.toMediaItem())
    }

    val hasNext: Boolean get() = controller?.hasNextMediaItem() == true

    /** Отметка очереди — чтобы «Отменить» у дизлайка вернул всё как было. */
    class Mark(val items: List<Track>, val index: Int, val position: Long, val playing: Boolean)

    fun mark(): Mark? {
        val c = controller ?: return null
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toTrack() }
        return Mark(items, c.currentMediaItemIndex, c.currentPosition.coerceAtLeast(0L), c.isPlaying)
    }

    /** Вернуть очередь к отметке: тот трек заиграет с того же места. */
    fun rewind(m: Mark) {
        val c = controller ?: return
        if (m.items.isEmpty()) return
        c.setMediaItems(m.items.map { it.toMediaItem() }, m.index.coerceIn(m.items.indices), m.position)
        c.prepare()
        if (m.playing) c.play()
    }

    /** Отпечаток очереди — понять, не трогали ли её с тех пор. */
    fun queueKey(): String = queue.joinToString(",") { it.id } + "@" + current?.id

    /** Что ещё сыграет после текущего, по порядку очереди. */
    fun upcoming(): List<Track> {
        val c = controller ?: return emptyList()
        val cur = c.currentMediaItemIndex
        return (cur + 1 until c.mediaItemCount).map { c.getMediaItemAt(it).toTrack() }
    }

    /**
     * Убирает из очереди треки, на которых [test] скажет да. Играющий
     * остаётся на месте.
     *
     * @return сколько убрано
     */
    fun drop(test: (Track) -> Boolean): Int {
        val c = controller ?: return 0
        val cur = c.currentMediaItemIndex
        var removed = 0
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (i == cur) continue
            if (test(c.getMediaItemAt(i).toTrack())) {
                c.removeMediaItem(i)
                removed++
            }
        }
        return removed
    }

    /** Дозвон позиции: плеер о ней не уведомляет, её надо спрашивать. */
    fun refreshPosition() {
        val c = controller ?: return
        position = c.currentPosition.coerceAtLeast(0L)
        duration = c.duration.takeIf { it > 0 } ?: 0L
    }

    // ── Уведомления от плеера ────────────────────────────────────────────────

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        // Заиграло — значит, прошлая неудача уже неправда. Служба чинит
        // ошибки сама ([Recovery]), и надпись о них не должна её пережить
        if (isPlaying) failure = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        current = queue.firstOrNull { it.id == mediaItem?.mediaId } ?: mediaItem?.toTrack()
        // История пишется здесь, а не при нажатии: так в неё попадает и то,
        // что доиграло само по очереди, а не только выбранное руками
        current?.let { Settings.rememberPlayed(it) }
        refreshPosition()
    }

    /**
     * Очередь переписали. Перечитываем её у плеера, а не держим свой список:
     * менять её может не только этот экран — радио досыпает похожих
     * из фона, своим подключением, и о нём мы узнаём только отсюда.
     */
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val c = controller ?: return
        queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toTrack() }
    }

    override fun onPlaybackStateChanged(state: Int) {
        buffering = state == Player.STATE_BUFFERING
        if (state == Player.STATE_READY) refreshPosition()
    }

    /**
     * Неудачи добычи потока приходят сюда — их важно показать, а не
     * проглотить. Чинит их не экран, а служба ([Recovery]): она жива и при
     * погашенном дисплее. Здесь только текст.
     */
    override fun onPlayerError(error: PlaybackException) {
        failure = Diag.chain(error, 300)
    }

    private fun syncFromPlayer(c: MediaController) {
        // Пока экран был погашен, очередь могли переписать без нас
        queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toTrack() }
        playing = c.isPlaying
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        current = queue.firstOrNull { it.id == c.currentMediaItem?.mediaId }
            ?: c.currentMediaItem?.toTrack()
        refreshPosition()
    }
}

/**
 * Ставит новую очередь вокруг того, что играет, не прерывая его.
 *
 * Нужно для радио по текущему треку. `setMediaItems` здесь не годится:
 * он заново готовит источник, и трек начинается с нуля — даже когда он же
 * стоит в новой очереди первым. Здесь играющий элемент не трогается вообще,
 * меняется только то, что вокруг него.
 *
 * Живёт отдельно от [PlayerHost] нарочно: тем же способом досыпает похожих
 * фоновый [RadioJob], а у него своё, недолгое подключение к плееру.
 *
 * @return false, если играть нечего
 */
@UnstableApi
fun requeueAround(player: Player, tracks: List<Track>): Boolean {
    val playingId = player.currentMediaItem?.mediaId ?: return false
    val index = player.currentMediaItemIndex
    val rest = tracks.filterNot { it.id == playingId }

    // Сначала хвост, потом голова: удаление хвоста не сдвигает индексы,
    // а удаление головы сдвигает текущий элемент в начало очереди
    if (player.mediaItemCount > index + 1) player.removeMediaItems(index + 1, player.mediaItemCount)
    if (index > 0) player.removeMediaItems(0, index)

    player.addMediaItems(rest.map { it.toMediaItem() })
    return true
}

/** Время в виде «3:07». Для отрицательных и неизвестных — прочерк. */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
