package com.lazymusic.app

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Сводка библиотеки с Lazy Music на компьютере.
 *
 * Компьютер — центр: у него общая библиотека, и с ним же сводится модуль
 * Foundry. Телефон присылает свою, получает сведённую и заменяет ею свою
 * целиком. Как сводит центр — трёхсторонне, по последнему общему
 * состоянию, — описано в `hub.rs` приложения для ПК; здесь только
 * клиентская половина.
 *
 * **Ходит телефон, а не компьютер.** Раньше было наоборот ([Share]):
 * компьютер забирал, и телефон приходилось будить. Теперь телефон сам
 * сводит, когда может: при открытии, через три секунды после любой правки
 * и раз в пару минут, пока процесс жив (а при игре музыки он жив). Спит —
 * ну и пусть, сведётся, как проснётся.
 *
 * **Сопряжение — один раз.** Компьютер показывает шестизначный код, его
 * вводят здесь, и телефон получает постоянный ключ. Адрес компьютера
 * выдаёт роутер, и он меняется — тогда телефон сам перебирает свою
 * подсеть и узнаёт компьютер по его идентификатору.
 */
object Hub {

    const val PORT = 8781
    private const val WIRE = 2
    private const val TOKEN_HEADER = "x-lazy-token"

    private const val TIMEOUT_MS = 6_000
    /** Столько ждём отзыва при переборе адресов: 253 из 254 не ответят. */
    private const val SWEEP_TIMEOUT_MS = 1_200
    private const val SWEEP_BATCH = 64
    /** Пауза после правки: десять сердец подряд — одна сводка, а не десять. */
    private const val DEBOUNCE_MS = 3_000L
    private const val PERIOD_MS = 2 * 60_000L
    /** Чаще этого подсеть не перебираем — вне дома компьютера там нет. */
    private const val REDISCOVER_EVERY_MS = 10 * 60_000L

    /** Повод сводки «по кнопке» — только о ней говорим вслух, если не вышло. */
    const val MANUAL = "по кнопке"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private var pending: Job? = null
    private var ticker: Job? = null
    @Volatile private var lastRediscover = 0L

    var busy by mutableStateOf(false); private set
    var note by mutableStateOf<String?>(null); private set
    /** Растёт после каждой сводки, поменявшей библиотеку, — экрану пора перечитать. */
    var applied by mutableIntStateOf(0); private set
    var lastSync by mutableLongStateOf(0L); private set

    /**
     * Сопряжён ли. Наблюдаемое, а не чтение настроек: иначе раздел
     * «Компьютер» не перерисовывался после «Отвязать» и продолжал
     * предлагать отвязать уже отвязанное.
     */
    var paired by mutableStateOf(false); private set

    /** Компьютер, отозвавшийся в сети. */
    data class Pc(val address: String, val id: String, val name: String, val pairing: Boolean)

    fun start() {
        paired = Settings.hubToken.isNotBlank()
        lastSync = Settings.hubLastSync
        Settings.onLibraryChanged = { soon() }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(PERIOD_MS)
                if (paired) guarded("по часам")
            }
        }
    }

    /** Свести чуть погодя — правки идут пачками. */
    fun soon(delayMs: Long = DEBOUNCE_MS, reason: String = "после правки") {
        if (!paired) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            guarded(reason)
        }
    }

    /** Сводка, сбой которой виден в журнале, а не пропадает молча. */
    private suspend fun guarded(reason: String) {
        try {
            sync(reason)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Diag.w("сводка ($reason) сорвалась: ${Diag.chain(t)}")
        }
    }

    // ── Поиск компьютера ─────────────────────────────────────────────────────

    /**
     * Все компьютеры с Lazy Music в локальных сетях телефона: в его Wi-Fi
     * и в точке доступа, которую он раздаёт сам.
     */
    suspend fun find(): List<Pc> = withContext(Dispatchers.IO) {
        val prefixes = Share.localIps().map { it.substringBeforeLast('.') }.distinct()
        coroutineScope {
            prefixes.flatMap { prefix ->
                (1..254).chunked(SWEEP_BATCH).flatMap { batch ->
                    batch.map { i -> async { ping("$prefix.$i:$PORT", SWEEP_TIMEOUT_MS) } }.awaitAll()
                }
            }.filterNotNull().distinctBy { it.id.ifBlank { it.address } }
        }
    }

    private fun ping(address: String, timeout: Int): Pc? = runCatching {
        val (code, json) = call("GET", "http://$address/ping", null, null, timeout)
        if (code != 200 || json == null) return null
        if (json.optString("app") != "lazy-music" || json.optString("role") != "pc") return null
        Pc(address, json.optString("id"), json.optString("name"), json.optBoolean("pairing"))
    }.getOrNull()

    /** Ручной адрес — когда перебор не нашёл (другая подсеть, гостевая сеть). */
    suspend fun probe(input: String): Pc? = withContext(Dispatchers.IO) {
        var a = input.trim().removePrefix("http://").trimEnd('/')
        if (a.isEmpty()) return@withContext null
        if (!a.contains(':')) a = "$a:$PORT"
        ping(a, TIMEOUT_MS)
    }

    // ── Сопряжение ───────────────────────────────────────────────────────────

    /** @return null — сопряжено, иначе текст для человека. */
    suspend fun pair(pc: Pc, code: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("code", code.trim())
            .put("peer", Settings.hubPeer)
            .put("device", device())
        val (status, json) = try {
            call("POST", "http://${pc.address}/pair", body, null, TIMEOUT_MS)
        } catch (e: IOException) {
            return@withContext "Компьютер не отвечает: ${e.message}"
        }
        when (status) {
            200 -> {
                val token = json?.optString("token").orEmpty()
                if (token.length < 16) return@withContext "Компьютер ответил странно"
                Settings.hubAddress = pc.address
                Settings.hubId = json?.optString("id").orEmpty().ifBlank { pc.id }
                Settings.hubName = json?.optString("name").orEmpty().ifBlank { pc.name }
                Settings.hubToken = token
                Settings.hubApplied = 0
                paired = true
                note = null
                Diag.i("сопряжён с компьютером «${Settings.hubName}»")
                soon(0, "сопряжение")
                null
            }
            403 -> "Код не тот — сверьте с экраном компьютера"
            409 -> "На компьютере не открыто сопряжение — нажмите там «Сопрячь телефон»"
            410 -> "Код устарел — возьмите на компьютере новый"
            429 -> "Слишком много неверных попыток — начните сопряжение на компьютере заново"
            else -> "Компьютер ответил $status"
        }
    }

    fun unpair() {
        Settings.hubToken = ""
        Settings.hubApplied = 0
        paired = false
        note = null
        Diag.i("связь с компьютером снята")
    }

    // ── Сводка ───────────────────────────────────────────────────────────────

    /**
     * Одна сводка. Если, пока ждали ответа, библиотеку здесь поправили —
     * ответ не применяем (он затёр бы правку), а сводим ещё раз.
     */
    suspend fun sync(reason: String): Boolean = gate.withLock {
        if (!paired) return false
        busy = true
        try {
            repeat(2) {
                val revision = Settings.libraryRevision
                val body = JSONObject()
                    .put("peer", Settings.hubPeer)
                    .put("device", device())
                    .put("applied", Settings.hubApplied)
                    .put("library", Settings.libraryForSync())
                val (status, json) = post(body) ?: run {
                    if (reason == MANUAL) note = "Компьютер не отвечает — он включён и в той же сети Wi-Fi?"
                    return false
                }
                when (status) {
                    200 -> {
                        if (Settings.libraryRevision != revision) return@repeat
                        val lib = json?.optJSONObject("library") ?: return false
                        val changed = Settings.replaceLibrary(lib)
                        Settings.hubApplied = json.optLong("rev")
                        val now = System.currentTimeMillis()
                        Settings.hubLastSync = now
                        lastSync = now
                        note = null
                        if (changed) {
                            Diag.i("сводка с компьютером ($reason): библиотека обновлена")
                            // Сердце в уведомлении трогает плеер, а тот живёт
                            // только в главном потоке — из фона он бросает
                            // исключение, и экран не узнавал о сводке
                            withContext(Dispatchers.Main) {
                                Likes.load()
                                applied++
                            }
                        }
                        return true
                    }
                    403 -> {
                        Settings.hubToken = ""
                        paired = false
                        note = "Компьютер не узнал этот телефон — сопрягите заново"
                        Diag.w("сводка: компьютер отказал в ключе")
                        return false
                    }
                    else -> {
                        note = "Компьютер ответил $status"
                        return false
                    }
                }
            }
            false
        } finally {
            busy = false
        }
    }

    /**
     * Запрос сводки. Не достучались — возможно, роутер выдал компьютеру
     * другой адрес: ищем его в подсети по идентификатору и пробуем снова.
     */
    private suspend fun post(body: JSONObject): Pair<Int, JSONObject?>? {
        val headers = mapOf(TOKEN_HEADER to Settings.hubToken)
        try {
            return call("POST", "http://${Settings.hubAddress}/sync", body, headers, TIMEOUT_MS)
        } catch (e: IOException) {
            val now = System.currentTimeMillis()
            if (now - lastRediscover < REDISCOVER_EVERY_MS) return null
            lastRediscover = now
            val moved = find().firstOrNull { it.id == Settings.hubId } ?: return null
            Diag.i("компьютер нашёлся по новому адресу")
            Settings.hubAddress = moved.address
            return runCatching {
                call("POST", "http://${moved.address}/sync", body, headers, TIMEOUT_MS)
            }.getOrNull()
        }
    }

    private fun call(
        method: String,
        url: String,
        body: JSONObject?,
        headers: Map<String, String>?,
        timeout: Int
    ): Pair<Int, JSONObject?> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout
            readTimeout = timeout * 3
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code == 200 && json != null && json.optInt("version", WIRE) > WIRE) {
                throw IOException("приложение на компьютере новее — обновите и это")
            }
            return code to json
        } finally {
            conn.disconnect()
        }
    }

    /** Имя, которое увидит компьютер: «Galaxy S23», а не «SM-S911B». */
    private fun device(): String {
        val named = runCatching {
            android.provider.Settings.Global.getString(App.instance.contentResolver, "device_name")
        }.getOrNull()
        return named?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }
}
