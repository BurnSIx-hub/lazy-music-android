package com.lazymusic.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Плейлисты без сети — как «Скачать» у плейлиста в Spotify или Яндекс
 * Музыке (просьба пользователя 2026-10-18).
 *
 * Отмеченный плейлист скачивается в тот же кэш, что и «Скачать» у трека
 * ([Cache]): это не файлы в «Музыке», а запас приложения, и играет он без
 * сети. Новые треки такого плейлиста — импорт, 👍, сводка с ПК —
 * докачиваются сами. Сняли отметку — докачка останавливается, скачанное
 * остаётся (убрать — «Очистить кэш» или «Убрать скачанное» у трека).
 *
 * **По одному.** yt-dlp — отдельный процесс, плейлист на тысячу треков
 * разом положил бы телефон; между треками пауза, чтобы не дёргать YouTube
 * очередью без передышки.
 *
 * **По умолчанию только по Wi-Fi**, как в Spotify: «Мне нравится» у
 * знакомой — 1200 треков, около 5 ГБ. Wi-Fi ищется среди всех сетей, а не
 * у активной: при VPN активная сеть — сама VPN, и Android честно называет
 * её платной даже поверх домашнего Wi-Fi.
 *
 * Качает, пока жив процесс (при игре музыки он жив); убили — продолжит при
 * следующем запуске ([attach]).
 */
object Offline {

    private const val TAG = "LazyMusic"

    /** Пауза между треками. */
    private const val PAUSE_MS = 1_500L

    /**
     * Попыток на трек — каждая со свежей ссылкой, как при игре. У знакомой
     * (2026-10-19) аудиосервер отвергал больше половины ссылок, игра
     * пробивалась со второй-третьей, а скачивание сдавалось с первой.
     */
    private const val TRIES = 4

    /** Пауза между попытками одного трека. */
    private const val RETRY_MS = 3_000L

    /** Столько треков подряд не скачались — передышка [BREAK_MS]. */
    private const val STREAK = 3

    /**
     * Передышка после серии неудач, потом заново — сама, без смены сети:
     * Wi-Fi дома не меняется, и знакомая включала кнопку заново раз в
     * полчаса, получая по треку. Каждая следующая вдвое длиннее, до часа.
     */
    private const val BREAK_MS = 5 * 60_000L
    private const val BREAK_MAX_MS = 60 * 60_000L

    /** Пауза после правки библиотеки: десяток 👍 подряд — один заход. */
    private const val SETTLE_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var settle: Job? = null
    private lateinit var app: Context

    /** Плейлисты без сети — наблюдаемо, для кнопки в шапке. */
    var lists by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Есть что качать, но нет Wi-Fi, а по мобильной не разрешено. */
    var waitingWifi by mutableStateOf(false)
        private set

    /** Не скачались в этот заход — повторяем после передышки или смены сети. */
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun attach(context: Context) {
        app = context.applicationContext
        lists = Settings.offlineLists
        // Сеть вернулась или сменилась — может, появился Wi-Fi
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Неудачи могли быть от пропавшей сети — пробуем их снова
                        failed.clear()
                        kick(again = true)
                    }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        if (waitingWifi) kick()
                    }
                })
        }.onFailure { Log.w(TAG, "без сети: за сетью следить не вышло: ${it.message}") }
        kick()
    }

    fun isOn(name: String) = name in lists

    fun toggle(name: String) {
        lists = if (name in lists) lists - name else lists + name
        Settings.offlineLists = lists
        Diag.i("без сети: «$name» ${if (name in lists) "включён" else "выключен"}")
        if (name in lists) {
            failed.clear()
            kick(again = true)
        }
    }

    /** Плейлисты поменялись — переименование держим, новые треки докачиваем. */
    fun libraryChanged() {
        if (!::app.isInitialized) return
        val names = Settings.playlistNames().toSet()
        val kept = lists.filterTo(mutableSetOf()) { it in names || it == Likes.LIST }
        if (kept != lists) {
            lists = kept
            Settings.offlineLists = kept
        }
        if (lists.isEmpty()) return
        settle?.cancel()
        settle = scope.launch {
            delay(SETTLE_MS)
            kick()
        }
    }

    /** Разрешено ли качать сейчас. */
    private fun allowed(): Boolean = Settings.offlineOnMobile || onWifi()

    private fun onWifi(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }.getOrDefault(false)

    /** Что докачать: треки отмеченных плейлистов, которых нет в кэше. */
    private fun wanted(): List<Track> {
        val have = Cache.dir(app).listFiles()?.mapTo(HashSet()) { it.nameWithoutExtension } ?: HashSet()
        val seen = HashSet<String>()
        val out = mutableListOf<Track>()
        for (name in lists) {
            for (t in Settings.playlist(name)) {
                if (t.id in have || t.id in failed || !seen.add(t.id)) continue
                if (Downloads.isRunning(t.id)) continue
                out += t
            }
        }
        return out
    }

    /** Один трек, до [TRIES] попыток. */
    private suspend fun fetch(track: Track): Boolean {
        repeat(TRIES) { attempt ->
            if (attempt > 0) delay(RETRY_MS)
            if (Downloads.fetch(app, track)) return true
            // Нет сети или Wi-Fi пропал — попытки не тратим
            if (!allowed()) return false
        }
        return false
    }

    /** Сколько из плейлиста уже в кэше — для сводки в журнале ([Diag.export]). */
    fun progress(context: Context): List<Pair<String, String>> {
        val have = Cache.dir(context).listFiles()?.mapTo(HashSet()) { it.nameWithoutExtension } ?: HashSet()
        return lists.map { name ->
            val tracks = Settings.playlist(name)
            name to "${tracks.count { it.id in have }} из ${tracks.size}"
        }
    }

    /** Идёт передышка после серии неудач — её можно прервать. */
    @Volatile private var resting = false

    /**
     * Докачать недостающее — если не качает уже. again — прервать
     * передышку: человек сам включил плейлист или сеть сменилась.
     */
    fun kick(again: Boolean = false) {
        if (!::app.isInitialized || lists.isEmpty()) return
        if (worker?.isActive == true) {
            if (!again || !resting) return
            worker?.cancel()
        }
        worker = scope.launch {
            var streak = 0
            var pause = BREAK_MS
            while (true) {
                val todo = wanted()
                if (todo.isEmpty()) {
                    waitingWifi = false
                    break
                }
                if (!allowed()) {
                    if (!waitingWifi) Diag.i("без сети: ждёт Wi-Fi, осталось ${todo.size}")
                    waitingWifi = true
                    break
                }
                waitingWifi = false
                val track = todo.first()
                if (fetch(track)) {
                    streak = 0
                    pause = BREAK_MS
                } else {
                    failed += track.id
                    // Подряд не качается — беда общая (сеть, YouTube); не
                    // долбим, а отдыхаем и пробуем снова, уже и упавшие
                    if (++streak >= STREAK) {
                        Diag.w("без сети: $STREAK трека подряд не скачались — пауза ${pause / 60_000} мин")
                        resting = true
                        try { delay(pause) } finally { resting = false }
                        pause = (pause * 2).coerceAtMost(BREAK_MAX_MS)
                        streak = 0
                        failed.clear()
                        continue
                    }
                }
                delay(PAUSE_MS)
            }
        }
    }
}
