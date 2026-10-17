package com.lazymusic.app

import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Раздача своей библиотеки компьютеру.
 *
 * Телефон — источник: плейлисты собираются здесь, а модуль Foundry их
 * забирает. Поэтому сервер стоит на телефоне, а не наоборот.
 *
 * **Почему не через помощника на ПК.** Помощник модуля слушает только
 * `127.0.0.1` — намеренно: через него можно запустить yt-dlp и стереть кэш.
 * Выставлять это в Wi-Fi ради переноса плейлистов — плохая сделка. Здесь же
 * наружу смотрит один-единственный маршрут со списком названий: худшее, что
 * с ним можно сделать, — узнать, что человек слушает.
 *
 * И всё-таки раздача выключена по умолчанию и закрыта кодом: Wi-Fi бывает
 * не только домашний, а в кафе соседям по сети знать нечего.
 *
 * **Телефон должен не спать.** Когда экран гаснет, Android замораживает
 * приложение целиком: сокет остаётся открытым и виден в `/proc` как
 * `LISTEN`, но отвечать некому — соединение просто не устанавливается.
 * Замерено на S23: молчит уже через полминуты после гашения экрана,
 * и оживает в тот же миг, как приложение открывают.
 *
 * Вылечить это можно только службой переднего плана, то есть вечным
 * значком в шторке ради дела на две секунды в неделю. Пока что честнее
 * сказать правду в настройках: раздача работает, пока приложение открыто
 * или играет музыка — во втором случае процесс держит служба
 * проигрывания.
 */
object Share {

    private const val TAG = "LazyMusic"

    /** Порт раздачи. У помощника на ПК свой, 8766, — чтобы не путались. */
    const val PORT = 8780

    /**
     * Заголовок с кодом доступа.
     *
     * Именно заголовок, а не `?token=`: адрес оседает в журналах и в истории,
     * а код — секрет, пусть и маленький.
     */
    private const val TOKEN_HEADER = "x-lazy-token"

    /** Формат выдачи. Модуль сверяет и ругается, если версия ему незнакома. */
    private const val WIRE = 1

    /** Конец строки в HTTP — возврат каретки и перевод, именно в таком виде. */
    private val CRLF = "${13.toChar()}${10.toChar()}"

    var running by mutableStateOf(false)
        private set

    private var socket: ServerSocket? = null

    // ── Включение ────────────────────────────────────────────────────────────

    /** @return false, если порт занят или сеть не дала его открыть. */
    fun start(): Boolean {
        if (socket != null) return true
        return try {
            // reuseAddress до привязки, иначе перезапуск после падения
            // упрётся в порт, ещё висящий в TIME_WAIT
            val fresh = ServerSocket()
            fresh.reuseAddress = true
            fresh.bind(InetSocketAddress(PORT))
            socket = fresh
            running = true
            thread(isDaemon = true, name = "lazy-share") { serve(fresh) }
            Log.i(TAG, "раздача включена на ${address() ?: "?"}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "раздачу не поднять: ${t.message}")
            running = false
            false
        }
    }

    fun stop() {
        // Закрытие сокета роняет accept() — на нём цикл и выходит
        runCatching { socket?.close() }
        socket = null
        running = false
    }

    /**
     * Адрес, который человек перенесёт в модуль, — вида `192.168.1.42:8780`.
     * Пусто, когда телефон не в локальной сети.
     */
    fun address(): String? = localIp()?.let { "$it:$PORT" }

    // ── Сервер ───────────────────────────────────────────────────────────────

    private fun serve(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (t: Throwable) {
                break // сокет закрыли — это и есть выключение
            }
            try {
                client.soTimeout = 5_000
                handle(client)
            } catch (t: Throwable) {
                Log.w(TAG, "запрос не обслужен: ${t.message}")
            } finally {
                runCatching { client.close() }
            }
        }
        // Цикл кончился — приводим состояние в порядок, иначе следующий
        // start() увидит живой сокет и не станет ничего поднимать.
        // Сверяем, тот ли это сокет: раздачу могли успеть включить заново
        if (socket === server) stop() else runCatching { server.close() }
    }

    private fun handle(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        val request = reader.readLine() ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        var token = ""
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.take(colon).trim().lowercase() == TOKEN_HEADER) {
                token = line.substring(colon + 1).trim()
            }
        }

        val out = client.getOutputStream()

        // Браузер модуля спрашивает разрешения до самого запроса: заголовок
        // с кодом для него «непростой», и он шлёт OPTIONS вперёд
        if (method == "OPTIONS") return reply(out, 204, null)
        if (method != "GET") return reply(out, 405, JSONObject().put("error", "method"))

        when (path) {
            // Отзыв на оклик. Кода не просит: по нему модуль ищет телефон в
            // сети, а кода на этом шаге у него может ещё не быть. Ничего,
            // кроме «здесь живёт Lazy Music», не сообщает
            "/ping" ->
                reply(out, 200, JSONObject().put("app", "lazy-music").put("version", WIRE))

            "/library" ->
                if (token != Settings.shareToken) {
                    Log.w(TAG, "раздача: отказ, код не подошёл")
                    reply(out, 403, JSONObject().put("error", "token"))
                } else {
                    reply(out, 200, library())
                    Log.i(TAG, "раздача: библиотека отдана")
                }

            else -> reply(out, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun reply(out: OutputStream, status: Int, body: JSONObject?) {
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = listOf(
            "HTTP/1.1 $status ${reason(status)}",
            "Content-Type: application/json; charset=utf-8",
            "Content-Length: ${bytes.size}",
            // Окно модуля живёт на другом адресе (localhost:30000), и без
            // этих трёх строк браузер молча выбросит ответ
            "Access-Control-Allow-Origin: *",
            "Access-Control-Allow-Headers: $TOKEN_HEADER",
            "Access-Control-Max-Age: 600",
            "Cache-Control: no-store",
            "Connection: close"
        ).joinToString(CRLF, postfix = CRLF + CRLF)
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "OK"
    }

    // ── Что отдаём ───────────────────────────────────────────────────────────

    /**
     * Снимок библиотеки.
     *
     * Формат описан здесь, а не взят из [Settings]: это соглашение с чужой
     * программой, и оно не должно меняться оттого, что мы переложили что-то
     * в своём хранилище.
     *
     * Названия уходят уже переименованными — [Settings.playlist] подставляет
     * свои при чтении. Карта `names` идёт следом отдельно, чтобы модуль знал:
     * это переименование, а не настоящее имя ролика.
     */
    private fun library(): JSONObject {
        val lists = JSONArray()
        for (name in Settings.playlistNames()) {
            val tracks = JSONArray()
            for (t in Settings.playlist(name)) {
                tracks.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("title", t.title)
                        .put("artist", t.artist)
                        .put("art", t.artworkUrl.orEmpty())
                )
            }
            lists.put(JSONObject().put("name", name).put("tracks", tracks))
        }
        return JSONObject()
            .put("app", "lazy-music")
            .put("version", WIRE)
            .put("at", System.currentTimeMillis())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("playlists", lists)
            .put("names", Settings.renamesJson())
    }

    // ── Адрес в локальной сети ───────────────────────────────────────────────

    /**
     * Первый домашний адрес IPv4. Wi-Fi предпочитаем мобильной сети: через
     * мобильную компьютер до телефона всё равно не достучится, а адрес
     * оператора бывает тоже из частного диапазона и сбивает с толку.
     */
    fun localIp(): String? = localIps().firstOrNull()

    /**
     * Все домашние адреса IPv4, Wi-Fi первым. Сюда же попадает точка
     * доступа, которую раздаёт сам телефон (`swlan0` у Samsung): ноутбук,
     * сидящий на ней, — тоже «в одной сети». Мобильная сеть выброшена:
     * её адрес оператора бывает из частного диапазона (`10.x`) и уводит
     * поиск компьютера не туда — так и вышло 2026-10-14.
     */
    fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filterNot { MOBILE.any { prefix -> it.name.startsWith(prefix) } }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /** Имена интерфейсов мобильной сети у разных производителей. */
    private val MOBILE = listOf("rmnet", "ccmni", "v4-rmnet", "seth", "pdp", "dummy")
}
