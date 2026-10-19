package com.lazymusic.app

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Сбор радио — целиком в стороне от экрана.
 *
 * Так сделано после жалобы, и жалоба была справедливой: раньше сбор жил
 * в `lifecycleScope` активности и говорил с плеером через её подключение.
 * Стоило свернуть приложение или погасить экран — подключение закрывалось
 * в `onStop`, собранная лента применяться было уже некуда, и всё
 * приходилось начинать заново. А ближний круг собирается секунды, дальний —
 * десятки секунд: за это время человек успевает и переключиться, и убрать
 * телефон в карман. Именно тогда музыка и нужна больше всего.
 *
 * Теперь у сбора свой [scope] на весь процесс, а к плееру он подключается
 * сам, ненадолго и только в момент, когда есть что применить. Подключение
 * живёт секунду и закрывается — держать его всё время нельзя, иначе служба
 * не сможет остановиться, когда музыка кончится.
 *
 * Состояние наблюдаемое: экран показывает [phase] и [note], если он вообще
 * открыт, но от него ничего не зависит.
 */
@UnstableApi
object RadioJob {

    private const val TAG = "LazyMusic"

    /** Сколько сообщение висит на экране после того, как всё закончилось. */
    private const val NOTE_MS = 6_000L

    /** Ближний круг собирается секунды, дальний — десятки. */
    enum class Phase { NEAR, WIDE }

    var phase by mutableStateOf<Phase?>(null); private set
    var note by mutableStateOf<String?>(null); private set

    val running: Boolean get() = phase != null

    /**
     * Исполнитель, с которого собрано играющее радио; null — играет своя
     * очередь. По нему дизлайк прореживает радио ([Taste]) — свою очередь
     * человек собрал сам, её не трогаем.
     */
    var seedArtist: String? = null
        private set

    fun forgetSeed() {
        seedArtist = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var fading: Job? = null

    /**
     * Собрать радио вокруг трека и включить его.
     *
     * Если затравка уже играет, она не прерывается: лента выстраивается
     * вокруг неё. Радио по другому треку, наоборот, его включает — за этим
     * и жали.
     *
     * Повторный вызов отменяет предыдущий сбор: очередь одна, и досыпать
     * в неё две разные ленты бессмысленно.
     */
    fun start(context: Context, seed: Track) {
        val app = context.applicationContext
        job?.cancel()
        fading?.cancel()
        job = scope.launch {
            try {
                phase = Phase.NEAR
                note = tr("собираю радио…")

                // Дизлайкнутое — вон, похожее на него — реже
                val near = Taste.screen(Radio.near(seed), seed.artist, Taste.MIN_AHEAD)
                if (near.isEmpty()) return@launch finish(tr("YouTube не смог сформировать радио на основе этого трека"))

                val queue = Radio.mix(head = seed, lanes = listOf(near))
                val started = withPlayer(app) { player ->
                    val playing = player.currentMediaItem?.mediaId == seed.id
                    if (playing && requeueAround(player, queue)) return@withPlayer true
                    player.setMediaItems(queue.map { it.toMediaItem() }, 0, 0L)
                    player.prepare()
                    player.play()
                    true
                }
                if (started != true) return@launch finish(tr("не достучался до плеера"))
                seedArtist = seed.artist

                if (!Settings.wideRadio) return@launch finish(tr("радио: {N треков}", "N треков" to trackCount(queue.size)))

                phase = Phase.WIDE
                note = tr("радио: {N треков}, подбираю похожих…", "N треков" to trackCount(queue.size))

                val extra = runCatching { Taste.screen(Radio.wider(seed, near), seed.artist) }
                    .onFailure { Log.w(TAG, "радио не расширилось", it) }
                    .getOrDefault(emptyList())
                if (extra.isEmpty()) return@launch finish(tr("радио: {N треков}", "N треков" to trackCount(queue.size)))

                val applied = widen(app, near, extra, queue.mapTo(HashSet()) { it.id })
                finish(
                    if (applied) tr("радио пополнено: ещё {число} от других исполнителей", "число" to extra.size)
                    else tr("радио: {N треков}", "N треков" to trackCount(queue.size))
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Log.w(TAG, "радио не собралось", t)
                finish(t.message ?: tr("радио не собралось"))
            }
        }
    }

    // ── Внутреннее ───────────────────────────────────────────────────────────

    /**
     * Вплетает похожих в очередь — если человек всё ещё слушает это радио.
     *
     * За те полминуты, что собирался дальний круг, он мог уйти в свой
     * плейлист, и подменять ему очередь задним числом — последнее, чего он
     * ждёт. Проверяем по тому, что играет сейчас.
     */
    private suspend fun widen(
        context: Context,
        near: List<Track>,
        extra: List<Track>,
        ours: Set<String>
    ): Boolean = withPlayer(context) { player ->
        val now = player.currentMediaItem ?: return@withPlayer false
        if (now.mediaId !in ours) return@withPlayer false

        // Уже прозвучавшее в очередь не возвращаем
        val heard = near.indexOfFirst { it.id == now.mediaId }
        val rest = if (heard >= 0) near.drop(heard + 1) else near

        requeueAround(player, Radio.mix(head = now.toTrack(), lanes = listOf(rest, extra)))
    } == true

    /**
     * Своё подключение к плееру на время одной операции.
     *
     * Отдельное от экранного: то закрывается, как только приложение уходит
     * в фон, а нам надо дотянуться до очереди именно тогда.
     */
    private suspend fun <T> withPlayer(context: Context, block: (MediaController) -> T): T? {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = suspendCancellableCoroutine<MediaController?> { cont ->
            future.addListener(
                { if (cont.isActive) cont.resume(runCatching { future.get() }.getOrNull()) },
                ContextCompat.getMainExecutor(context)
            )
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        } ?: return null
        return try {
            block(controller)
        } catch (t: Throwable) {
            Log.w(TAG, "не вышло переписать очередь: ${t.message}")
            null
        } finally {
            controller.release()
        }
    }

    /** Убирает кружок и оставляет сообщение — ненадолго, как всплывающую подсказку. */
    private fun finish(message: String) {
        phase = null
        note = message
        fading = scope.launch {
            delay(NOTE_MS)
            note = null
        }
    }
}
