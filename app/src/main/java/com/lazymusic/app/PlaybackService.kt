package com.lazymusic.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Проигрывание живёт здесь, а не в активности.
 *
 * Ради этого и выбирался самостоятельный плеер: служба переживает сворачивание
 * и погашенный экран, а уведомление с кнопками система строит сама по
 * [MediaSession]. Активность — лишь один из возможных пультов к этой службе.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private companion object {
        /** Сколько звука следующего трека скачиваем заранее. */
        const val PRELOAD_US = 20_000_000L

        /** Сколько ждём соединения и ответа. Столько же ждал штатный слой. */
        const val NET_TIMEOUT_S = 8L

        /** Своя команда сессии: сердце в уведомлении. */
        const val ACTION_LIKE = "com.lazymusic.app.LIKE"
    }

    private var session: MediaSession? = null
    private var recovery: Recovery? = null

    private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        Engine.start(this)

        // По сети плеер ходит через OkHttp, а не штатным слоем Android:
        // только так можно сказать «сначала IPv4» — см. Ipv4First, там же
        // замер, из-за которого это понадобилось
        val http = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .dns(Ipv4First)
                // Простаивающие соединения не держим дольше 10 с. Замерено
                // 2026-10-17: трек скачивается целиком за секунду, соединение
                // минуты простаивает, и мобильный оператор молча его
                // забывает. Следующий трек шёл в него, ответа не было, и плеер
                // ждал свой таймаут — 12 с до звука вместо 4. Новое соединение
                // с шифрованием стоит 70–100 мс
                .connectionPool(ConnectionPool(4, 10, TimeUnit.SECONDS))
                .eventListenerFactory(NetTrace.factory)
                .connectTimeout(NET_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(NET_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        )

        // Источник данных: сначала ytm:<id> превращается в файл или ссылку,
        // дальше DefaultDataSource читает file:// сам, а https:// — через http.
        // Отказы YouTube гасятся там же — см. YtDataSource
        val sources = YtDataSource.Factory(this, DefaultDataSource.Factory(this, http))

        // Звук идёт через эквалайзер — см. Eq. Звено стоит всегда, а
        // выключенный эквалайзер просто ничего не меняет
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(Eq.Processor()))
                .build()
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources))
            .setLoadControl(loadControl())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Наушники выдернули — замолкаем, как положено плееру
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                // Начало следующего трека скачивается, пока играет текущий.
                // Двадцать секунд звука — это сотни килобайт, зато переход
                // получается без паузы на раскачку
                setPreloadConfiguration(ExoPlayer.PreloadConfiguration(PRELOAD_US))
            }

        // А ссылку для следующего трека добудут ещё раньше
        val prefetcher = Prefetcher(this, player)
        player.addListener(prefetcher)

        // Сторож: следит за сетью и поднимает плеер после ошибки. Сменилась
        // сеть — запас ссылок выброшен, и собрать его надо сразу
        recovery = Recovery(this, player) { prefetcher.refresh() }.also {
            player.addListener(it)
            it.start()
        }

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp())
            .setCallback(Remote())
            .setMediaButtonPreferences(listOf(likeButton(player)))
            .build()

        // Сердце в уведомлении следует и за треком, и за самим списком:
        // лайк могли поставить с экрана, пока уведомление висит в шторке
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshLikeButton()
        })
        // Понравившееся меняет и сводка с компьютером — из фонового потока,
        // а сессию и плеер можно трогать только из главного
        Likes.onChange = { ContextCompat.getMainExecutor(this).execute(::refreshLikeButton) }
    }

    /**
     * 👍 в уведомлении и на экране блокировки. Музыку слушают с
     * погашенным дисплеем, и ставить лайк надо уметь оттуда же — иначе
     * ради одного нажатия пришлось бы разблокировать телефон и идти
     * в приложение.
     */
    private fun likeButton(player: Player): CommandButton {
        val liked = player.currentMediaItem?.mediaId?.let { it in Likes } == true
        return CommandButton.Builder(
            if (liked) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED
        )
            .setDisplayName(if (liked) tr("Убрать из понравившегося") else tr("Нравится"))
            .setSessionCommand(likeCommand)
            .build()
    }

    private fun refreshLikeButton() {
        val current = session ?: return
        current.setMediaButtonPreferences(listOf(likeButton(current.player)))
    }

    /** Что сессия разрешает пультам и как отвечает на свою команду. */
    private inner class Remote : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            // Всё обычное остаётся как было, добавляется одна своя команда
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(likeCommand)
                        .build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_LIKE) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            session.player.currentMediaItem?.toTrack()?.let { track ->
                val liked = Likes.toggle(track)
                Diag.i("${track.id} — сердце из уведомления: ${if (liked) "поставлено" else "снято"}")
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Смахнули из недавних: играющую музыку не трогаем, молчащую службу гасим. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Likes.onChange = null
        recovery?.stop()
        recovery = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Сколько держим в запасе.
     *
     * Умолчание media3 — 50 секунд, и рассчитано оно на видео. У нас звук:
     * три минуты музыки весят примерно как двадцать секунд ролика, так что
     * запас можно держать куда больше — и тогда сеть, просевшая в метро,
     * до слуха не доходит. Заодно снижен порог начала: полторы секунды
     * достаточно, чтобы заиграть, а ждать пять было заметно.
     */
    private fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 30_000,
            /* maxBufferMs = */ 180_000,
            /* bufferForPlaybackMs = */ 1_500,
            /* bufferForPlaybackAfterRebufferMs = */ 3_000
        )
        .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
