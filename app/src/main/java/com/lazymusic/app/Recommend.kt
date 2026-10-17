package com.lazymusic.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Может понравиться» — подборка по тому, что человек слушал.
 *
 * Устроена как радио, только затравка не одна, а несколько: берутся
 * последние исполнители из истории, у каждого спрашиваются соседи по вкусу,
 * найденное перемешивается и ищется на YouTube.
 *
 * **Чем отличается от радио.** Радио — это «ещё вот такого же, прямо
 * сейчас»; здесь наоборот — то, чего человек ещё не слышал. Поэтому всё,
 * что уже есть в истории, из подборки выбрасывается: и сами ролики, и
 * исполнители целиком. Иначе получился бы пересказ «Недавнего», только
 * другими словами. На одного исполнителя берётся один трек — это витрина,
 * а не очередь.
 *
 * **Почему не пересобирается каждый раз.** Сбор стоит десяток запросов
 * к каталогу и один заход yt-dlp, то есть полминуты. Поэтому подборка
 * лежит в настройках и обновляется, только когда протухла по сроку или
 * когда сменился вкус — набор исполнителей, по которым её собирали.
 */
@UnstableApi
object Recommend {

    private const val TAG = "LazyMusic"

    /** Сколько исполнителей из истории берём за отправную точку. */
    private const val SEEDS = 4

    /** Сколько треков показываем. Больше витрина всё равно не вмещает. */
    private const val WANT = 12

    /** Сколько подсказок просим у каталога на каждого исполнителя. */
    private const val PER_SEED = 6

    /** Через сколько подборка считается несвежей. */
    private const val STALE_MS = 12L * 60 * 60 * 1000

    var tracks by mutableStateOf<List<Track>>(emptyList()); private set
    var running by mutableStateOf(false); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** Достаёт собранное раньше. Зовётся один раз при запуске приложения. */
    fun load() {
        tracks = Settings.picks()
    }

    /**
     * Пересобрать, если пора. Зовётся при открытии «Главной».
     *
     * Пока идёт радио — не лезем: оба пользуются yt-dlp, а музыка важнее
     * витрины.
     */
    fun refreshIfStale(context: Context) {
        if (running || RadioJob.running) return
        val seed = signature(Settings.recentTracks())
        if (seed.isEmpty()) return
        if (tracks.isNotEmpty() && seed == Settings.picksSeed() && Settings.picksAge() < STALE_MS) return
        refresh(context)
    }

    /** Пересобрать прямо сейчас — по кнопке. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (running) return
        job?.cancel()
        job = scope.launch {
            running = true
            try {
                val history = Settings.recentTracks()
                val seed = signature(history)
                if (seed.isEmpty()) return@launch

                val found = collect(history)
                if (found.isEmpty()) {
                    Log.i(TAG, "подборка не собралась: каталог ничего не предложил")
                    return@launch
                }
                // Заход yt-dlp мог оборваться на полпути — тогда в витрине
                // окажется три карточки вместо двенадцати. Хуже прежней
                // подборку не делаем: пусть лучше полежит старая
                if (found.size < WANT / 2 && found.size < tracks.size) {
                    Log.w(TAG, "нашлось ${found.size}, прежняя подборка полнее — оставляю её")
                    return@launch
                }
                tracks = found
                Settings.savePicks(found, seed)
                Log.i(TAG, "подборка: ${found.size} треков")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Log.w(TAG, "подборка не собралась", t)
            } finally {
                running = false
            }
        }
    }

    // ── Внутреннее ───────────────────────────────────────────────────────────

    /**
     * Отпечаток вкуса: имена исполнителей-затравок по порядку. Сменился —
     * значит человек слушает уже другое, и подборку пора пересобрать.
     */
    private fun signature(history: List<Track>) =
        seeds(history).joinToString("|") { flat(it.artist) }

    /** Последние исполнители истории, по одному разу, свежие — первыми. */
    private fun seeds(history: List<Track>) = history.asSequence()
        .filter { it.artist.isNotBlank() }
        .distinctBy { flat(it.artist) }
        .take(SEEDS)
        .toList()

    private suspend fun collect(history: List<Track>): List<Track> {
        val heardIds = history.mapTo(HashSet()) { it.id }
        val heardArtists = history.mapNotNullTo(HashSet()) { flat(it.artist).ifEmpty { null } }

        val picked = LinkedHashMap<String, Deezer.Suggestion>()
        val fallback = LinkedHashMap<String, Deezer.Suggestion>()

        for (seed in seeds(history)) {
            val suggestions = withContext(Dispatchers.IO) {
                runCatching { Deezer.similar(seed.artist, seed.title, PER_SEED) }
                    .onFailure { Log.w(TAG, "каталог не знает ${seed.artist}: ${it.message}") }
                    .getOrDefault(emptyList())
            }
            for (item in suggestions) {
                val artist = flat(item.artist)
                if (artist.isEmpty()) continue
                // Одного исполнителя — один трек: это витрина, а не очередь
                if (artist in picked || artist in fallback) continue
                if (artist in heardArtists) fallback[artist] = item else picked[artist] = item
            }
        }

        // Знакомых добираем, только если незнакомых не набралось: пустая
        // витрина хуже витрины с уже слышанным исполнителем
        val wanted = (picked.values + fallback.values).take(WANT)
        if (wanted.isEmpty()) return emptyList()

        Log.i(TAG, "подсказки каталога: ${picked.size} незнакомых, ${fallback.size} уже слышанных, беру ${wanted.size}")
        // Дизлайкнутое — вон, похожее на него — реже
        return Taste.screen(Radio.resolve(wanted).filterNot { it.id in heardIds }.distinctBy { it.id })
    }

    private fun flat(value: String) =
        artistOf(value).lowercase().filter { it.isLetterOrDigit() }
}
