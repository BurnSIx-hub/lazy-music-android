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
        failures.remove(track.id)
        progress[track.id] = 0f

        scope.launch {
            try {
                val dir = Cache.dir(app)
                // Быстрым путём, без JS, — см. Engine.FAST_CLIENT; не вышло — обычным
                // YouTube требовал проверку на бота — качаем со входом (YtLogin)
                val jar = YtLogin.jarIfNeeded(app)
                val run = { fast: Boolean ->
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
                if (jar != null) run(false) else Engine.fastFirst(track.id, run)

                val file = Cache.find(app, track.id)
                if (file == null) {
                    failures[track.id] = "файл не появился в кэше"
                    Log.e(TAG, "скачали ${track.id}, но файла нет")
                } else {
                    Log.i(TAG, "скачан ${track.id}: ${file.length() / 1024} КБ")
                }
            } catch (t: Throwable) {
                failures[track.id] = t.message?.take(200) ?: "не удалось скачать"
                Log.e(TAG, "не скачался ${track.id}", t)
            } finally {
                progress.remove(track.id)
                version++
            }
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
