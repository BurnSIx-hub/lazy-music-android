package com.lazymusic.app

import android.os.SystemClock
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Разбор первого запроса звука по шагам — для журнала.
 *
 * Заведён 2026-10-17: на мобильной сети примерно каждый четвёртый трек
 * начинался не за 4–5 с, а за 9–16: свежая ссылка то отвечала 403, то
 * молчала до таймаута, хотя проверочный запрос на байт за миг до того
 * проходил. Чтобы не гадать, где теряется время, каждый первый запрос
 * трека (`Range: bytes=0-` к серверу звука) разбирается по шагам:
 * соединение (IPv4 или IPv6, новое или из пула), шифрование, ответ. В
 * журнал идут только отказ, сбой и ответ дольше [SLOW_MS].
 *
 * Так и нашлись две причины. Первая — отказ 403 свежей ссылке при
 * быстром соединении, примерно у каждого десятого трека: та же ссылка не
 * оживает (семь попыток за 4 с), так что верный ход — сразу добыть новую,
 * как [YtDataSource] и делает. Вторая — молчание соединения из пула,
 * забытого оператором; лечится коротким сроком пула в [PlaybackService].
 *
 * Адресов и ссылок в журнале нет — только семейство адреса и времена.
 */
class NetTrace private constructor() : EventListener() {

    companion object {
        /** Дольше этого до ответа — уже стоит записи. */
        private const val SLOW_MS = 1_500L

        val factory = EventListener.Factory { call ->
            val r = call.request()
            if (r.url.host.endsWith("googlevideo.com") && r.header("Range") == "bytes=0-") NetTrace()
            else NONE
        }
    }

    private val t0 = SystemClock.elapsedRealtime()
    private val steps = StringBuilder()

    private fun mark(text: String) {
        if (steps.isNotEmpty()) steps.append(", ")
        steps.append(text).append(" +").append(SystemClock.elapsedRealtime() - t0).append(" мс")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        mark("соединение " + if (inetSocketAddress.address is Inet6Address) "IPv6" else "IPv4")

    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) = mark("TLS")

    override fun connectFailed(
        call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException
    ) = mark("соединение не вышло: ${ioe::class.simpleName}")

    override fun connectionAcquired(call: Call, connection: Connection) =
        mark("канал ${connection.protocol()}")

    override fun responseHeadersEnd(call: Call, response: Response) {
        mark("ответ ${response.code}")
        // Обычный случай — 206 за доли секунды — журнал не засоряет
        if (response.code >= 400 || SystemClock.elapsedRealtime() - t0 > SLOW_MS) {
            Diag.w("звук, первый запрос: $steps")
        }
    }

    override fun callFailed(call: Call, ioe: IOException) {
        mark("сбой ${ioe::class.simpleName}: ${ioe.message?.take(60)}")
        Diag.w("звук, первый запрос: $steps")
    }
}
