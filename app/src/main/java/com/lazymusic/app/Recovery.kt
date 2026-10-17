package com.lazymusic.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * Следит за сетью и поднимает плеер после ошибки.
 *
 * Живёт в службе, а не в активности, по той же причине, что и [Prefetcher]:
 * музыку слушают с погашенным экраном, и чинить её должен тот, кто в это
 * время не спит. Раньше ошибку ловил только экран — он показывал текст и
 * больше ничего не делал; при погашенном экране музыка просто молча
 * вставала.
 *
 * ## Сеть
 *
 * Ссылка на звук привязана к адресу телефона (см. [StreamUrls]). Поэтому
 * при любой смене сети — Wi-Fi на мобильную, мобильная на Wi-Fi, новый
 * адрес у той же сети — весь запас ссылок выбрасывается и добывается
 * заново, не дожидаясь отказа.
 *
 * ## Ошибки плеера
 *
 * До плеера доходит только то, что не погасил [YtDataSource]. Дальше
 * три случая, и ведут они себя по-разному:
 *
 * - **ролика нет** ([TrackUnavailable]) — повторять нечего, сразу дальше;
 * - **сеть** — трек не виноват, поэтому не пропускаем: пробуем с растущей
 *   паузой, а потом ждём, пока сеть не сменится или не вернётся. Иначе
 *   тоннель в метро съедал бы очередь по треку за раз;
 * - **остальное** — пара попыток и переход к следующему, но не больше
 *   [MAX_SKIPS] подряд: если не открывается ничего, беда общая, и молча
 *   перелистать всю очередь было бы хуже, чем остановиться.
 */
class Recovery(
    context: Context,
    private val player: Player,
    /** Запас ссылок выброшен — пусть [Prefetcher] соберёт его заново. */
    private val onLinksDropped: () -> Unit
) : Player.Listener {

    private companion object {
        /** Сколько раз поднимаем один трек, прежде чем его пропустить. */
        const val RETRIES = 2
        const val RETRY_DELAY_MS = 1_500L

        /** Сколько раз и с какой наибольшей паузой ждём плохую сеть. */
        const val NET_RETRIES = 5
        const val NET_DELAY_MS = 2_000L
        const val NET_DELAY_MAX_MS = 30_000L

        /** Больше стольких треков подряд не пропускаем. */
        const val MAX_SKIPS = 3

        /** Столько трек должен проиграть, чтобы счётчики неудач обнулились. */
        const val SETTLED_MS = 15_000L

        /** Коды ExoPlayer: не дозвонились и не дождались ответа. */
        const val NET_FAILED = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        const val NET_TIMEOUT = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

        /**
         * Слова, по которым сетевую беду видно в тексте. Нужны потому, что
         * yt-dlp — отдельный процесс: его «нет сети» приходит обычной
         * строкой, а не сетевым кодом плеера.
         */
        val NET_WORDS = listOf(
            "urlopen error", "timed out", "name resolution", "no address associated",
            "network is unreachable", "connection reset", "connection refused",
            "unable to resolve host", "failed to connect", "software caused connection abort"
        )
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    // Сеть. Адреса держим только в памяти и только чтобы заметить смену —
    // в журнал они не попадают
    private var network: Network? = null
    // «Есть», пока не сказали обратного: если подписаться на сеть не выйдет,
    // ошибки должны лечиться обычным порядком, а не вечно ждать сеть
    private var online = true
    private var named: Network? = null
    private var addresses: Set<String> = emptySet()

    // Неудачи
    private var failedId: String? = null
    private var attempts = 0
    private var skips = 0

    private val settled = Runnable {
        failedId = null
        attempts = 0
        skips = 0
    }

    private val retry = Runnable {
        if (player.playerError != null && player.playWhenReady) {
            Diag.i("поднимаю плеер заново")
            player.prepare()
        }
    }

    // ── Жизненный цикл ───────────────────────────────────────────────────────

    fun start() {
        // Вызовы просим на главный поток: оттуда можно трогать плеер
        runCatching { connectivity?.registerDefaultNetworkCallback(watcher, main) }
            .onFailure { Diag.w("за сетью следить не вышло: ${it.message}") }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(watcher) }
        main.removeCallbacksAndMessages(null)
    }

    // ── Сеть ─────────────────────────────────────────────────────────────────

    private val watcher = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(fresh: Network) {
            // Первый вызов приходит сразу при подписке — это не смена
            val changed = network != null && (fresh != network || !online)
            network = fresh
            online = true
            addresses = emptySet()
            if (changed) dropLinks("сеть сменилась")
            // Плеер мог стоять и ждать именно этого
            main.removeCallbacks(retry)
            retry.run()
        }

        override fun onLost(lost: Network) {
            if (lost != network) return
            online = false
            Diag.i("сеть пропала")
        }

        override fun onCapabilitiesChanged(seen: Network, caps: NetworkCapabilities) {
            if (seen != network || seen == named) return
            named = seen
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "прочая"
            }
            Diag.i("сеть: $kind")
        }

        /**
         * Сеть та же, а адрес другой: оператор переподключил или к IPv4
         * добавился IPv6. Для ссылок это та же смена сети.
         */
        override fun onLinkPropertiesChanged(seen: Network, link: LinkProperties) {
            if (seen != network) return
            val now = link.linkAddresses
                .map { it.address }
                .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .toSet()
            val was = addresses
            addresses = now
            if (was.isNotEmpty() && now != was) dropLinks("у телефона сменился адрес")
        }
    }

    private fun dropLinks(reason: String) {
        StreamUrls.clear(reason)
        onLinksDropped()
    }

    // ── Ошибки плеера ────────────────────────────────────────────────────────

    override fun onPlayerError(error: PlaybackException) {
        main.removeCallbacks(settled)
        main.removeCallbacks(retry)
        val id = player.currentMediaItem?.mediaId
        val text = Diag.chain(error)
        Diag.w("плеер встал на ${id ?: "?"}: ${error.errorCodeName} — $text")

        // Что бы ни случилось, припасённой ссылке на этот трек больше не верим
        id?.let { StreamUrls.forget(it) }
        // Человек сам поставил на паузу — будить плеер за него не надо
        if (!player.playWhenReady) return

        if (id != failedId) {
            failedId = id
            attempts = 0
        }
        attempts++

        val gone = generateSequence<Throwable>(error) { it.cause }.any { it is TrackUnavailable }
        val net = !gone && (!online || error.errorCode == NET_FAILED ||
            error.errorCode == NET_TIMEOUT || NET_WORDS.any { text.contains(it, ignoreCase = true) })

        when {
            net && !online -> Diag.i("сети нет — жду, когда вернётся")
            net && attempts <= NET_RETRIES -> {
                val pause = (NET_DELAY_MS shl (attempts - 1)).coerceAtMost(NET_DELAY_MAX_MS)
                Diag.i("похоже на сеть — попытка $attempts через ${pause / 1000} с")
                main.postDelayed(retry, pause)
            }
            net -> Diag.w("сеть так и не наладилась — жду, пока она сменится")
            !gone && attempts <= RETRIES -> main.postDelayed(retry, RETRY_DELAY_MS * attempts)
            skips < MAX_SKIPS && player.hasNextMediaItem() -> {
                skips++
                Diag.w("трек ${id ?: "?"} не открылся — перехожу к следующему")
                player.seekToNextMediaItem()
                player.prepare()
            }
            else -> Diag.w("не открывается ничего — останавливаюсь")
        }
    }

    /** Заиграло и держится — значит, прошлые неудачи можно забыть. */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        main.removeCallbacks(settled)
        if (isPlaying) main.postDelayed(settled, SETTLED_MS)
    }
}
