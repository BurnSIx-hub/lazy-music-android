package com.lazymusic.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread

/**
 * Движок извлечения: Python и yt-dlp, распакованные в filesDir.
 *
 * Разворачивается один раз при старте приложения и занимает несколько секунд,
 * поэтому всё, что его ждёт, ждёт через [awaitReady] — а не пытается поднять
 * повторно. Ждут из потока загрузки ExoPlayer, где блокировка допустима.
 *
 * ## Обновление yt-dlp
 *
 * Идёт само: при запуске, при возвращении на экран и при новой очереди —
 * но не чаще, чем раз в [CHECK_EVERY_MS], — и вне очереди, когда добыча
 * ломается. Кнопка в настройках осталась, но нажимать её больше незачем.
 *
 * **Канал — ночной, а не стабильный.** Стабильные выпуски выходят раз в
 * месяц-два: с 19 августа по 7 октября 2026 не вышло ни одного, и всё это
 * время кнопка «Обновить» на стабильном канале честно отвечала «без
 * изменений» — то есть не делала ничего. Починки под очередную выходку
 * YouTube попадают сначала в ночные сборки, и сами авторы yt-dlp советуют
 * обычным пользователям именно их.
 *
 * **Обновляем сами, а не методом библиотеки.** У её `updateYoutubeDL` три
 * изъяна, и для самостоятельного обновления каждый неприемлем:
 *
 * - сеть без таймаутов — зависший GitHub держал бы обновление вечно;
 * - файл меняется не разом: сначала стирается папка, потом копируется
 *   новый, и запущенная в этот зазор добыча ссылки падает;
 * - скачанное никак не проверяется. Если очередной выпуск потребует Python
 *   новее вшитого в библиотеку, добыча сломается целиком, а человек будет
 *   в дороге и без компьютера.
 *
 * Поэтому здесь: скачали рядом, подменили под замком [gate], запустили
 * `--version` — и если новый не завёлся, вернули прежний, а негодную
 * версию запомнили, чтобы не тянуть её при каждой проверке.
 *
 * Раскладка файлов и ключи настроек подсмотрены в `youtubedl-android`
 * 0.18.1. При обновлении библиотеки сверить заново: [LIB_DIR], [LIB_TAG].
 */
object Engine {

    /**
     * Ключ для любого запуска yt-dlp, после которого будет скачиваться звук.
     *
     * Ссылка привязана к адресу, с которого её добыли, а плеер ходит за
     * звуком по IPv4 — см. [Ipv4First], там и замер, и вся история. Значит,
     * и добывать её надо по IPv4, иначе адреса разойдутся и YouTube ответит
     * 403. Менять этот ключ и порядок адресов у плеера можно только вместе.
     */
    const val IPV4 = "--force-ipv4"

    /**
     * Быстрый путь к YouTube: только клиент visionos, которому не нужен JS.
     *
     * По умолчанию yt-dlp спрашивает клиентов tv и visionos и берёт ссылку
     * от tv. Для tv он решает задачку из JS-плеера YouTube, а на телефоне её
     * решает QuickJS — без JIT, медленно: в журнале 10–16 с на каждую ссылку
     * при 2,8 с на ПК с Deno. Замерено на ПК 2026-10-17: visionos отдаёт тот
     * же формат 140 (AAC ~129 кбит/с), ссылка открывается сразу, без отказов
     * первых двух секунд, что бывают у свежей ссылки tv.
     *
     * Не вышло — добыча повторяется по-обычному ([fastFailed]): YouTube
     * время от времени закрывает клиентам дорогу, и тогда это не поломка.
     */
    const val FAST_CLIENT = "youtube:player_client=visionos"

    /** Столько неудач быстрого пути подряд — и он выключается на [FAST_PAUSE_MS]. */
    private const val FAST_STRIKES = 3
    private const val FAST_PAUSE_MS = 6 * 60 * 60 * 1000L

    private val fastStrikes = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var fastOffUntil = 0L

    /** Пробовать ли сначала быстрый путь. */
    val fastClient: Boolean get() = System.currentTimeMillis() >= fastOffUntil

    /** Выключить быстрый путь — только для проверки запасного (отладочная сборка). */
    fun pauseFast() {
        fastOffUntil = System.currentTimeMillis() + FAST_PAUSE_MS
        Diag.w("быстрый путь выключен вручную — проверка")
    }

    /** Быстрый путь справился — неудачи считаются заново. */
    fun fastWorked() = fastStrikes.set(0)

    /**
     * Быстрый путь не справился, а обычный — да. Значит, дело в клиенте, а не
     * в сети; трижды подряд — YouTube его прикрыл, и тратить на него секунды
     * перед каждой ссылкой незачем.
     */
    fun fastFailed() {
        if (fastStrikes.incrementAndGet() < FAST_STRIKES) return
        fastStrikes.set(0)
        fastOffUntil = System.currentTimeMillis() + FAST_PAUSE_MS
        Diag.w("быстрый путь к YouTube не сработал трижды подряд — 6 часов по-обычному")
    }

    /**
     * Запуск yt-dlp сначала быстрым путём, при неудаче — обычным.
     * `run` получает признак быстрого пути и сам добавляет ключи.
     */
    fun <T> fastFirst(what: String, run: (fast: Boolean) -> T): T {
        if (!fastClient) return run(false)
        val first = try {
            return run(true).also { fastWorked() }
        } catch (t: Throwable) {
            t
        }
        // Работу сняли — повторять её другим путём некому и незачем
        if (first is InterruptedException || first is java.util.concurrent.CancellationException) throw first
        Diag.w("$what — быстрый путь не вышел, пробую обычный: ${Diag.cause(first, 200)}")
        return run(false).also { fastFailed() }
    }

    /** Ночные сборки yt-dlp — см. шапку, почему не стабильные. */
    private const val RELEASES =
        "https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"

    /** Имя нужного файла в выпуске: сборка «для любого Python», без exe. */
    private const val ASSET = "yt-dlp"

    /** Настоящий файл весит около трёх мегабайт. Меньше — скачалось не то. */
    private const val MIN_SIZE = 1024 * 1024L

    /** Чаще этого сами за обновлением не ходим: сборки выходят раз в сутки. */
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    /** После отказа проверяем вне очереди, но не чаще — иначе получится цикл. */
    private const val URGENT_EVERY_MS = 15 * 60 * 1000L

    /** Сколько ждём, пока доработают начатые добычи, прежде чем подменить файл. */
    private const val SWAP_WAIT_S = 60L

    // Внутренности youtubedl-android: папка в noBackupFilesDir и её настройки
    private const val LIB_DIR = "youtubedl-android"
    private const val LIB_TAG = "dlpVersion"
    private const val LIB_NAME = "dlpVersionName"

    private val latch = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null
    @Volatile private var started = false

    /**
     * Добыча берёт этот замок на чтение, подмена файла — на запись.
     * Одновременных добыч может быть сколько угодно, а подмена ждёт, пока
     * они закончатся, и на свои секунды не пускает новые.
     */
    private val gate = ReentrantReadWriteLock()

    private val updating = AtomicBoolean(false)
    @Volatile private var urgentAt = 0L

    /** Какой yt-dlp стоит. Пусто — ещё встроенный в библиотеку, давний. */
    var version by mutableStateOf<String?>(null)
        private set

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(name = "engine-init") {
            try {
                YoutubeDL.getInstance().init(app)
                version = installed(app)
                Diag.i("движок готов, yt-dlp ${version ?: "встроенный"}")
            } catch (t: Throwable) {
                failure = t
                Diag.w("движок не поднялся: ${Diag.chain(t)}")
            } finally {
                latch.countDown()
            }
            if (failure == null) refreshIfDue(app)
        }
    }

    /** Блокирует до готовности движка. Бросает, если развернуть не удалось. */
    fun awaitReady() {
        latch.await()
        failure?.let { throw IOException(tr("движок не поднялся: {причина}", "причина" to it.message), it) }
    }

    /**
     * Обёртка для любого запуска yt-dlp. Ждёт готовности движка и не даёт
     * обновлению подменить файл посреди работы.
     */
    fun <T> extracting(block: () -> T): T {
        awaitReady()
        return gate.read(block)
    }

    // ── Обновление ───────────────────────────────────────────────────────────

    sealed class Outcome {
        class Updated(val from: String?, val to: String) : Outcome()
        class Current(val version: String?) : Outcome()
        class Rejected(val tag: String) : Outcome()
        class Failed(val why: String) : Outcome()
        object Busy : Outcome()

        fun describe(): String = when (this) {
            is Updated -> tr("обновлён: {было} → {стало}", "было" to (from ?: tr("встроенный")), "стало" to to)
            is Current -> tr("уже свежий ({версия})", "версия" to (version ?: tr("встроенный")))
            is Rejected -> tr("сборка {выпуск} не запустилась, оставлен прежний", "выпуск" to tag)
            is Failed -> tr("проверить не вышло — {причина}", "причина" to why)
            Busy -> tr("обновление уже идёт")
        }
    }

    /**
     * Тихая проверка по расписанию. Зовётся часто — при запуске, при
     * возвращении на экран, при каждой новой очереди, — а в сеть ходит
     * не чаще раза в [CHECK_EVERY_MS]. Ничего не ждёт и не блокирует.
     */
    fun refreshIfDue(context: Context) {
        val since = System.currentTimeMillis() - Settings.engineCheckedAt
        if (since in 0L until CHECK_EVERY_MS) return
        if (updating.get() || !started) return
        val app = context.applicationContext
        thread(name = "engine-update", isDaemon = true) { update(app, "по расписанию") }
    }

    /**
     * Внеочередная проверка: добыча только что сломалась, и возможно,
     * дело в устаревшем yt-dlp. Блокирует — зовётся из потока загрузки.
     *
     * @return true, если версия сменилась и стоит попробовать ещё раз
     */
    fun refreshAfterFailure(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (urgentAt != 0L && now - urgentAt < URGENT_EVERY_MS) return false
        val outcome = update(context, "после отказа")
        // Попытку засчитываем, только если до GitHub дошли. Иначе отказ
        // из-за пропавшей сети съел бы единственную попытку, и когда сеть
        // вернётся, настоящая поломка ждала бы обновления четверть часа
        if (outcome !is Outcome.Failed && outcome != Outcome.Busy) urgentAt = now
        return outcome is Outcome.Updated
    }

    /** Проверить и, если есть что, обновить. Блокирует на время работы. */
    fun update(context: Context, reason: String): Outcome {
        val app = context.applicationContext
        if (runCatching { awaitReady() }.isFailure) return Outcome.Failed(tr("движок не поднялся"))
        if (!updating.compareAndSet(false, true)) return Outcome.Busy
        try {
            val outcome = runCatching { check(app) }
                .getOrElse { Outcome.Failed(Diag.chain(it, 200)) }
            Diag.i("yt-dlp, проверка $reason: ${outcome.describe()}")
            return outcome
        } finally {
            updating.set(false)
        }
    }

    private fun check(app: Context): Outcome {
        val current = installed(app)
        val release = JSONObject(fetchText(RELEASES))
        val tag = release.optString("tag_name").trim()
        if (tag.isEmpty()) return Outcome.Failed(tr("GitHub ответил без номера версии"))

        // До сети дошли и ответ получили — следующая плановая проверка
        // не раньше, чем через положенный срок
        Settings.engineCheckedAt = System.currentTimeMillis()
        if (tag == current) return Outcome.Current(current)
        if (tag == Settings.engineRejected) return Outcome.Rejected(tag)

        val link = assetUrl(release)
            ?: return Outcome.Failed(tr("в выпуске {выпуск} нет файла {файл}", "выпуск" to tag, "файл" to ASSET))

        val dir = File(File(app.noBackupFilesDir, LIB_DIR), "yt-dlp")
        val binary = File(dir, "yt-dlp")
        val fresh = File(dir, "yt-dlp.new")
        val previous = File(dir, "yt-dlp.prev")
        dir.mkdirs()
        download(link, fresh)

        // Сеть позади — дальше только диск и один запуск Python, так что
        // замок держится секунды, а не «пока GitHub не ответит»
        val swap = gate.writeLock()
        if (!swap.tryLock(SWAP_WAIT_S, TimeUnit.SECONDS)) {
            fresh.delete()
            return Outcome.Busy
        }
        try {
            if (binary.isFile) binary.copyTo(previous, overwrite = true)
            if (!fresh.renameTo(binary)) {
                fresh.copyTo(binary, overwrite = true)
                fresh.delete()
            }
            val seen = selfTest()
            if (seen == null) {
                if (previous.isFile) previous.copyTo(binary, overwrite = true)
                Settings.engineRejected = tag
                return Outcome.Rejected(tag)
            }
            app.getSharedPreferences(LIB_DIR, Context.MODE_PRIVATE).edit()
                .putString(LIB_TAG, tag)
                .putString(LIB_NAME, release.optString("name").ifBlank { tag })
                .apply()
            version = tag
            return Outcome.Updated(current, tag)
        } finally {
            swap.unlock()
        }
    }

    /**
     * Заводится ли только что подменённый yt-dlp. Сети не касается —
     * проверяем ровно одно: что Python его вообще исполняет. Этого хватает
     * на главную беду, выпуск под Python новее вшитого: такой падает
     * уже на импорте.
     *
     * @return номер версии, который он о себе сообщил, или null
     */
    private fun selfTest(): String? = runCatching {
        val request = YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
        YoutubeDL.getInstance().execute(request).out
            .lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
    }.onFailure {
        Diag.w("новый yt-dlp не запустился: ${Diag.chain(it, 300)}")
    }.getOrNull()?.takeIf { it.firstOrNull()?.isDigit() == true }

    private fun installed(app: Context): String? =
        app.getSharedPreferences(LIB_DIR, Context.MODE_PRIVATE)
            .getString(LIB_TAG, null)?.takeIf { it.isNotBlank() }

    // ── Сеть ─────────────────────────────────────────────────────────────────

    private fun assetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == ASSET) {
                return asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
            }
        }
        return null
    }

    private fun fetchText(url: String): String {
        val conn = connect(url)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode !in 200..299) throw IOException("GitHub: ответ ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        val conn = connect(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("скачивание: ответ ${conn.responseCode}")
            conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } catch (t: Throwable) {
            to.delete()
            throw t
        } finally {
            conn.disconnect()
        }
        // Настоящая сборка начинается со строки «#!» и весит мегабайты.
        // Страница с ошибкой вместо файла на этом и попадётся
        val head = to.inputStream().use { stream -> ByteArray(2).also { stream.read(it) } }
        if (to.length() < MIN_SIZE || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) {
            val size = to.length()
            to.delete()
            throw IOException("скачалось не то: $size байт")
        }
    }

    /** Таймауты обязательны: без них зависший сервер держал бы обновление вечно. */
    private fun connect(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
}
