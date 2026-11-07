package com.lazymusic.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * «Подкасты» — каналы YouTube, которые слушают, а не смотрят: GEO, Utopia
 * Show и подобные. Своей ленты RSS у них нет, выпуски — ролики с вкладки
 * «Видео» канала, и играют они тем же путём, что музыка.
 *
 * Отличия от музыки:
 * - выпуск продолжается с места, где его бросили, а дослушанный отмечается;
 * - своя скорость (1×–2×) и перемотка на 15 и 30 секунд;
 * - в историю, «Продолжить» и «Итоги года» выпуски не попадают.
 *
 * Место в выпуске ведёт служба проигрывания ([Tracker]): музыку слушают
 * с погашенным экраном, и экран о позиции может не узнать вовсе.
 *
 * Подписки и место в выпусках сводятся с компьютером вместе с библиотекой
 * ([Hub]); правила сводки те же, что в `podcasts.rs` на ПК, — см. [merge].
 * Скорость у каждого устройства своя.
 *
 * Файл — `podcasts.json` во внутренней папке, вид тот же, что на ПК:
 * `{ v, speed, channels: [{ id, name, url, avatar, added, checked,
 * episodes: [{ id, title, dur, added }] }], progress: { id: { p, d, done, at } },
 * removed: { id: когда отписались } }`.
 */
object Podcasts {

    private const val TAG = "LazyMusic"
    /** Каналы обновляются сами не чаще, чем раз в столько. */
    private const val STALE_MS = 6 * 60 * 60 * 1000L
    /** Последние полминуты — титры и прощание: дальше выпуск дослушан. */
    private const val TAIL_SEC = 30
    /** Продолжаем чуть раньше, чем бросили, — вспомнить, о чём шла речь. */
    private const val BACK_SEC = 3
    val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

    data class Episode(val id: String, val title: String, val dur: Int, val added: Long)

    data class Channel(
        val id: String,
        val name: String,
        val url: String,
        val avatar: String,
        val added: Long,
        val checked: Long,
        val episodes: List<Episode>
    )

    data class Progress(val p: Int, val d: Int, val done: Boolean, val at: Long)

    /** Выпуск, открытый по ссылке без подписки на канал. */
    data class Single(val chId: String, val chName: String, val chUrl: String, val e: Episode)

    /** Что вышло из ссылки: страница канала, выпуск для игры или ошибка. */
    sealed interface Opened {
        data class Page(val channelId: String) : Opened
        data class Play(val channel: Channel, val index: Int) : Opened
        data class Failed(val why: String) : Opened
    }

    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var progress by mutableStateOf<Map<String, Progress>>(emptyMap()); private set
    private var removed: Map<String, Long> = emptyMap()
    var speed by mutableStateOf(1f); private set
    /** Что сейчас обновляется: id канала, ALL или ADD; null — ничего. */
    var refreshing by mutableStateOf<String?>(null); private set

    /**
     * Каналы без подписки: открытые по ссылке и те, откуда выпуски по ссылке.
     * Список выпусков у них целиком, только если id в [guestsFull]; иначе в нём
     * лишь открытые по ссылке, а целиком он берётся, когда откроют страницу.
     */
    var guests by mutableStateOf<Map<String, Channel>>(emptyMap()); private set
    var guestsFull by mutableStateOf<Set<String>>(emptySet()); private set
    var guestLoading by mutableStateOf<String?>(null); private set
    private val guestFailed = HashSet<String>()
    /**
     * Выпуски без подписки, свежие сверху. Помнятся только на телефоне
     * (`podcast-singles.json`, последние 30), с компьютером сводится лишь
     * место в них — как на ПК.
     */
    var singles by mutableStateOf<List<Single>>(emptyList()); private set
    private const val SINGLES_MAX = 30

    const val ALL = "*"
    const val ADD = "+"

    @Volatile private var byEpisode: Map<String, Channel> = emptyMap()
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun file(context: Context) = File(context.filesDir, "podcasts.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching { loadSingles(context) }
            .onFailure { Log.w(TAG, "выпуски по ссылке не прочитались: ${it.message}") }
        val f = file(context)
        if (f.exists()) {
            runCatching { apply(JSONObject(f.readText())) }
                .onFailure { Log.w(TAG, "подкасты не прочитались: ${it.message}") }
        }
        reindex()
    }

    private fun singlesFile(context: Context) = File(context.filesDir, "podcast-singles.json")

    private fun loadSingles(context: Context) {
        val f = singlesFile(context)
        if (!f.exists()) return
        singles = JSONArray(f.readText()).objects().mapNotNull { o ->
            val e = o.optJSONObject("e") ?: return@mapNotNull null
            val chId = o.optString("chId")
            if (!chId.startsWith("UC") || e.optString("id").isBlank()) return@mapNotNull null
            Single(chId, o.optString("chName"), o.optString("chUrl"),
                Episode(e.optString("id"), e.optString("title"), e.optInt("dur"), 0))
        }
        for (s in singles) addToGuest(s)
    }

    private fun saveSingles() {
        val arr = JSONArray()
        for (s in singles) {
            arr.put(JSONObject().put("chId", s.chId).put("chName", s.chName).put("chUrl", s.chUrl)
                .put("e", JSONObject().put("id", s.e.id).put("title", s.e.title).put("dur", s.e.dur)))
        }
        runCatching { singlesFile(App.instance).writeText(arr.toString()) }
            .onFailure { Log.w(TAG, "выпуски по ссылке не записались: ${it.message}") }
    }

    private fun addToGuest(s: Single): Channel {
        val have = guests[s.chId] ?: Channel(s.chId, s.chName, s.chUrl, "", 0, 0, emptyList())
        val ch = if (have.episodes.any { it.id == s.e.id }) have else have.copy(episodes = have.episodes + s.e.copy(added = 0))
        guests = guests + (s.chId to ch)
        return ch
    }

    /**
     * Запомнить выпуск без подписки — иначе после перезапуска он заиграл бы
     * как обычный трек, без места и скорости.
     */
    private fun keepSingle(ch: Channel, e: Episode) {
        if (isSubscribed(ch.id)) return
        singles = (listOf(Single(ch.id, ch.name, ch.url, e.copy(added = 0))) + singles.filter { it.e.id != e.id }).take(SINGLES_MAX)
        saveSingles()
    }

    @Synchronized
    private fun save(context: Context = App.instance) {
        runCatching { file(context).writeText(toJson(withSpeed = true).toString()) }
            .onFailure { Log.w(TAG, "подкасты не записались: ${it.message}") }
    }

    private fun reindex() {
        val map = HashMap<String, Channel>()
        for (ch in guests.values) for (e in ch.episodes) map[e.id] = ch
        // Подписка важнее: её выпуски перекрывают гостевые
        for (ch in channels) for (e in ch.episodes) map[e.id] = ch
        byEpisode = map
    }

    fun isSubscribed(id: String) = channels.any { it.id == id }

    /** Канал по id — с подпиской или без. */
    fun channel(id: String): Channel? = channels.firstOrNull { it.id == id } ?: guests[id]

    fun isEpisode(id: String?): Boolean = id != null && id in byEpisode
    fun channelOf(id: String?): Channel? = id?.let { byEpisode[it] }

    fun episodeTrack(ch: Channel, e: Episode) =
        Track(id = e.id, title = e.title, artist = ch.name, artworkUrl = Radio.thumbnailUrl(e.id))

    fun tracks(ch: Channel) = ch.episodes.map { episodeTrack(ch, it) }

    fun isNew(e: Episode) = e.added > 0 && e.id !in progress

    /** С какого места продолжить, мс; 0 — с начала. */
    fun resumeMs(id: String): Long {
        val p = progress[id] ?: return 0
        if (p.done || p.p <= 0) return 0
        return (p.p - BACK_SEC).coerceAtLeast(0) * 1000L
    }

    // ── Вид в файле и в сводке ──────────────────────────────────────────────

    fun toJson(withSpeed: Boolean): JSONObject {
        val o = JSONObject().put("v", 1)
        if (withSpeed) o.put("speed", speed.toDouble())
        o.put("channels", JSONArray().apply {
            channels.forEach { ch ->
                put(JSONObject()
                    .put("id", ch.id).put("name", ch.name).put("url", ch.url).put("avatar", ch.avatar)
                    .put("added", ch.added).put("checked", ch.checked)
                    .put("episodes", JSONArray().apply {
                        ch.episodes.forEach { e ->
                            put(JSONObject().put("id", e.id).put("title", e.title).put("dur", e.dur).put("added", e.added))
                        }
                    }))
            }
        })
        o.put("progress", JSONObject().apply {
            progress.forEach { (id, p) ->
                put(id, JSONObject().put("p", p.p).put("d", p.d).put("done", p.done).put("at", p.at))
            }
        })
        o.put("removed", JSONObject().apply { removed.forEach { (id, t) -> put(id, t) } })
        return o
    }

    private fun parseChannel(o: JSONObject) = Channel(
        id = o.optString("id"),
        name = o.optString("name"),
        url = o.optString("url"),
        avatar = o.optString("avatar"),
        added = o.optLong("added"),
        checked = o.optLong("checked"),
        episodes = o.optJSONArray("episodes").objects().map {
            Episode(it.optString("id"), it.optString("title"), it.optInt("dur"), it.optLong("added"))
        }
    )

    private fun apply(o: JSONObject) {
        o.optDouble("speed", Double.NaN).takeIf { !it.isNaN() }?.toFloat()?.let { s -> if (s in SPEEDS) speed = s }
        channels = o.optJSONArray("channels").objects().map(::parseChannel).filter { it.id.startsWith("UC") }
        progress = o.optJSONObject("progress").entries().mapValues { (_, p) ->
            Progress(p.optInt("p"), p.optInt("d"), p.optBoolean("done"), p.optLong("at"))
        }
        removed = o.optJSONObject("removed")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap()
        reindex()
    }

    // ── Сводка ──────────────────────────────────────────────────────────────

    /** Что отдать компьютеру. Скорость — своя, её не шлём. */
    @Synchronized
    fun forSync(): JSONObject = toJson(withSpeed = false)

    /**
     * Компьютер вернул сведённое. Сводим ещё раз со своим: пока ждали ответа,
     * место в выпуске могло уйти вперёд.
     */
    @Synchronized
    fun fromHub(theirs: JSONObject) {
        val merged = merge(toJson(withSpeed = true), theirs)
        val before = toJson(withSpeed = true).toString()
        apply(merged)
        if (toJson(withSpeed = true).toString() != before) save()
    }

    /** Те же правила, что `merge` в podcasts.rs. Скорость берётся у [a]. */
    fun merge(a: JSONObject, b: JSONObject): JSONObject {
        val gone = HashMap<String, Long>()
        for (src in listOf(a, b)) {
            src.optJSONObject("removed")?.let { r ->
                r.keys().forEach { k -> gone[k] = maxOf(gone[k] ?: 0L, r.optLong(k)) }
            }
        }
        val byId = LinkedHashMap<String, JSONObject>()
        for (ch in a.optJSONArray("channels").objects() + b.optJSONArray("channels").objects()) {
            val id = ch.optString("id")
            if (!id.startsWith("UC")) continue
            val have = byId[id]
            byId[id] = if (have == null) ch else mergeChannel(have, ch)
        }
        val chans = JSONArray()
        byId.values.filter { c ->
            val g = gone[c.optString("id")] ?: 0L
            g == 0L || c.optLong("added") > g
        }.forEach { chans.put(it) }
        val prog = JSONObject()
        for (src in listOf(a, b)) {
            src.optJSONObject("progress").entries().forEach { (k, v) ->
                val have = prog.optJSONObject(k)
                if (have == null || v.optLong("at") > have.optLong("at")) prog.put(k, v)
            }
        }
        val out = JSONObject().put("v", 1).put("channels", chans).put("progress", prog)
            .put("removed", JSONObject().apply { gone.forEach { (k, t) -> put(k, t) } })
        if (a.has("speed")) out.put("speed", a.get("speed"))
        return out
    }

    private fun mergeChannel(a: JSONObject, b: JSONObject): JSONObject {
        val (base, other) = if (b.optLong("checked") > a.optLong("checked")) b to a else a to b
        val theirs = other.optJSONArray("episodes").objects().associate { it.optString("id") to it.optLong("added") }
        val eps = JSONArray()
        base.optJSONArray("episodes").objects().forEach { e ->
            val copy = JSONObject(e.toString())
            theirs[e.optString("id")]?.let { copy.put("added", minOf(e.optLong("added"), it)) }
            eps.put(copy)
        }
        return JSONObject(base.toString())
            .put("episodes", eps)
            .put("checked", maxOf(a.optLong("checked"), b.optLong("checked")))
            .put("added", maxOf(a.optLong("added"), b.optLong("added")))
    }

    // ── Правки ──────────────────────────────────────────────────────────────

    @Synchronized
    private fun change(block: () -> Unit) {
        block()
        reindex()
        save()
        Hub.soon()
    }

    fun changeSpeed(s: Float) {
        speed = s
        save()
        tracker?.speedChanged()
    }

    fun cycleSpeed() = changeSpeed(SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size])

    fun speedLabel(s: Float = speed): String {
        val text = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()
        return (if (Lang.english) text else text.replace('.', ',')) + "×"
    }

    fun setDone(id: String, done: Boolean) = change {
        progress = if (done) {
            val p = progress[id]
            progress + (id to Progress(p?.p ?: 0, p?.d ?: 0, true, System.currentTimeMillis()))
        } else {
            // «Не прослушано» — с начала; и это тоже правка, которую надо свести
            progress + (id to Progress(0, progress[id]?.d ?: 0, false, System.currentTimeMillis()))
        }
    }

    /** Ссылка из «Открыть по ссылке»: канал — его страница, ролик — играть. */
    suspend fun open(link: String): Opened {
        refreshing = ADD
        try {
            val vid = Links.videoIdFrom(link)
            if (vid == null) {
                val c = fetch(link)
                if (!isSubscribed(c.id)) withContext(Dispatchers.Main) { setGuestFull(c) }
                return Opened.Page(c.id)
            }
            byEpisode[vid]?.let { ch -> return Opened.Play(ch, ch.episodes.indexOfFirst { it.id == vid }) }
            val s = fetchEpisode(vid)
            val ch = withContext(Dispatchers.Main) {
                val ch = addToGuest(s)
                reindex()
                keepSingle(ch, s.e)
                ch
            }
            Diag.i("подкаст: выпуск по ссылке с канала ${s.chId}")
            return Opened.Play(ch, ch.episodes.indexOfFirst { it.id == vid })
        } catch (e: IOException) {
            return Opened.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            refreshing = null
        }
    }

    /** Полный список канала без подписки; выпуски по ссылке, которых в нём нет, остаются. */
    private fun setGuestFull(c: Channel) {
        val have = guests[c.id]
        val extra = have?.episodes.orEmpty().filter { e -> c.episodes.none { it.id == e.id } }
        guests = guests + (c.id to c.copy(episodes = c.episodes.map { it.copy(added = 0) } + extra))
        guestsFull = guestsFull + c.id
        reindex()
    }

    /** Открыли страницу канала без подписки — взять его выпуски целиком. */
    suspend fun loadGuest(ch: Channel) {
        if (ch.id in guestsFull || ch.id in guestFailed || guestLoading != null || isSubscribed(ch.id)) return
        guestLoading = ch.id
        try {
            val c = fetch(ch.url)
            withContext(Dispatchers.Main) { setGuestFull(c) }
        } catch (e: IOException) {
            guestFailed += ch.id
            Diag.w("подкаст: канал ${ch.id} не открылся: ${e.message}")
        } finally {
            guestLoading = null
        }
    }

    /** Подписаться на канал, открытый по ссылке. @return null — вышло, иначе текст для человека. */
    suspend fun subscribe(ch: Channel): String? {
        refreshing = ch.id
        try {
            val c = if (ch.id in guestsFull) ch else fetch(ch.url)
            val now = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                change {
                    channels = channels + c.copy(added = now, checked = now, episodes = c.episodes.map { it.copy(added = 0) })
                    guests = guests - ch.id
                    // Без подписки остаются только выпуски по ссылке, которых
                    // нет на вкладке «Видео» (эфиры, Shorts)
                    for (s in singles) if (s.chId == ch.id && c.episodes.none { it.id == s.e.id }) addToGuest(s)
                }
            }
            Diag.i("подкаст: подписка на канал ${c.id} со страницы, выпусков ${c.episodes.size}")
            return null
        } catch (e: IOException) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            refreshing = null
        }
    }


    fun remove(ch: Channel) = change {
        channels = channels.filter { it.id != ch.id }
        // Отписка помнится по времени — иначе компьютер вернул бы канал при
        // сводке. Отметки о прослушанном остаются
        removed = removed + (ch.id to System.currentTimeMillis())
    }

    /** Свежий список выпусков; появившиеся с прошлого раза — новые. */
    private suspend fun refreshChannel(ch: Channel) {
        val fresh = fetch(ch.url)
        val now = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            change {
                channels = channels.map { c ->
                    if (c.id != ch.id) c else {
                        val old = c.episodes.associateBy { it.id }
                        c.copy(
                            name = fresh.name.ifBlank { c.name },
                            avatar = fresh.avatar.ifBlank { c.avatar },
                            checked = now,
                            episodes = fresh.episodes.map { e -> e.copy(added = old[e.id]?.added ?: now) }
                        )
                    }
                }
            }
        }
    }

    /** Обновить один канал или все; [quiet] — только устаревшие. @return сколько новых, null — сбой. */
    suspend fun refresh(only: Channel? = null, quiet: Boolean = false): Int? {
        if (refreshing != null) return 0
        val due = (only?.let { listOf(it) } ?: channels)
            .filter { !quiet || System.currentTimeMillis() - it.checked > STALE_MS }
        if (due.isEmpty()) return 0
        refreshing = only?.id ?: ALL
        val before = countNew()
        var failed = false
        try {
            for (ch in due) {
                try { refreshChannel(ch) } catch (e: IOException) {
                    failed = true
                    Diag.w("подкаст: канал ${ch.id} не обновился: ${e.message}")
                }
            }
        } finally {
            refreshing = null
        }
        return if (failed) null else (countNew() - before).coerceAtLeast(0)
    }

    /** Раз в шесть часов — сами, при открытии приложения. */
    fun refreshIfStale() {
        scope.launch { runCatching { refresh(quiet = true) } }
    }

    fun countNew() = channels.sumOf { ch -> ch.episodes.count(::isNew) }

    // ── yt-dlp ──────────────────────────────────────────────────────────────

    /**
     * Адрес канала из того, что вставили: `@имя`, `/channel/UC…`, `/c/…`,
     * `/user/…` с любым хвостом. Ролик или плейлист — не канал: null.
     * Как `channel_base` на ПК.
     */
    fun channelBase(input: String): String? {
        var s = input.trim().removePrefix("https://").removePrefix("http://")
        val host: String
        val path: String
        val slash = s.indexOf('/')
        if (slash > 0 && s.substring(0, slash).contains('.')) {
            host = s.substring(0, slash).lowercase()
            path = s.substring(slash + 1)
        } else if (s.startsWith("@")) {
            host = "www.youtube.com"
            path = s
        } else return null
        if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")) return null
        s = path.substringBefore('?').substringBefore('#')
        val parts = s.split('/').filter { it.isNotEmpty() }
        val first = parts.firstOrNull() ?: return null
        fun ok(n: String) = n.isNotEmpty() && n.length <= 100 && n.all { it.isLetterOrDigit() || it in "-_.%" }
        val base = when {
            first.startsWith("@") -> first.drop(1).takeIf(::ok)?.let { "@$it" }
            first in setOf("channel", "c", "user") -> {
                val name = parts.getOrNull(1) ?: return null
                if (first == "channel" && !(name.startsWith("UC") && name.length == 24)) return null
                name.takeIf(::ok)?.let { "$first/$it" }
            }
            else -> null
        } ?: return null
        return "https://www.youtube.com/$base"
    }

    /**
     * Канал и его выпуски. Названия — на языке приложения: YouTube сам
     * переводит их под язык запроса, и без него у русских каналов приезжали
     * английские.
     */
    private suspend fun fetch(link: String): Channel = withContext(Dispatchers.IO) {
        val base = channelBase(link) ?: throw IOException(tr("Это не ссылка на канал YouTube"))
        val request = YoutubeDLRequest("$base/videos").apply {
            addOption("-J")
            addOption("--flat-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "10")
            addOption("--extractor-retries", "1")
            addOption("--extractor-args", "youtube:lang=${if (Lang.english) "en" else "ru"}")
        }
        val response = try {
            Engine.extracting { YoutubeDL.getInstance().execute(request) }
        } catch (t: Throwable) {
            throw IOException(tr("YouTube не ответил: {причина}", "причина" to (t.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(200))), t)
        }
        val out = response.out
        val start = out.indexOf('{')
        val json = runCatching { JSONObject(out.substring(start)) }.getOrNull()
            ?: throw IOException(tr("yt-dlp вернул непонятный ответ"))
        parse(json) ?: throw IOException(tr("Канал не нашёлся"))
    }

    /**
     * Один выпуск по ссылке на ролик — с каналом, откуда он. Как `episode`
     * в extract.rs на ПК.
     */
    private suspend fun fetchEpisode(id: String): Single = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$id").apply {
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
            addOption("--socket-timeout", "10")
            addOption("--extractor-args", "youtube:lang=${if (Lang.english) "en" else "ru"}")
            addOption("-O", listOf("title", "duration", "channel_id", "channel", "uploader").joinToString(SEP) { "%($it)s" })
            YtLogin.jarIfNeeded(App.instance)?.let { addOption("--cookies", it.absolutePath) }
        }
        val response = try {
            Engine.extracting { YoutubeDL.getInstance().execute(request) }
        } catch (t: Throwable) {
            throw IOException(tr("YouTube не ответил: {причина}", "причина" to (t.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(200))), t)
        }
        parseEpisode(id, response.out) ?: throw IOException(tr("Канал не нашёлся"))
    }

    private const val SEP = "\u0001"

    /** Строка `-O` выпуска: название, длина, канал. */
    fun parseEpisode(id: String, text: String): Single? {
        val parts = text.trim().split(SEP).map { it.trim() }
        fun field(i: Int) = parts.getOrNull(i)?.takeIf { it.isNotEmpty() && it != "NA" }
        val ch = field(2)?.takeIf { it.startsWith("UC") } ?: return null
        val dur = field(1)?.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
        return Single(ch, field(3) ?: field(4) ?: ch, "https://www.youtube.com/channel/$ch", Episode(id, field(0) ?: id, dur, 0))
    }

    private fun parse(v: JSONObject): Channel? {
        val id = v.optString("channel_id").ifBlank { v.optString("id") }
        if (!id.startsWith("UC")) return null
        val name = v.optString("channel").ifBlank { v.optString("uploader") }.ifBlank { id }
        val thumbs = v.optJSONArray("thumbnails").objects()
        val avatar = (thumbs.firstOrNull { it.optString("id") == "avatar_uncropped" }
            ?: thumbs.firstOrNull { it.has("width") && it.optInt("width") == it.optInt("height", -1) })
            ?.optString("url").orEmpty()
        val episodes = v.optJSONArray("entries").objects()
            .filter { it.optString("live_status") !in setOf("is_live", "is_upcoming") }
            .mapNotNull { e ->
                val eid = e.optString("id")
                if (eid.length !in 6..20) return@mapNotNull null
                Episode(eid, e.optString("title").ifBlank { eid }, e.optDouble("duration", 0.0).toInt().coerceAtLeast(0), 0)
            }
        return Channel(id, name, "https://www.youtube.com/channel/$id", avatar, 0, 0, episodes)
    }

    // ── Служба проигрывания ─────────────────────────────────────────────────

    /**
     * Ведёт место в выпуске, ставит скорость и продолжает выпуск с места.
     * Живёт в [PlaybackService] рядом с [Listening.Tracker].
     */
    class Tracker(private val player: Player) : Player.Listener {
        private val main = Handler(Looper.getMainLooper())
        private var ticks = 0

        private val tick = object : Runnable {
            override fun run() {
                record()
                // На диск — раз в полминуты, а не на каждом шаге
                if (++ticks % 6 == 0) save()
                if (player.isPlaying) main.postDelayed(this, TICK_MS)
            }
        }

        init {
            applySpeed(player.currentMediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            main.removeCallbacks(tick)
            if (isPlaying) main.postDelayed(tick, TICK_MS) else {
                record()
                save()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applySpeed(mediaItem)
            val id = mediaItem?.mediaId ?: return
            if (!isEpisode(id)) return
            val at = resumeMs(id)
            // Только если плеер начинает с начала: восстановление после
            // сбоя ставит своё место само
            if (at > 0 && player.currentPosition < 2_000) player.seekTo(at)
        }

        /** Скорость у выпуска своя, у музыки — всегда обычная. */
        private fun applySpeed(item: MediaItem?) {
            val want = if (isEpisode(item?.mediaId)) speed else 1f
            if (player.playbackParameters.speed != want) player.setPlaybackSpeed(want)
        }

        /** Скорость поменяли на экране — применить к играющему. */
        fun speedChanged() = applySpeed(player.currentMediaItem)

        fun stop() {
            main.removeCallbacks(tick)
            record()
            save()
        }

        private fun record() {
            val id = player.currentMediaItem?.mediaId ?: return
            val ch = channelOf(id) ?: return
            val pos = (player.currentPosition / 1000).toInt()
            if (pos < 1) return
            if (!isSubscribed(ch.id) && singles.none { it.e.id == id }) {
                ch.episodes.firstOrNull { it.id == id }?.let { e -> main.post { keepSingle(ch, e) } }
            }
            val dur = (player.duration.takeIf { it > 0 }?.div(1000)?.toInt())
                ?: ch.episodes.firstOrNull { it.id == id }?.dur ?: 0
            val was = progress[id]
            val done = (was?.done == true) || (dur > 0 && pos >= dur - TAIL_SEC)
            synchronized(Podcasts) {
                progress = progress + (id to Progress(pos, dur, done, System.currentTimeMillis()))
            }
        }

        private companion object {
            const val TICK_MS = 5_000L
        }
    }

    /** Служба жива — ей и говорим про смену скорости. */
    @Volatile var tracker: Tracker? = null
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject?.entries(): Map<String, JSONObject> =
    if (this == null) emptyMap() else keys().asSequence().mapNotNull { k -> optJSONObject(k)?.let { k to it } }.toMap()
