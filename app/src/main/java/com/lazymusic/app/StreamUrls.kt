package com.lazymusic.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ролик недоступен насовсем: удалён, закрыт, не для этой страны.
 *
 * Наследует [FileNotFoundException] нарочно: ExoPlayer такие ошибки не
 * повторяет, а сразу отдаёт плееру. Иначе он ещё трижды запускал бы yt-dlp
 * ради ответа, который не изменится, — секунд пятнадцать тишины впустую.
 */
class TrackUnavailable(message: String) : FileNotFoundException(message)

/**
 * Прямые ссылки на звук: добытые, припасённые и добываемые заранее.
 *
 * Раньше ссылка добывалась ровно в тот момент, когда трек начинал играть, —
 * и эти три-шесть секунд работы yt-dlp были слышны как пауза между треками.
 * Теперь [warm] запускает ту же работу заранее, пока играет предыдущий трек,
 * и к переходу ссылка уже лежит здесь.
 *
 * ## Ссылка привязана к адресу, с которого её добыли
 *
 * В ней есть параметр `ip=` — адрес телефона на момент добычи — и срок
 * `expire=`, около шести часов. С любого другого адреса YouTube отвечает
 * 403. Телефон же меняет адрес постоянно: вышел из дома — вместо Wi-Fi
 * мобильная сеть; оператор переподключил — другой адрес; на мобильной сети
 * есть и IPv4, и IPv6, на домашней только IPv4.
 *
 * Отсюда два правила, и оба появились после месяца жалоб на «403»:
 *
 * - запас живёт, **пока жива сеть**, при которой его собрали: сменилась —
 *   [clear] выбрасывает всё разом;
 * - отвергнутую ссылку нельзя отдавать второй раз. Раньше после отказа
 *   плеер приходил за ссылкой снова и получал отсюда ту же самую — и так
 *   до двух часов подряд, пока не истечёт [TTL_MS]. Теперь источник данных
 *   зовёт [reject], и следующая попытка добывает ссылку заново.
 *
 * Очередь из идентификаторов при этом остаётся как была: припасённые ссылки —
 * это кэш поверх неё, а не замена. Протухло — просто добудем заново.
 */
object StreamUrls {

    private const val TAG = "LazyMusic"

    /** Сколько считаем ссылку годной, если сеть не менялась. */
    private const val TTL_MS = 2 * 60 * 60 * 1000L

    /** За сколько до срока из самой ссылки перестаём ей верить. */
    private const val EXPIRY_MARGIN_MS = 10 * 60 * 1000L

    /** Сколько ссылок помним. Больше пары десятков наперёд всё равно не нужно. */
    private const val KEEP = 32

    /** Слова, по которым ясно: ролика нет и не будет, повторять бесполезно. */
    private val GONE = listOf(
        "video unavailable", "private video", "this video is not available",
        "not available in your country", "been removed", "been terminated",
        "members-only", "confirm your age"
    )

    /**
     * Упреждающая добыча идёт в один поток: yt-dlp — это запуск Python,
     * и десяток таких запусков разом телефону не на пользу. Срочной добыче
     * (трек уже начался) эта очередь не мешает — она идёт своим путём.
     */
    private val worker = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { r ->
            Thread(r, "lazy-prefetch").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    )

    /**
     * @param until когда перестаём ей верить
     * @param note что о ней известно — для журнала, без самой ссылки
     */
    private class Entry(val url: String, val at: Long, val until: Long, val note: String)

    private val ready = ConcurrentHashMap<String, Entry>()
    private val running = ConcurrentHashMap<String, Deferred<String>>()

    /** Каким путём добыта последняя ссылка ролика: true — быстрым (visionos). */
    private val lastFast = ConcurrentHashMap<String, Boolean>()

    /**
     * Ролики, которым нужен свой путь, — см. [otherWay]. Живёт до конца
     * процесса: чем ролик не угодил клиенту, со временем может и пройти.
     */
    private val prefer = ConcurrentHashMap<String, Boolean>()

    /**
     * Номер «эпохи сети». Растёт при каждой смене сети; ссылка, которую
     * начали добывать при прежней, в запас уже не попадает — она привязана
     * к адресу, которого у телефона больше нет.
     */
    private val epoch = AtomicInteger(0)

    /**
     * Ссылка для проигрывания. Зовётся из потока загрузки ExoPlayer,
     * поэтому может блокировать.
     *
     * Если эту же ссылку прямо сейчас тянет упреждающая загрузка —
     * дожидаемся её, а не запускаем yt-dlp вторым процессом на тот же ролик.
     */
    fun blocking(context: Context, videoId: String): String {
        fresh(videoId)?.let {
            Log.i(TAG, "$videoId — ссылка была готова заранее")
            return it
        }
        running[videoId]?.let { pending ->
            val fromPrefetch = runCatching { runBlocking { pending.await() } }.getOrNull()
            // Пока ждали, сеть могла смениться — тогда дождались мы уже
            // негодной ссылки, и в запасе её нет. Добываем сами
            if (fromPrefetch != null && fresh(videoId) != null) {
                Log.i(TAG, "$videoId — дождались упреждающей загрузки")
                return fromPrefetch
            }
        }
        // Пока ждали, работа могла и закончиться
        fresh(videoId)?.let { return it }
        return resolve(context, videoId)
    }

    /**
     * Добыть ссылки заранее. Уже готовые, уже добываемые и скачанные в кэш
     * пропускаются: первым двум работа не нужна, третьи играют с диска.
     *
     * Возврата не ждём — это фоновая подготовка, а не обязательство.
     */
    fun warm(context: Context, videoIds: List<String>) {
        val app = context.applicationContext
        val wanted = videoIds.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return
        worker.launch {
            for (id in wanted) {
                if (fresh(id) != null || running.containsKey(id)) continue
                if (Cache.find(app, id) != null) continue
                val slot = CompletableDeferred<String>()
                running[id] = slot
                try {
                    slot.complete(resolve(app, id))
                } catch (t: Throwable) {
                    // Причину resolve уже записал; здесь важно лишь не уронить очередь
                    slot.completeExceptionally(t)
                } finally {
                    running.remove(id)
                }
            }
        }
    }

    /**
     * Ссылку другим путём — быстрым вместо обычного или наоборот.
     *
     * Зовётся, когда YouTube отверг и свежую ссылку: значит, дело не в
     * её возрасте, а в клиенте, который её выдал. Бывает это с отдельными
     * роликами: «Lou Bliss — Killing Butterflies» 2026-10-18 обычным путём
     * давал 403 раз за разом, а visionos — рабочую ссылку; у других роликов
     * бывает наоборот. Раньше обычный путь включался, только если быстрый
     * не выдавал ссылки вовсе, — а выдавал он исправно, просто мёртвую, и
     * трек не играл даже после перезапуска.
     *
     * Сработавший путь запоминается для ролика — и упреждающая добыча, и
     * следующее открытие сразу идут им.
     */
    fun otherWay(context: Context, videoId: String): String {
        val fast = !(lastFast[videoId] ?: Engine.fastClient)
        Diag.w("$videoId — отвергли и свежую ссылку, беру ${if (fast) "быстрым" else "обычным"} путём")
        prefer[videoId] = fast
        ready.remove(videoId)
        return resolve(context, videoId)
    }

    /** Забыть ссылку молча. */
    fun forget(videoId: String) {
        ready.remove(videoId)
    }

    /**
     * Ссылку не приняли. Выбрасываем её и записываем, что это была за
     * ссылка: сколько ей было и откуда она, — по этим строкам потом и
     * видно, протухание это, смена сети или что-то третье.
     */
    fun reject(videoId: String, code: Int) {
        val entry = ready.remove(videoId)
        val what = if (entry == null) {
            "в запасе её уже не было"
        } else {
            "ей ${(System.currentTimeMillis() - entry.at) / 1000} с, ${entry.note}"
        }
        Diag.w("$videoId — ссылку не приняли, ответ $code: $what")
    }

    /** Сеть сменилась — всё припасённое привязано к прежнему адресу. */
    fun clear(reason: String) {
        epoch.incrementAndGet()
        val dropped = ready.size
        ready.clear()
        Diag.i("$reason — выброшено припасённых ссылок: $dropped")
    }

    // ── Внутреннее ───────────────────────────────────────────────────────────

    private fun fresh(videoId: String): String? {
        val entry = ready[videoId] ?: return null
        if (System.currentTimeMillis() > entry.until) {
            ready.remove(videoId)
            return null
        }
        return entry.url
    }

    /** Собственно работа yt-dlp. Бросает IOException с его текстом. */
    private fun resolve(context: Context, videoId: String): String {
        val born = epoch.get()
        val started = SystemClock.elapsedRealtime()
        // jar — файл со входом в YouTube (см. YtLogin); с ним только обычный
        // путь: visionos cookies не принимает
        val extract = { fast: Boolean, jar: File? ->
            lastFast[videoId] = fast && jar == null
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("--no-playlist")
                addOption(Engine.IPV4)
                // Предупреждения (ffmpeg нет, «No title found» у visionos)
                // заслоняли в тексте ошибки саму причину
                addOption("--no-warnings")
                if (jar != null) addOption("--cookies", jar.absolutePath)
                else if (fast) addOption("--extractor-args", Engine.FAST_CLIENT)
            }
            Engine.extracting { YoutubeDL.getInstance().getInfo(request) }
        }
        val info = try {
            val jar = YtLogin.jarIfNeeded(context)
            try {
                when {
                    jar != null -> extract(false, jar)
                    // Ролику нужен свой путь — им и идём, без запасного
                    prefer[videoId] != null -> extract(prefer.getValue(videoId), null)
                    else -> Engine.fastFirst(videoId) { extract(it, null) }
                }
            } catch (t: Throwable) {
                if (t is InterruptedException || t is java.util.concurrent.CancellationException) throw t
                if (jar != null || !YtLogin.isBotCheck(Diag.chain(t, 4000))) throw t
                // YouTube требует подтвердить, что не бот, — с этим адресом
                // без входа не пустит. Вход есть — сразу со входом; нет — подсказать
                val login = YtLogin.botCheck(context)
                if (login == null) {
                    YtLogin.ask(context)
                    throw t
                }
                Diag.w("$videoId — YouTube просит подтвердить, что не бот; беру со входом")
                extract(false, login)
            }
        } catch (t: Throwable) {
            val why = Diag.chain(t, 4000)
            Diag.w("$videoId — yt-dlp не отдал поток: ${Diag.cause(t)}")
            if (GONE.any { why.contains(it, ignoreCase = true) }) {
                throw TrackUnavailable(tr("ролик недоступен: {причина}", "причина" to t.message))
            }
            throw IOException("yt-dlp не отдал поток для $videoId: ${Diag.cause(t)}", t)
        }
        val url = info.url?.takeIf { it.isNotBlank() }
            ?: throw IOException("yt-dlp вернул ответ без ссылки для $videoId")

        val now = System.currentTimeMillis()
        val expires = expiryOf(url)
        val note = describe(url, expires, now)
        val seconds = (SystemClock.elapsedRealtime() - started) / 100 / 10.0
        Diag.i("$videoId — ссылка добыта за $seconds с: $note")

        // Сеть успела смениться, пока работал yt-dlp, — ссылка привязана
        // к прежнему адресу. Отдаём её просившему (вдруг адрес тот же),
        // но в запас не кладём
        if (born == epoch.get()) {
            val until = minOf(now + TTL_MS, (expires ?: Long.MAX_VALUE) - EXPIRY_MARGIN_MS)
            remember(videoId, Entry(url, now, until, note))
        }
        return url
    }

    /** Срок годности из самой ссылки, в миллисекундах. */
    private fun expiryOf(url: String): Long? = runCatching {
        Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1000)
    }.getOrNull()

    /**
     * Что о ссылке можно записать, не записывая саму ссылку: какой клиент
     * её выдал, к какому семейству адресов она привязана и сколько ей жить.
     * Самого адреса здесь нет — только «IPv4» или «IPv6».
     */
    private fun describe(url: String, expires: Long?, now: Long): String = runCatching {
        val uri = Uri.parse(url)
        val client = uri.getQueryParameter("c") ?: "?"
        val family = uri.getQueryParameter("ip")?.let { if (it.contains(':')) "IPv6" else "IPv4" }
            ?: "без привязки"
        val life = expires?.let { "годна ${(it - now) / 60_000} мин" } ?: "срок не указан"
        "клиент $client, привязка $family, $life"
    }.getOrDefault("разобрать не вышло")

    private fun remember(videoId: String, entry: Entry) {
        ready[videoId] = entry
        if (ready.size <= KEEP) return
        // Тесниться начали — выкидываем самую старую запись
        ready.entries.minByOrNull { it.value.at }?.let { ready.remove(it.key) }
    }
}
