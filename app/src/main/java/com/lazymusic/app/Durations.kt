package com.lazymusic.app

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Длительности треков для строк списков — как `durations.js` на ПК.
 *
 * В самом [Track] длительности нет: библиотека общая с ПК и модулем, и
 * новое поле при сводке потерялось бы. Храним отдельно, идентификатор →
 * секунды, в `files/durations.json`. Ролик не меняет длину — узнанное
 * хранится насовсем.
 *
 * Откуда: YouTube Data API `videos?part=contentDetails` — пачка до 50
 * роликов за 1 единицу квоты (поиск — 100), и сам плеер, когда трек
 * открылся. Без ключа API строки просто без длительности.
 */
object Durations {

    private val known = mutableStateMapOf<String, Int>()
    /** Не отдал длительность (эфир, удалён) — за этот запуск снова не спрашиваем. */
    private val none = mutableSetOf<String>()
    private val wanted = linkedSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var saveJob: Job? = null
    private var file: File? = null

    fun load(context: Context) {
        val f = File(context.filesDir, "durations.json").also { file = it }
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val json = JSONObject(f.readText())
                    json.keys().asSequence().associateWith { json.optInt(it) }.filterValues { it > 0 }
                }.getOrDefault(emptyMap())
            }
            read.forEach { (id, s) -> if (id !in known) known[id] = s }
        }
    }

    /** «3:45» или пусто. Читается из Compose — строка дорисуется, когда узнаем. */
    fun text(id: String): String = known[id]?.let(::clock).orEmpty()

    /** Попросить узнать длительность. Звать из LaunchedEffect строки. */
    fun want(id: String) {
        if (id.isBlank() || id in known || id in none || Podcasts.isEpisode(id)) return
        wanted += id
        if (job?.isActive != true) job = scope.launch { delay(200); fetch() }
    }

    /** Длительность от плеера — только если API её ещё не дал. */
    fun note(id: String, ms: Long) {
        if (id.isBlank() || ms <= 0 || id in known || Podcasts.isEpisode(id)) return
        scope.launch { known[id] = ((ms + 500) / 1000).toInt(); save() }
    }

    private suspend fun fetch() {
        if (!Settings.hasApiKey) { wanted.clear(); return }
        while (wanted.isNotEmpty()) {
            val ids = wanted.take(50)
            wanted.removeAll(ids.toSet())
            val got = runCatching { YouTubeApi.durations(ids) }.getOrElse {
                // Квота кончилась или нет сети — до перезапуска не дёргаем
                Diag.w("длительности: ${it.message}")
                none += ids; none += wanted; wanted.clear()
                return
            }
            for (id in ids) {
                val s = got[id] ?: 0
                if (s > 0) known[id] = s else none += id
            }
            save()
        }
    }

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1000)
            val json = JSONObject(known.toMap() as Map<*, *>).toString()
            withContext(Dispatchers.IO) { runCatching { file?.writeText(json) } }
        }
    }

    private fun clock(sec: Int): String {
        val h = sec / 3600
        val m = sec / 60 % 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(sec / 60, s)
    }
}
