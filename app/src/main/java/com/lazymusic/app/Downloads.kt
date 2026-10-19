package com.lazymusic.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Скачивание треков в [Cache] — чтобы играли без сети и не тратили трафик
 * на повторных прослушиваниях.
 *
 * Файл кладётся под именем `<videoId>.<ext>`, тем же, что у модуля на ПК:
 * скачанное переносится между телефоном и компьютером простым копированием.
 *
 * Своя область выполнения, а не `lifecycleScope` активности: качание длится
 * дольше, чем живёт экран, и не должно обрываться от поворота или ухода
 * в другое приложение.
 */
object Downloads {

    private const val TAG = "LazyMusic"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Идёт прямо сейчас: идентификатор → доля выполнения от 0 до 1. */
    val progress = mutableStateMapOf<String, Float>()

    /** Последняя неудача: идентификатор → текст. Сбрасывается новой попыткой. */
    val failures = mutableStateMapOf<String, String>()

    /** Растёт после каждого завершённого скачивания — сигнал перечитать кэш. */
    var version by mutableIntStateOf(0)
        private set

    fun isRunning(videoId: String) = progress.containsKey(videoId)

    fun start(context: Context, track: Track) {
        if (isRunning(track.id)) return
        val app = context.applicationContext
        scope.launch { fetch(app, track) }
    }

    /**
     * Скачать трек и дождаться. true — файл в кэше. Зовут и кнопка у трека
     * ([start]), и очередь плейлистов ([Offline]) — та по одному, иначе
     * плейлист на тысячу треков поднял бы тысячу yt-dlp разом.
     */
    suspend fun fetch(context: Context, track: Track): Boolean = withContext(Dispatchers.IO) {
        if (isRunning(track.id)) return@withContext false
        val app = context.applicationContext
        failures.remove(track.id)
        progress[track.id] = 0f
        try {
            val dir = Cache.dir(app)
            val go = { fast: Boolean, jar: File? ->
                // Скачивание сняли — быстрый путь потому и оборвался
                if (!fast && !isRunning(track.id)) throw java.io.IOException("скачивание снято")
                val request = YoutubeDLRequest(
                    "https://www.youtube.com/watch?v=${track.id}"
                ).apply {
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    // Качает yt-dlp сам, и по IPv6 до серверов со звуком он
                    // на мобильной сети не дозвонится так же, как плеер
                    addOption(Engine.IPV4)
                    // Со входом (YtLogin) — только обычный путь: visionos cookies не принимает
                    if (jar != null) addOption("--cookies", jar.absolutePath)
                    else if (fast) addOption("--extractor-args", Engine.FAST_CLIENT)
                    // Имя файла — идентификатор ролика: по нему кэш и ищется
                    addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
                }

                // Под замком движка: обновление yt-dlp не подменит файл
                // посреди скачивания, а подождёт или отложится
                Engine.extracting {
                    YoutubeDL.getInstance().execute(request, track.id) { percent, _, _ ->
                        // yt-dlp отдаёт проценты, а полоске нужна доля
                        progress[track.id] = (percent / 100f).coerceIn(0f, 1f)
                    }
                }
            }
            // YouTube требовал проверку на бота — сразу со входом; иначе быстрым
            // путём, без JS (Engine.FAST_CLIENT), не вышло — обычным
            val jar = YtLogin.jarIfNeeded(app)
            try {
                if (jar != null) go(false, jar) else Engine.fastFirst(track.id) { go(it, null) }
            } catch (t: Throwable) {
                if (t is InterruptedException || t is kotlinx.coroutines.CancellationException) throw t
                if (jar != null || !YtLogin.isBotCheck(Diag.chain(t, 4000))) throw t
                val login = YtLogin.botCheck(app) ?: run { YtLogin.ask(app); throw t }
                go(false, login)
            }

            val file = Cache.find(app, track.id)
            if (file == null) {
                failures[track.id] = "файл не появился в кэше"
                Log.e(TAG, "скачали ${track.id}, но файла нет")
                false
            } else {
                // В общий журнал — его присылают, когда «не качается»
                Diag.i("${track.id} — скачан: ${file.length() / 1024} КБ")
                true
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            failures[track.id] = Diag.cause(t, 200)
            Diag.w("${track.id} — не скачался: ${Diag.cause(t, 200)}")
            Log.e(TAG, "не скачался ${track.id}", t)
            false
        } finally {
            progress.remove(track.id)
            version++
        }
    }

    /** Снимает качание. Недокачанные куски yt-dlp убирает за собой сам. */
    fun cancel(videoId: String) {
        if (!isRunning(videoId)) return
        runCatching { YoutubeDL.getInstance().destroyProcessById(videoId) }
        progress.remove(videoId)
        version++
    }

    /** Убирает скачанный файл. Возвращает true, если что-то удалили. */
    fun remove(context: Context, videoId: String): Boolean {
        val file = Cache.find(context, videoId) ?: return false
        val gone = file.delete()
        if (gone) version++
        return gone
    }
}
