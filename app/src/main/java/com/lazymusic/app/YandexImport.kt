package com.lazymusic.app

import android.content.Context
import com.lazymusic.app.ImportMatch.Item
import com.lazymusic.app.ImportMatch.State
import android.util.AtomicFile
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Импорт чужих списков: Яндекс Музыка → поиск на YouTube → свой плейлист.
 * Тот же порядок, что `imports.js` на ПК.
 *
 * **В два шага.** Список из Яндекса берётся сразу и сохраняется, а поиск на
 * YouTube идёт, когда тот доступен. В России Яндекс пускает только с
 * российского адреса, а YouTube часто только через VPN: при VPN «на всё»
 * первый шаг не пройдёт, без VPN — второй. Так импорт работает при любой
 * настройке, и прерванный продолжается после перезапуска с того же места.
 *
 * **Как ищем.** Раздел «Песни» YouTube Music: там студийные записи, а не
 * клипы с вступлениями. Но «Песни» отвечают всегда, даже на бессмыслицу, —
 * поэтому находка сверяется с названием. Не сошлось — вторая попытка
 * обычным поиском, со сверкой названия и исполнителя. Не сошлось и там —
 * трек в списке «не нашлось».
 *
 * Работа идёт в своём [scope] на весь процесс, как у [RadioJob]: свернуть
 * приложение посреди импорта можно. Убитый системой процесс доделает
 * импорт при следующем запуске ([restore]).
 */
object YandexImport {

    private const val TAG = "LazyMusic"
    private const val FILE = "imports.json"

    /** Запросов на один заход yt-dlp: запуск Python стоит секунды, оптом дешевле. */
    private const val BATCH = 8

    /**
     * Заходов разом. На ПК три; телефону хватает двух — каждый заход это
     * свой Python, а рядом может добываться звук для играющего трека.
     */
    private const val LANES = 2

    /** YouTube не отвечает — пробуем снова через столько (или сразу, вернувшись в приложение). */
    private const val RETRY_MS = 60_000L

    class Task(
        val id: String,
        val title: String,
        val name: String,
        val at: Long,
        val items: List<Item>,
        var done: Long = 0L
    ) {
        val waiting get() = items.any { it.state == State.WAIT }
        fun count(s: State) = items.count { it.state == s }
    }

    /** Основа адреса Яндекса для проверки с образцами — только в отладочной сборке. */
    @Volatile
    var debugApi: String? = null

    /** Растёт, когда в плейлист легли находки, — экран перечитывает списки. */
    var changes by mutableIntStateOf(0)
        private set

    /** Открытое окошко «не нашлось». */
    var missing by mutableStateOf<Task?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = mutableListOf<Task>()
    private var runner: kotlinx.coroutines.Job? = null
    private var wake = CompletableDeferred<Unit>()
    private var plaque: Plaque? = null
    private var hidden = false
    private lateinit var file: File

    // ── Задания ──────────────────────────────────────────────────────────────

    /** После запуска приложения: доделать прерванное. */
    fun restore(context: Context) {
        file = File(context.filesDir, FILE)
        tasks.clear()
        tasks += runCatching { load(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }
            .getOrElse { if (file.exists()) Log.w(TAG, "импорт: сохранённое не прочлось", it); emptyList() }
        if (tasks.any { it.done == 0L && it.waiting }) run()
    }

    /** Вернулись в приложение — возможно, включив VPN: не ждать минуту. */
    fun nudge() {
        wake.complete(Unit)
    }

    /**
     * Ссылка из строки «Ссылка на…». true — это Яндекс, импорт начат или
     * объяснено, почему нет; false — не наша ссылка.
     */
    fun start(text: String): Boolean {
        val link = Yandex.link(text) ?: return false
        scope.launch {
            val p = Plaques.notice("Беру список из Яндекс Музыки…")
            val list = try {
                withContext(Dispatchers.IO) { Yandex.fetch(link) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "импорт: список не взят: ${t.message}")
                val region = (t as? Yandex.Failure)?.why == Yandex.Why.REGION
                p.text = t.message ?: t::class.simpleName.orEmpty()
                p.sub = if (region) "Яндекс пускает только с адресов СНГ. Если включён VPN — выключите его на минуту, пока составляется список" else ""
                p.actions = listOf(Plaque.Action("Закрыть") { p.close() })
                return@launch
            }
            p.close()
            if (list.tracks.isEmpty()) {
                Plaques.toast("В этом плейлисте нет треков")
                return@launch
            }
            val name = freeName(list.title)
            Settings.createPlaylist(name)
            tasks += Task(
                id = System.currentTimeMillis().toString(36),
                title = list.title,
                name = name,
                at = System.currentTimeMillis(),
                items = list.tracks.map { Item(it.artist, it.title, it.version, it.ms) }
            )
            changes++
            save()
            Log.i(TAG, "импорт «${list.title}»: ${list.tracks.size} треков → «$name»")
            hidden = false
            run()
        }
        return true
    }

    /** Свободное имя плейлиста: «Мне нравится» уже есть — «Мне нравится (Яндекс)». */
    private fun freeName(title: String): String {
        val taken = Settings.playlistNames().mapTo(HashSet()) { it.trim().lowercase() }
        val base = title.trim().ifBlank { "Яндекс Музыка" }
        if (base.lowercase() !in taken) return base
        var n = 1
        while (true) {
            val name = if (n == 1) "$base (Яндекс)" else "$base (Яндекс $n)"
            if (name.lowercase() !in taken) return name
            n++
        }
    }

    /** Найденное — в плейлист, на те же места, что в Яндексе. */
    private fun addFound(task: Task) {
        val have = Settings.playlist(task.name).mapTo(HashSet()) { it.id }
        val placed = mutableListOf<Pair<Track, Int>>()
        var at = 0
        for (item in task.items) {
            if (item.added == true) { at++; continue }
            if (item.state != State.OK || item.added != null) continue
            val id = item.yt ?: continue
            // false — уже был в списке (тот же ролик для двух строк Яндекса)
            item.added = have.add(id)
            if (item.added != true) continue
            placed += Track(id, item.ytTitle ?: item.title, item.artist, Radio.thumbnailUrl(id)) to at
            at++
        }
        if (placed.isEmpty()) return
        Settings.insertManyIntoPlaylist(task.name, placed)
        changes++
    }

    private fun run() {
        if (runner?.isActive == true) return
        runner = scope.launch {
            var i = 0
            while (i < tasks.size) {
                val task = tasks[i++]
                if (task.done != 0L) continue
                // «YouTube не отвечает» держится, пока не пройдёт удачный заход, —
                // и на время следующей попытки тоже
                var stalled = false
                // Первый заход и заход после неудачи — одним треком: жив ли
                // YouTube, видно за секунды, а пачка из восьми поисков без сети
                // висела бы минуты. Ответил — дальше полными пачками
                var single = true
                while (task.waiting) {
                    // Плейлист удалили посреди импорта — значит, не нужен
                    if (task.name !in Settings.playlistNames()) {
                        Log.i(TAG, "импорт «${task.title}»: плейлист удалён, бросаю")
                        task.done = System.currentTimeMillis()
                        plaque?.close(); plaque = null
                        save()
                        break
                    }
                    show(task, stalled)
                    val todo = task.items.filter { it.state == State.WAIT }
                    val lanes = if (single) listOf(todo.take(1)) else todo.chunked(BATCH).take(LANES)
                    val done = coroutineScope {
                        lanes.map { batch ->
                            async {
                                try {
                                    ImportMatch.matchBatch(batch); true
                                } catch (c: CancellationException) {
                                    throw c
                                } catch (t: Throwable) {
                                    Log.w(TAG, "импорт: заход не удался: ${t.message}")
                                    false
                                }
                            }
                        }.awaitAll()
                    }
                    if (done.none { it }) {
                        // YouTube не ответил ни на один заход — ждём его, список уже сохранён
                        stalled = true; single = true
                        show(task, true)
                        pause()
                        continue
                    }
                    stalled = false; single = false
                    addFound(task)
                    save()
                }
                if (task.done == 0L) {
                    task.done = System.currentTimeMillis()
                    save()
                    Log.i(TAG, "импорт «${task.title}»: найдено ${task.count(State.OK)}, не нашлось ${task.count(State.MISS)}")
                    finish(task)
                }
            }
        }
    }

    private suspend fun pause() {
        if (wake.isCompleted) wake = CompletableDeferred()
        withTimeoutOrNull(RETRY_MS) { wake.await() }
    }

    // ── Хранение ─────────────────────────────────────────────────────────────

    private suspend fun save() {
        if (!::file.isInitialized) return
        val text = dump(tasks)
        withContext(Dispatchers.IO) {
            val af = AtomicFile(file)
            val out = af.startWrite()
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                af.finishWrite(out)
            } catch (t: Throwable) {
                af.failWrite(out)
                Log.w(TAG, "импорт: не сохранился", t)
            }
        }
    }

    fun dump(list: List<Task>): String = JSONArray().apply {
        for (t in list) put(JSONObject().apply {
            put("id", t.id); put("source", "yandex"); put("title", t.title); put("name", t.name)
            put("at", t.at); put("done", t.done)
            put("items", JSONArray().apply {
                for (i in t.items) put(JSONObject().apply {
                    put("artist", i.artist); put("title", i.title); put("version", i.version); put("ms", i.ms)
                    put("state", i.state.name.lowercase()); put("tries", i.tries)
                    i.yt?.let { put("yt", it) }
                    i.ytTitle?.let { put("ytTitle", it) }
                    if (i.video) put("video", true)
                    i.added?.let { put("added", it) }
                })
            })
        })
    }.toString()

    fun load(text: String): List<Task> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val items = o.optJSONArray("items") ?: JSONArray()
            Task(
                id = o.optString("id"),
                title = o.optString("title"),
                name = o.optString("name"),
                at = o.optLong("at"),
                done = o.optLong("done"),
                items = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { i ->
                    Item(
                        artist = i.optString("artist"),
                        title = i.optString("title"),
                        version = i.optString("version"),
                        ms = i.optLong("ms"),
                        state = runCatching { State.valueOf(i.optString("state").uppercase()) }.getOrDefault(State.WAIT),
                        tries = i.optInt("tries"),
                        yt = i.optString("yt").ifEmpty { null },
                        ytTitle = i.optString("ytTitle").ifEmpty { null },
                        video = i.optBoolean("video"),
                        added = if (i.has("added")) i.optBoolean("added") else null
                    )
                }
            )
        }
    }

    // ── Плашка ───────────────────────────────────────────────────────────────

    private fun show(task: Task, stalled: Boolean) {
        if (hidden) return
        val total = task.items.size
        val settled = total - task.count(State.WAIT)
        val text = "Импорт «${task.title}»: $settled из $total"
        val sub = if (stalled) "YouTube не отвечает. Список сохранён — продолжу сам, когда он станет доступен"
        else "Найдено ${task.count(State.OK)}, плейлист «${task.name}»"
        val p = plaque?.takeIf { it in Plaques.items } ?: Plaques.notice(text).also { plaque = it }
        p.text = text
        p.sub = sub
        p.actions = listOf(Plaque.Action("Скрыть", quiet = true) { hidden = true; plaque?.close(); plaque = null })
        p.progress = if (total > 0) settled.toFloat() / total else 0f
    }

    private fun finish(task: Task) {
        plaque?.close()
        plaque = null
        val ok = task.count(State.OK)
        val miss = task.count(State.MISS)
        lateinit var p: Plaque
        val actions = mutableListOf(Plaque.Action("Закрыть", quiet = miss > 0) { p.close() })
        if (miss > 0) actions += Plaque.Action("Что не нашлось") { p.close(); missing = task }
        p = Plaques.notice(
            "Импорт «${task.title}» готов",
            "В плейлисте «${task.name}» ${trackCount(ok)}" + (if (miss > 0) ", не нашлось $miss" else ""),
            actions
        )
    }
}

/** Список ненайденного: исполнитель и название, их можно выделить и скопировать. */
@Composable
fun ImportMissingDialog() {
    val task = YandexImport.missing ?: return
    val lost = task.items.filter { it.state == State.MISS }
    val close = { YandexImport.missing = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Не нашлось на YouTube: ${lost.size}") },
        text = {
            Column {
                Text(
                    "Из «${task.title}». Их можно найти поиском и добавить в «${task.name}» вручную.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        lost.forEachIndexed { n, i ->
                            Text(
                                "${n + 1}. ${i.artist} — ${i.title}" + (if (i.version.isNotEmpty()) " (${i.version})" else ""),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Закрыть") } }
    )
}
