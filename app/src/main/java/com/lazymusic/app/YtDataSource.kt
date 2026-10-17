package com.lazymusic.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Превращает `ytm:<videoId>` в то, что ExoPlayer умеет читать, — и не даёт
 * мёртвой ссылке остановить музыку.
 *
 * Сначала смотрит в [Cache] — скачанное играет из файла и работает офлайн.
 * Иначе берёт прямую ссылку у [StreamUrls]: она либо уже добыта заранее,
 * пока играл предыдущий трек, либо добывается прямо сейчас.
 *
 * Почему очередь хранит идентификаторы, а не ссылки: ссылки YouTube живут
 * считанные часы. Очередь из идентификаторов не протухает, а ссылки —
 * недолговечный кэш поверх неё.
 *
 * ## Отказ лечится здесь, а не в плеере
 *
 * Раньше тут стоял готовый `ResolvingDataSource`: подставил ссылку — и всё.
 * Если YouTube отвечал на неё 403, ошибка уходила в плеер; тот по своему
 * обычаю пробовал ещё трижды — и каждый раз получал из запаса ту же самую
 * ссылку. Музыка вставала, а следом вставали и два следующих трека: их
 * ссылки были добыты заранее, при той же сети.
 *
 * Теперь отказ разбирается на месте, в три ступени:
 *
 * 1. что лежит в запасе — обычно ссылка, добытая загодя;
 * 2. отвергли — выбрасываем и добываем заново, уже с нынешнего адреса.
 *    Этого хватает почти всегда: ссылка привязана к адресу телефона, а он
 *    сменился вместе с сетью;
 * 3. отвергли и свежую — значит, дело не в возрасте ссылки. Проверяем,
 *    нет ли нового yt-dlp, и если он обновился, пробуем в последний раз.
 *
 * Плеер при этом ничего не замечает: для него это один долгий `open`.
 * Позицию он передаёт в `dataSpec`, так что продолжение идёт с того же
 * места, а не с начала трека.
 *
 * Проверено настоящими отказами настоящего сервера: и на первом открытии
 * трека, и после перемотки на 69-ю минуту — оба раза музыка пошла сама.
 *
 * Вызывается из потока загрузки ExoPlayer, поэтому здесь можно блокировать —
 * и ждать разворачивания движка, и ходить в сеть.
 */
@UnstableApi
class YtDataSource private constructor(
    private val app: Context,
    private val upstream: DataSource
) : DataSource {

    class Factory(
        context: Context,
        private val upstream: DataSource.Factory
    ) : DataSource.Factory {
        private val app = context.applicationContext
        override fun createDataSource(): DataSource = YtDataSource(app, upstream.createDataSource())
    }

    companion object {
        const val SCHEME = "ytm"
        private const val TAG = "LazyMusic"

        /** Ответы, после которых ссылку надо добывать заново, а не повторять. */
        private val DEAD = setOf(403, 404, 410)

        /**
         * Дольше этого сервер обычно не отвечает. Медленные соединения
         * пишем в журнал и замеряем дорогу до узла: восемь секунд на первом
         * треке оказались таймаутом IPv6, и без записи их было бы не
         * отличить от долгой добычи ссылки.
         */
        private const val SLOW_OPEN_MS = 2_000L

        /** Сколько замер ждёт каждого соединения. */
        private const val PROBE_TIMEOUT_MS = 4_000

        /** Идентификатор из `ytm:<id>`. Берём schemeSpecificPart: host бы понизил регистр. */
        fun videoIdOf(uri: Uri): String? =
            if (uri.scheme == SCHEME) uri.schemeSpecificPart?.trim('/')?.takeIf { it.isNotEmpty() }
            else null
    }

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val videoId = videoIdOf(dataSpec.uri) ?: return upstream.open(dataSpec)

        Cache.find(app, videoId)?.let { file ->
            // Позиция не ноль — это дочитывание того же файла, а не новый трек
            if (dataSpec.position == 0L) {
                Log.i(TAG, "$videoId — играю из кэша, ${file.length() / 1024} КБ")
            }
            return upstream.open(dataSpec.withUri(Uri.fromFile(file)))
        }

        var updated = false
        var switched = false
        var other = false
        var attempt = 0
        while (true) {
            attempt++
            val url = try {
                if (other) {
                    other = false
                    StreamUrls.otherWay(app, videoId)
                } else StreamUrls.blocking(app, videoId)
            } catch (gone: TrackUnavailable) {
                throw gone
            } catch (e: IOException) {
                // yt-dlp не справился. Если он просто устарел — обновление это лечит
                if (updated || !Engine.refreshAfterFailure(app)) throw e
                updated = true
                continue
            }
            try {
                val started = SystemClock.elapsedRealtime()
                val length = upstream.open(withRange(dataSpec.withUri(Uri.parse(url))))
                val took = SystemClock.elapsedRealtime() - started
                if (took > SLOW_OPEN_MS) {
                    Diag.i("$videoId — сервер отвечал ${took / 100 / 10.0} с")
                    thread(name = "lazy-probe", isDaemon = true) {
                        Diag.i("$videoId — замер: ${probe(url)}")
                    }
                }
                return length
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode !in DEAD) throw e
                // Без close следующий open у DefaultDataSource упадёт:
                // он помнит, что уже открыт
                runCatching { upstream.close() }
                StreamUrls.reject(videoId, e.responseCode)
                // Замер идёт в стороне: он ждёт соединений и починку задерживать не должен
                thread(name = "lazy-probe", isDaemon = true) { Diag.i("$videoId — замер: ${probe(url)}") }
                if (attempt == 1) continue
                // Отвергли и только что добытую — возраст ссылки ни при чём,
                // дело в клиенте: пробуем другой путь (см. StreamUrls.otherWay)
                if (!switched) {
                    switched = true
                    other = true
                    continue
                }
                if (updated || !Engine.refreshAfterFailure(app)) throw e
                updated = true
            }
        }
    }

    /**
     * Запрос с начала трека — с явным `Range: bytes=0-`.
     *
     * Плеер с начала трека диапазона не шлёт, а без него YouTube отдаёт файл
     * со скоростью около 32 КБ/с — вдвое быстрее, чем трек играет: запас
     * копится медленно, и первые секунды ждут. С `bytes=0-` тот же файл
     * приходит целиком за секунду (замерено 2026-10-17, клиенты tv и
     * visionos одинаково). Ответ 206 вместо 200 плеер понимает сам.
     */
    private fun withRange(spec: DataSpec): DataSpec =
        if (spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong()) {
            spec.withAdditionalHeaders(mapOf("Range" to "bytes=0-"))
        } else spec

    /**
     * Что с дорогой до узла, отказавшего в ссылке: в каком порядке система
     * отдаёт его адреса и до какого семейства реально удаётся дозвониться.
     *
     * Этим замером была найдена вторая причина «403»: на мобильной сети до
     * серверов со звуком по IPv6 не дозвониться, и плеер, прождав таймаут,
     * приходил с IPv4 за ссылкой, привязанной к IPv6 (см. [Ipv4First]).
     * Оставлен на будущее: в другой сети расклад может оказаться иным,
     * и узнать об этом лучше из журнала, чем из жалобы.
     *
     * Имя узла в журнал не пишется — по нему видно, где находится телефон.
     * Вместо имени короткая метка: её хватает, чтобы понять, тот же это
     * узел или другой.
     */
    private fun probe(url: String): String = runCatching {
        val host = Uri.parse(url).host ?: return "узел не разобран"
        val all = InetAddress.getAllByName(host)
        val order = all.joinToString("") { if (it is Inet6Address) "6" else "4" }
        fun dial(address: InetAddress?): String {
            if (address == null) return "адреса нет"
            val started = SystemClock.elapsedRealtime()
            return try {
                Socket().use { it.connect(InetSocketAddress(address, 443), PROBE_TIMEOUT_MS) }
                "${SystemClock.elapsedRealtime() - started} мс"
            } catch (t: Throwable) {
                "не вышло за ${SystemClock.elapsedRealtime() - started} мс"
            }
        }
        val mark = (host.hashCode() and 0xfff).toString(16)
        "узел $mark, адреса по порядку $order, " +
            "IPv6 — ${dial(all.firstOrNull { it is Inet6Address })}, " +
            "IPv4 — ${dial(all.firstOrNull { it !is Inet6Address })}"
    }.getOrElse { "не удался: ${it.message}" }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
