package com.lazymusic.app

import android.content.Context
import android.net.ConnectivityManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * YouTube просит подтвердить, что это не бот, — и входа, которым это
 * лечится ([YtLogin]), нет. Отдельный тип, чтобы источник данных
 * ([YtDataSource]) отличал эту беду от прочих и шёл в [Stash].
 */
class BotCheck(message: String) : IOException(message)

/**
 * Запас на случай, когда YouTube не отдаёт звук. То же, что на ПК
 * (`stash.rs` у lazy-music-pc), — объяснение там; коротко:
 *
 * - **трек библиотеки, который хоть раз добыли**, тихо сохраняется сюда по
 *   уже добытой ссылке — второй раз YouTube не спрашиваем. Только по Wi-Fi
 *   и прочим безлимитным сетям: на мобильной это вдвое больше трафика;
 * - **копия с SoundCloud** (`<id>.sc.<ext>`) — когда проверка уже идёт,
 *   а трека в запасе нет. Звук не тот же файл, что на YouTube, поэтому
 *   играет она только пока YouTube отказывает;
 * - **минута тишины**: после первой проверки YouTube не спрашиваем вовсе —
 *   каждый новый вопрос только продлевает её ([botPaused]).
 *
 * Место — [Settings.stashMb]; сверх него стирается давно не игравшее.
 * Скачанное человеком ([Cache]) сюда не входит.
 */
object Stash {

    private val AUDIO_EXT = listOf("m4a", "webm", "opus", "mp3", "ogg", "aac")

    /** Сколько после проверки на бота не спрашиваем YouTube. */
    private const val BOT_PAUSE_MS = 60_000L

    /** Короче — отрывок или заставка, а не трек. */
    private const val MIN_SEC = 45

    /** Кусок за запрос: одним запросом YouTube отдаёт файл со скоростью игры. */
    private const val CHUNK = 2L shl 20

    private const val SEP = "\u0001"

    @Volatile private var botUntil = 0L

    /** По одному: запас не должен отъедать канал у того, что играет. */
    private val worker = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { r ->
            Thread(r, "lazy-stash").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    )

    private val running = ConcurrentHashMap.newKeySet<String>()

    /**
     * Название и исполнитель по идентификатору — для поиска на SoundCloud.
     * Источник данных видит только `ytm:<id>`; сюда трек кладёт
     * [Track.toMediaItem], то есть всё, что попало в очередь.
     */
    private val known = ConcurrentHashMap<String, Track>()

    /** Уже искали на SoundCloud за этот запуск: идентификатор → нашлось ли. */
    private val tried = ConcurrentHashMap<String, Boolean>()

    private val http by lazy {
        OkHttpClient.Builder().dns(Ipv4First)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "stash")
            .apply { if (!exists()) mkdirs() }

    fun note(track: Track) {
        if (track.id.isNotBlank()) known[track.id] = track
    }

    // ── Проверка «не бот» ────────────────────────────────────────────────────

    /** Отладочная сборка: `--ez fake_bot true` — как будто YouTube всё время просит проверку. */
    @Volatile var fakeBot = false

    fun botPaused() = fakeBot || System.currentTimeMillis() < botUntil

    /** YouTube сейчас не спрашиваем: проверка идёт, а входа, которым она лечится, нет. */
    fun skipYouTube(context: Context) = fakeBot || botPaused() && YtLogin.jarIfNeeded(context) == null

    fun botHit() {
        botUntil = System.currentTimeMillis() + BOT_PAUSE_MS
    }

    // ── Поиск файла ──────────────────────────────────────────────────────────

    /** Сохранённый с YouTube. */
    fun find(context: Context, videoId: String): File? =
        AUDIO_EXT.map { File(dir(context), "$videoId.$it") }.firstOrNull { it.isFile && it.length() > 0 }
            ?.also { it.setLastModified(System.currentTimeMillis()) }

    /** Копия с SoundCloud. */
    fun findSc(context: Context, videoId: String): File? =
        AUDIO_EXT.map { File(dir(context), "$videoId.sc.$it") }.firstOrNull { it.isFile && it.length() > 0 }
            ?.also { it.setLastModified(System.currentTimeMillis()) }

    // ── Запас с YouTube ──────────────────────────────────────────────────────

    /** Ссылка на трек добыта — сохранить его, если он из библиотеки и его ещё нет. */
    fun keep(context: Context, videoId: String, url: String) {
        val app = context.applicationContext
        if (!Settings.stash || metered(app)) return
        if (Cache.find(app, videoId) != null || find(app, videoId) != null) return
        if (Settings.libraryTrack(videoId) == null) return
        if (!running.add(videoId)) return
        worker.launch {
            try {
                val bytes = save(app, videoId, url)
                Diag.i("$videoId — в запас, ${bytes / 1024} КБ")
                // Настоящий звук пришёл — копия с SoundCloud больше не нужна
                findSc(app, videoId)?.delete()
                trim(app)
            } catch (t: Throwable) {
                Diag.w("$videoId — в запас не сохранился: ${Diag.cause(t)}")
            } finally {
                running.remove(videoId)
            }
        }
    }

    private fun metered(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(true)

    private fun save(context: Context, videoId: String, url: String): Long {
        // Только что добытая ссылка первые секунды бывает «не открыта» (403)
        Thread.sleep(3_000)
        val part = File(dir(context), "$videoId.m4a.part")
        var pos = 0L
        var total = -1L
        var refusals = 0
        try {
            part.outputStream().use { out ->
                while (total < 0 || pos < total) {
                    val req = Request.Builder().url(url).header("Range", "bytes=$pos-${pos + CHUNK - 1}").build()
                    val retry = http.newCall(req).execute().use { resp ->
                        if ((resp.code == 403 || resp.code == 429) && refusals < 8) {
                            refusals++
                            return@use true
                        }
                        if (resp.code != 200 && resp.code != 206) throw IOException("audio server answered ${resp.code}")
                        if (total < 0) total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                        val got = resp.body!!.byteStream().copyTo(out)
                        pos += got
                        if (resp.code == 200 || got == 0L) total = pos
                        false
                    }
                    if (retry) Thread.sleep(1_000)
                }
            }
            if (pos <= 0) throw IOException("empty file")
            val done = File(dir(context), "$videoId.m4a")
            if (!part.renameTo(done)) throw IOException("rename failed")
            return pos
        } finally {
            part.delete()
        }
    }

    // ── Копия с SoundCloud ───────────────────────────────────────────────────

    /**
     * Копия трека с SoundCloud: найти, сверить, скачать. Блокирует — зовётся
     * из потока загрузки плеера. null — не нашлась или не скачалась; второй
     * раз за запуск тот же трек не ищем.
     */
    fun soundcloud(context: Context, videoId: String): File? {
        findSc(context, videoId)?.let { return it }
        if (tried[videoId] == false) return null
        val track = known[videoId] ?: Settings.libraryTrack(videoId) ?: return null
        val artist = track.artist.trim()
        val title = track.title.trim()
        if (artist.isEmpty() || title.isEmpty() || title == videoId) {
            tried[videoId] = false
            return null
        }
        val rows = try {
            search("$artist ${ImportMatch.searchTitle(title)}")
        } catch (t: Throwable) {
            Diag.w("$videoId — SoundCloud не ответил: ${Diag.cause(t)}")
            return null
        }
        // У части треков SoundCloud отдаёт только отрывок или звук под DRM —
        // такие не скачаются, тогда пробуем следующую находку
        for (row in rows.filter { sameTrack(it, artist, title) }.take(3)) {
            val file = runCatching { fetch(context, videoId, row.url) }
                .onFailure { Diag.w("$videoId — копия с SoundCloud не скачалась: ${Diag.cause(it)}") }
                .getOrNull()
            if (file != null) {
                tried[videoId] = true
                Diag.i("$videoId — копия с SoundCloud, ${file.length() / 1024} КБ")
                trim(context)
                return file
            }
        }
        tried[videoId] = false
        Diag.i("$videoId — на SoundCloud не нашёлся")
        return null
    }

    /** Подкачать копию следующего трека, пока проверка идёт. */
    fun soundcloudAhead(context: Context, videoId: String) {
        val app = context.applicationContext
        if (findSc(app, videoId) != null || Cache.find(app, videoId) != null || find(app, videoId) != null) return
        worker.launch { runCatching { soundcloud(app, videoId) } }
    }

    class ScRow(val url: String, val title: String, val uploader: String, val dur: Int)

    private fun search(q: String): List<ScRow> {
        val request = YoutubeDLRequest("scsearch5:$q").apply {
            addOption("--flat-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "10")
            addOption("--extractor-retries", "1")
            addOption("--print", "%(url)s$SEP%(title)s$SEP%(uploader)s$SEP%(duration)s")
        }
        val out = Engine.extracting { YoutubeDL.getInstance().execute(request) }.out
        return parse(out)
    }

    internal fun parse(out: String): List<ScRow> = out.lineSequence().mapNotNull { line ->
        val p = line.split(SEP)
        fun f(i: Int) = p.getOrNull(i)?.trim()?.takeUnless { it == "NA" }.orEmpty()
        val url = f(0)
        if (p.size < 3 || !urlOk(url)) null
        else ScRow(url, f(1), f(2), f(3).toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0)
    }.toList()

    internal fun urlOk(url: String) =
        (url.startsWith("https://api.soundcloud.com/tracks/") || url.startsWith("https://soundcloud.com/")) &&
            url.length < 300 && url.none { it.isWhitespace() }

    /** «Artist - Title» в названии на SoundCloud — частое дело: исполнителя спереди убираем. */
    internal fun withoutArtist(title: String, artist: String): String {
        val cut = title.indexOf(" - ")
        if (cut < 0) return title
        val who = ImportMatch.flat(artist.split(Regex(""",|&| и | x """, RegexOption.IGNORE_CASE)).first())
        return if (who.length >= 2 && ImportMatch.flat(title.substring(0, cut)).contains(who)) title.substring(cut + 3) else title
    }

    /** Находка SoundCloud — тот же трек? Сверка как у импорта: лучше пропустить, чем поставить чужой. */
    internal fun sameTrack(row: ScRow, artist: String, title: String): Boolean {
        if (row.dur in 1 until MIN_SEC) return false
        if (!ImportMatch.mentions(Radio.Row("", row.title, row.uploader, ""), artist)) return false
        return ImportMatch.sameTitle(row.title, title) || ImportMatch.sameTitle(withoutArtist(row.title, artist), title)
    }

    private fun fetch(context: Context, videoId: String, url: String): File {
        val request = YoutubeDLRequest(url).apply {
            // Отрывок на 30 секунд — не трек
            addOption("-f", "bestaudio[format_id!*=preview]")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "15")
            addOption("-o", File(dir(context), "$videoId.sc.%(ext)s").absolutePath)
        }
        Engine.extracting { YoutubeDL.getInstance().execute(request) }
        val file = findSc(context, videoId) ?: throw IOException("no file after download")
        runCatching { oneIndex(file) }.onFailure { Diag.w("$videoId — оглавление копии не собралось: ${Diag.cause(it)}") }
        return file
    }

    /**
     * Одно оглавление на весь файл.
     *
     * SoundCloud отдаёт звук кусками (HLS), и yt-dlp склеивает их как есть:
     * у каждого из ~30 кусков своё оглавление (`sidx`), а длина в заголовке —
     * ноль. ExoPlayer берёт первое оглавление за всё: «Prepare for Escape»
     * на 5:26 показывался длиной 3:30 (замерено 2026-11-22). Браузер на ПК
     * и в Foundry такой файл читает верно — чинить нужно только здесь.
     *
     * Куски (`moof` + `mdat`) не трогаются; выбрасываются их `styp` и `sidx`,
     * а сразу после `moov` встаёт одно оглавление со ссылкой на каждый кусок.
     * Смещения внутри кусков считаются от их же `moof`, так что звук не едет.
     */
    internal fun oneIndex(file: File) {
        val d = file.readBytes()
        fun u32(at: Int) = ((d[at].toLong() and 0xff) shl 24) or ((d[at + 1].toLong() and 0xff) shl 16) or
            ((d[at + 2].toLong() and 0xff) shl 8) or (d[at + 3].toLong() and 0xff)
        fun u64(at: Int) = (u32(at) shl 32) or u32(at + 4)
        class Box(val type: String, val at: Int, val size: Int)
        val boxes = mutableListOf<Box>()
        var at = 0
        while (at + 8 <= d.size) {
            val size = u32(at).toInt()
            if (size < 8 || at + size > d.size) throw IOException("box at $at: size $size")
            boxes += Box(String(d, at + 4, 4, Charsets.ISO_8859_1), at, size)
            at += size
        }
        val sidx = boxes.filter { it.type == "sidx" }
        if (sidx.size < 2) return
        val head = boxes.takeWhile { it.type != "styp" && it.type != "sidx" && it.type != "moof" }
        if (head.none { it.type == "moov" }) return
        // Из каждого оглавления — шкала, начало и одна ссылка (длительность, SAP)
        var timescale = 0L
        var earliest = -1L
        val refs = mutableListOf<Triple<Long, Long, Long>>() // длительность, SAP, размер куска
        val chunks = mutableListOf<Box>()
        var i = boxes.indexOf(sidx.first())
        while (i < boxes.size) {
            val b = boxes[i]
            when (b.type) {
                "styp" -> { i++; continue }
                "sidx" -> {
                    val v = d[b.at + 8].toInt()
                    timescale = u32(b.at + 16)
                    var p = b.at + 20
                    val ept = if (v == 0) u32(p).also { p += 8 } else u64(p).also { p += 16 }
                    if (earliest < 0) earliest = ept
                    val count = ((d[p + 2].toInt() and 0xff) shl 8) or (d[p + 3].toInt() and 0xff)
                    if (count != 1) throw IOException("sidx with $count references")
                    p += 4
                    val moof = boxes.getOrNull(i + 1)
                    val mdat = boxes.getOrNull(i + 2)
                    if (moof?.type != "moof" || mdat?.type != "mdat") throw IOException("sidx not followed by moof+mdat")
                    refs += Triple(u32(p + 4), u32(p + 8), (moof.size + mdat.size).toLong())
                    chunks += moof
                    chunks += mdat
                    i += 3
                }
                else -> throw IOException("unexpected ${b.type}")
            }
        }
        val out = java.io.ByteArrayOutputStream(d.size)
        fun w32(v: Long) { out.write((v ushr 24).toInt() and 0xff); out.write((v ushr 16).toInt() and 0xff); out.write((v ushr 8).toInt() and 0xff); out.write(v.toInt() and 0xff) }
        for (b in head) out.write(d, b.at, b.size)
        // sidx версии 1: 12 (заголовок) + 4 (track id) + 4 (шкала) + 16 (времена) + 4 + 12 на ссылку
        w32(12L + 4 + 4 + 16 + 4 + 12L * refs.size)
        out.write("sidx".toByteArray(Charsets.ISO_8859_1))
        w32(1L shl 24)                // версия 1, флаги 0
        w32(1)                        // дорожка
        w32(timescale)
        w32(earliest ushr 32); w32(earliest and 0xffffffffL)
        w32(0); w32(0)                // first_offset: куски сразу за оглавлением
        w32(refs.size.toLong())       // reserved 0 + reference_count
        for ((dur, sap, size) in refs) {
            w32(size and 0x7fffffffL) // тип 0 — ссылка на звук
            w32(dur)
            w32(sap)
        }
        for (b in chunks) out.write(d, b.at, b.size)
        val tmp = File(file.path + ".part")
        tmp.writeBytes(out.toByteArray())
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        }
    }

    // ── Место ────────────────────────────────────────────────────────────────

    private fun files(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()

    fun sizeBytes(context: Context): Long = files(context).sumOf { it.length() }

    fun count(context: Context): Int = files(context).size

    fun trim(context: Context) {
        val limit = Settings.stashMb.toLong() * 1024 * 1024
        var total = sizeBytes(context)
        if (total <= limit) return
        var removed = 0
        for (f in files(context).sortedBy { it.lastModified() }) {
            if (total <= limit) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                removed++
            }
        }
        Diag.i("запас: стёр давно не игравших — $removed")
    }

    fun clear(context: Context) {
        files(context).forEach { it.delete() }
    }
}
