package com.lazymusic.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * «Скачать изменённый трек» — сохранить трек так, как он сейчас звучит,
 * с эквалайзером, в «Музыка/Lazy Music». Как на ПК, только формат другой:
 * MP3 Android кодировать не умеет, а AAC (M4A) — умеет сам и слушается
 * любым плеером.
 *
 * Звук берётся из кэша, а нет — скачивается целиком, разбирается
 * штатным декодером, проходит ту же цепочку [Eq.Chain], что играет, и
 * сразу кодируется — без промежуточного файла с сырым звуком.
 */
@UnstableApi
object Export {

    private const val BITRATE = 192_000
    /** Сколько ждать буфер кодека. Ноль на входе: свободного нет — сначала забираем готовое. */
    private const val TIMEOUT_US = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Исполнитель — Название (эквалайзер).m4a */
    private fun fileName(track: Track, eq: Boolean): String {
        val artist = artistOf(track.artist)
        val base = if (artist.isNotBlank() && !track.title.lowercase().contains(artist.lowercase()))
            "$artist — ${track.title}" else track.title
        val name = base + if (eq) " (эквалайзер)" else ""
        return name.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim()
            .take(150).ifBlank { "трек" } + ".m4a"
    }

    fun start(context: Context, track: Track) {
        val app = context.applicationContext
        val params = Eq.params
        val name = fileName(track, params.gains != null)
        val plaque = Plaques.notice("Скачивание «${track.title}»…", "Беру звук")
        plaque.progress = 0f
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    val source = Cache.find(app, track.id) ?: fetch(app, track.id) { share ->
                        scope.launch { plaque.progress = share * 0.3f }
                    }
                    val t1 = System.currentTimeMillis()
                    try {
                        val out = File(app.cacheDir, "export.m4a")
                        transcode(source, out, params) { stage, share ->
                            scope.launch {
                                plaque.sub = stage
                                plaque.progress = 0.3f + share * 0.7f
                            }
                        }
                        val t2 = System.currentTimeMillis()
                        Diag.i("${track.id}: сохранение — звук ${(t1 - t0) / 1000.0} с, обработка ${(t2 - t1) / 1000.0} с")
                        publish(app, out, name, track)
                    } finally {
                        File(app.cacheDir, "export").deleteRecursively()
                    }
                }
                plaque.text = "Скачано"
                plaque.sub = name
                plaque.progress = null
                plaque.actions = listOf(
                    Plaque.Action("Закрыть", quiet = true) { plaque.close() },
                    Plaque.Action("Показать") { show(app, uri); plaque.close() }
                )
            } catch (t: Throwable) {
                Diag.w("${track.id}: трек не сохранился: ${Diag.chain(t)}")
                plaque.text = "Не скачано"
                plaque.sub = t.message ?: t::class.simpleName.orEmpty()
                plaque.progress = null
                plaque.actions = listOf(Plaque.Action("Закрыть") { plaque.close() })
            }
        }
    }

    /** По сколько качать звук за запрос. */
    private const val CHUNK = 2L shl 20

    /**
     * Звук целиком с YouTube — во временный файл, по той же ссылке, что
     * играет плеер, и кусками по 2 МБ. Одним запросом YouTube отдаёт файл
     * со скоростью игры (трек на 3 минуты качался 2 минуты), а yt-dlp на
     * телефоне иногда не получает формат вовсе — ссылка плеера надёжнее.
     */
    private fun fetch(context: Context, videoId: String, progress: (Float) -> Unit): File {
        val url = StreamUrls.blocking(context, videoId)
        val http = OkHttpClient.Builder().dns(Ipv4First)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val dir = File(context.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "source")
        var pos = 0L
        var total = -1L
        file.outputStream().use { out ->
            while (total < 0 || pos < total) {
                val req = Request.Builder().url(url).header("Range", "bytes=$pos-${pos + CHUNK - 1}").build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) throw IOException("звук не получен (${resp.code})")
                    if (total < 0) total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    val got = resp.body!!.byteStream().copyTo(out)
                    pos += got
                    // Сервер отдал всё сразу или больше нечего — конец
                    if (resp.code == 200 || got == 0L) total = pos
                }
                if (total > 0) progress(pos.toFloat() / total)
            }
        }
        return file
    }

    /**
     * Разобрать, прогнать через эквалайзер и закодировать в AAC.
     * `progress` — этап и доля 0…1.
     */
    private fun transcode(source: File, out: File, params: Eq.Params, progress: (String, Float) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(source.path)
        val ti = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
        } ?: throw IOException("в файле нет звука")
        ex.selectTrack(ti)
        val inFmt = ex.getTrackFormat(ti)
        val durationUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L

        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(inFmt, null, null, 0)
        dec.start()

        var enc: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxTrack = -1
        var chain: Eq.Chain? = null
        var rate = 44_100
        var channels = 2
        var floatPcm = false
        var framesFed = 0L
        var inEq = 0L
        var inEnc = 0L
        val began = System.nanoTime()
        val info = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false
        var told = -1
        progress("Обрабатываю", 0.02f)

        /** Забрать готовое у кодировщика и уложить в файл. */
        fun drainEncoder(untilEnd: Boolean) {
            val e = enc ?: return
            while (true) {
                val i = e.dequeueOutputBuffer(info, if (untilEnd) TIMEOUT_US else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer!!.addTrack(e.outputFormat)
                        muxer!!.start()
                    }
                    i >= 0 -> {
                        val buf = e.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxTrack >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer!!.writeSampleData(muxTrack, buf, info)
                        }
                        e.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                            return
                        }
                    }
                }
            }
        }

        /** Отдать кодировщику 16-битный звук; конец потока — `pcm == null`. */
        fun feedEncoder(pcm: ByteArray?) {
            val e = enc ?: return
            var at = 0
            while (true) {
                val i = e.dequeueInputBuffer(TIMEOUT_US)
                if (i < 0) {
                    drainEncoder(false)
                    continue
                }
                val buf = e.getInputBuffer(i)!!
                buf.clear()
                val pts = framesFed * 1_000_000L / rate
                if (pcm == null) {
                    e.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return
                }
                val n = minOf(buf.remaining() / (2 * channels) * (2 * channels), pcm.size - at)
                buf.put(pcm, at, n)
                e.queueInputBuffer(i, 0, n, pts, 0)
                framesFed += n / (2 * channels)
                at += n
                if (at >= pcm.size) return
            }
        }

        try {
            while (!decoderDone) {
                var moved = false
                while (!extractorDone) {
                    val i = dec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                    moved = true
                }
                while (!decoderDone) {
                    val o = dec.dequeueOutputBuffer(info, if (moved) 0 else TIMEOUT_US)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    moved = true
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = dec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (channels !in 1..2) throw IOException("каналов $channels — так не умею")
                            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            chain = Eq.Chain(rate, channels)
                            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                                val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
                                ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                                ef.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                                ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                                configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                                start()
                            }
                            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        }
                        o >= 0 -> {
                            val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val samples = info.size / (if (floatPcm) 4 else 2)
                            if (samples > 0) {
                                val f = FloatArray(samples)
                                if (floatPcm) buf.asFloatBuffer().get(f)
                                else {
                                    val sh = ShortArray(samples)
                                    buf.asShortBuffer().get(sh)
                                    for (k in 0 until samples) f[k] = sh[k] / 32768f
                                }
                                val tq = System.nanoTime()
                                chain?.process(f, samples / channels, params)
                                inEq += System.nanoTime() - tq
                                val sh = ShortArray(samples) { (f[it] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
                                val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                                pcm.asShortBuffer().put(sh)
                                val te = System.nanoTime()
                                feedEncoder(pcm.array())
                                drainEncoder(false)
                                inEnc += System.nanoTime() - te
                            }
                            dec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                            if (durationUs > 0) {
                                val share = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                val pct = (share * 100).toInt()
                                if (pct != told) {
                                    told = pct
                                    progress("Обрабатываю", 0.02f + share * 0.96f)
                                }
                            }
                        }
                    }
                }
            }
            Diag.i("сохранение: эквалайзер ${inEq / 1_000_000} мс, кодировщик ${inEnc / 1_000_000} мс, всего ${(System.nanoTime() - began) / 1_000_000} мс")
            if (enc == null) throw IOException("пустой файл")
            feedEncoder(null)
            while (!encoderDone) drainEncoder(true)
        } finally {
            runCatching { dec.stop() }; dec.release()
            enc?.let { runCatching { it.stop() }; it.release() }
            muxer?.let { runCatching { it.stop() }; it.release() }
            ex.release()
        }
        if (out.length() < 1024) throw IOException("пустой файл")
    }

    /** Готовый файл — в «Музыка/Lazy Music»: системный медиастор или папка приложения. */
    private fun publish(context: Context, file: File, name: String, track: Track): Uri {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Lazy Music")
                    put(MediaStore.Audio.Media.TITLE, track.title)
                    put(MediaStore.Audio.Media.ARTIST, artistOf(track.artist))
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Android не дал места для файла")
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            }
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Lazy Music").apply { mkdirs() }
            val target = File(dir, name)
            file.copyTo(target, overwrite = true)
            return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        } finally {
            file.delete()
        }
    }

    /** Открыть готовое в плеере, который выберет человек. */
    private fun show(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Plaques.toast("Нечем открыть файл") }
    }
}
