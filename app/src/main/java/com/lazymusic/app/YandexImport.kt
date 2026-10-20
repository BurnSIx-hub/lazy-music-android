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
 * Импорт чужих списков: Яндекс Музыка или Spotify → поиск на YouTube →
 * свой плейлист. Тот же порядок, что `imports.js` на ПК. Источник нужен
 * только на первом шаге — взять список ([Yandex], [Spotify]); дальше путь общий.
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
        var done: Long = 0L,
        /** По каким правилам сверки искали — [ImportMatch.RULES]. */
        var rules: Int = ImportMatch.RULES
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
        recheck()
        if (tasks.any { it.done == 0L && it.waiting }) run()
    }

    /**
     * Правила сверки поменялись с прошлого раза — ненайденное в законченных
     * импортах ищется заново. Найденное встанет на своё место в плейлисте,
     * в конце — та же плашка «Импорт готов» с новым счётом.
     */
    private fun recheck() {
        val names = Settings.playlistNames().toSet()
        for (task in tasks) {
            if (task.rules >= ImportMatch.RULES) continue
            task.rules = ImportMatch.RULES
            if (task.done == 0L || task.name !in names) continue
            val lost = task.items.filter { it.state == State.MISS }
            if (lost.isEmpty()) continue
            lost.forEach { it.state = State.WAIT; it.tries = 0 }
            task.done = 0L
            Log.i(TAG, "импорт «${task.title}»: новые правила сверки, ищу заново ${lost.size}")
        }
    }

    /** Вернулись в приложение — возможно, включив VPN: не ждать минуту. */
    fun nudge() {
        wake.complete(Unit)
    }

    /**
     * Ссылка из строки «Ссылка на…». true — это Яндекс или Spotify, импорт
     * начат или объяснено, почему нет; false — не наша ссылка.
     */
    fun start(text: String): Boolean {
        val ya = Yandex.link(text)
        val sp = if (ya == null) Spotify.link(text) else null
        if (ya == null && sp == null) return false
        scope.launch {
            val p = Plaques.notice(if (ya != null) tr("Беру список из Яндекс Музыки…") else tr("Беру список из Spotify…"))
            val list = try {
                withContext(Dispatchers.IO) { if (ya != null) Yandex.fetch(ya) else Spotify.fetch(sp!!) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "импорт: список не взят: ${t.message}")
                val region = (t as? Yandex.Failure)?.why == Yandex.Why.REGION
                p.text = t.message ?: t::class.simpleName.orEmpty()
                p.sub = if (region) tr("Яндекс пускает только с адресов СНГ. Если включён VPN — выключите его на минуту, пока составляется список") else ""
                p.actions = listOf(Plaque.Action(tr("Закрыть")) { p.close() })
                return@launch
            }
            p.close()
            if (list.tracks.isEmpty()) {
                Plaques.toast(tr("В этом плейлисте нет треков"))
                return@launch
            }
            // Встроенный плеер Spotify отдаёт не больше 100 треков — сказать сразу
            if (list.cut) Plaques.toast(tr("Spotify по ссылке отдаёт только первые {число} треков", "число" to Spotify.CAP))
            addLists(listOf(list), spotify = sp != null)
        }
        return true
    }

    /**
     * Готовые списки → плейлисты и поиск на YouTube. Ссылка даёт один список,
     * выгрузка данных Spotify — сколько отметил человек; ищутся по очереди.
     */
    fun importLists(lists: List<Yandex.Listing>, spotify: Boolean) {
        scope.launch { addLists(lists, spotify) }
    }

    private suspend fun addLists(lists: List<Yandex.Listing>, spotify: Boolean) {
        var id = System.currentTimeMillis()
        for (list in lists) {
            val name = freeName(list.title, spotify)
            Settings.createPlaylist(name)
            tasks += Task(
                id = (id++).toString(36),
                title = list.title,
                name = name,
                at = System.currentTimeMillis(),
                items = list.tracks.map { Item(it.artist, it.title, it.version, it.ms) }
            )
            Log.i(TAG, "импорт «${list.title}»: ${list.tracks.size} треков → «$name»")
        }
        changes++
        save()
        hidden = false
        run()
    }

    /** Свободное имя плейлиста: «Мне нравится» уже есть — «Мне нравится (Яндекс)», «… (Spotify)». */
    private fun freeName(title: String, spotify: Boolean): String {
        val taken = Settings.playlistNames().mapTo(HashSet()) { it.trim().lowercase() }
        val base = title.trim().ifBlank { if (spotify) "Spotify" else tr("Яндекс Музыка") }
        if (base.lowercase() !in taken) return base
        var n = 1
        while (true) {
            val name = when {
                spotify -> if (n == 1) "$base (Spotify)" else "$base (Spotify $n)"
                n == 1 -> tr("{название} (Яндекс)", "название" to base)
                else -> tr("{название} (Яндекс {число})", "название" to base, "число" to n)
            }
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
            put("at", t.at); put("done", t.done); put("rules", t.rules)
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
                // Задания до правил версии 2 этого поля не знали
                rules = o.optInt("rules", 1),
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
        val text = tr("Импорт «{название в Яндексе}»: {обработано} из {всего}", "название в Яндексе" to task.title, "обработано" to settled, "всего" to total)
        val sub = if (stalled) tr("YouTube не отвечает. Список сохранён — продолжу сам, когда он станет доступен")
        else tr("Найдено {найдено}, плейлист «{плейлист}»", "найдено" to task.count(State.OK), "плейлист" to task.name)
        val p = plaque?.takeIf { it in Plaques.items } ?: Plaques.notice(text).also { plaque = it }
        p.text = text
        p.sub = sub
        p.actions = listOf(Plaque.Action(tr("Скрыть"), quiet = true) { hidden = true; plaque?.close(); plaque = null })
        p.progress = if (total > 0) settled.toFloat() / total else 0f
    }

    private fun finish(task: Task) {
        plaque?.close()
        plaque = null
        val ok = task.count(State.OK)
        val miss = task.count(State.MISS)
        lateinit var p: Plaque
        val actions = mutableListOf(Plaque.Action(tr("Закрыть"), quiet = miss > 0) { p.close() })
        if (miss > 0) actions += Plaque.Action(tr("Что не нашлось")) { p.close(); missing = task }
        p = Plaques.notice(
            tr("Импорт «{название в Яндексе}» готов", "название в Яндексе" to task.title),
            tr("В плейлисте «{плейлист}» {N треков}", "плейлист" to task.name, "N треков" to trackCount(ok)) + (if (miss > 0) tr(", не нашлось {число}", "число" to miss) else ""),
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
        title = { Text(tr("Не нашлось на YouTube: {число}", "число" to lost.size)) },
        text = {
            Column {
                Text(
                    tr("Из «{название в Яндексе}». Их можно найти поиском и добавить в «{плейлист}» вручную.", "название в Яндексе" to task.title, "плейлист" to task.name),
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
        confirmButton = { TextButton(onClick = close) { Text(tr("Закрыть")) } }
    )
}
