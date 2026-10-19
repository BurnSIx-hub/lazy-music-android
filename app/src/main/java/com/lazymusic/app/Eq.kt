package com.lazymusic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Эквалайзер — тот же, что у Lazy Music на ПК: десять полос по октаве от
 * 31 Гц до 16 кГц, крайние — полками, запас громкости и ограничитель.
 * Коэффициенты считаются по тем же формулам, что у фильтров Web Audio,
 * поэтому одна и та же настройка звучит на обоих одинаково.
 *
 * Звук проходит через [Processor] внутри плеера (см. [PlaybackService]);
 * та же цепочка ([Chain]) обрабатывает трек при сохранении в файл.
 * Выключенный эквалайзер звук не трогает вовсе.
 */
@UnstableApi
object Eq {

    val BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val LABELS = listOf("31", "62", "125", "250", "500", trKey("1к"), trKey("2к"), trKey("4к"), trKey("8к"), trKey("16к"))

    /** Предел полосы, дБ. */
    const val LIMIT = 12

    /** Ширина полосы — октава. */
    private const val Q = 1.41

    /**
     * Сколько от самого сильного подъёма убирать запасом. Половина: при 0,7
     * «Больше баса» звучало скорее тише, чем басовитее. Остальное ловит
     * ограничитель — он включён, только пока включён эквалайзер.
     */
    private const val HEADROOM = 0.5

    class Preset(val key: String, val name: String, val gains: IntArray)

    val PRESETS = listOf(
        Preset("flat", trKey("Обычный"), intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        Preset("bass", trKey("Больше баса"), intArrayOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)),
        Preset("lessbass", trKey("Меньше баса"), intArrayOf(-6, -5, -4, -2, 0, 0, 0, 0, 0, 0)),
        Preset("vocal", trKey("Голос"), intArrayOf(-2, -2, -1, 1, 3, 4, 3, 1, 0, -1)),
        Preset("treble", trKey("Больше высоких"), intArrayOf(0, 0, 0, 0, 0, 1, 2, 4, 5, 6)),
        Preset("rock", trKey("Рок"), intArrayOf(5, 4, 2, -1, -2, -1, 2, 4, 5, 5)),
        Preset("electro", trKey("Электроника"), intArrayOf(5, 4, 1, 0, -2, 1, 0, 2, 4, 5)),
        Preset("classic", trKey("Классика"), intArrayOf(4, 3, 2, 1, -1, -1, 0, 2, 3, 4)),
        Preset("acoustic", trKey("Акустика"), intArrayOf(3, 3, 2, 1, 1, 1, 2, 2, 2, 1)),
    )

    /** Что применено: полосы в дБ или null — эквалайзер выключен. */
    class Params(val gains: DoubleArray?)

    var on by mutableStateOf(false); private set
    var preset by mutableStateOf("flat"); private set
    var gains by mutableStateOf(IntArray(BANDS.size)); private set

    /** Снимок для звукового потока: подменяется целиком, читается без блокировок. */
    @Volatile
    var params = Params(null)
        private set

    fun load() {
        val saved = Settings.equalizer ?: return apply()
        val arr = saved.optJSONArray("gains")
        if (arr != null && arr.length() == BANDS.size) {
            gains = IntArray(BANDS.size) { arr.optInt(it).coerceIn(-LIMIT, LIMIT) }
            preset = saved.optString("preset", "custom")
            on = saved.optBoolean("on")
        }
        apply()
    }

    private fun apply() {
        params = Params(if (on) DoubleArray(BANDS.size) { gains[it].toDouble() } else null)
    }

    private fun save() {
        Settings.equalizer = JSONObject().put("on", on).put("preset", preset).put("gains", JSONArray(gains.toList()))
    }

    fun switch(value: Boolean) {
        on = value
        apply(); save()
    }

    fun choose(key: String) {
        val p = PRESETS.firstOrNull { it.key == key } ?: return
        gains = p.gains.copyOf()
        preset = key
        on = true
        apply(); save()
    }

    /** Сдвинули полосу — это уже своя настройка, если не совпала с готовой. */
    fun setBand(i: Int, db: Int) {
        val next = gains.copyOf()
        next[i] = db.coerceIn(-LIMIT, LIMIT)
        gains = next
        preset = PRESETS.firstOrNull { it.gains.contentEquals(next) }?.key ?: "custom"
        on = true
        apply(); save()
    }

    // ── Фильтры ──────────────────────────────────────────────────────────────

    /** Коэффициенты одного биквада, уже делённые на a0. */
    private class Coef(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /**
     * Фильтр полосы `i` — формулы Web Audio (Audio EQ Cookbook): крайние —
     * полки с наклоном 1, остальные — колокол с добротностью [Q].
     */
    private fun coef(i: Int, gain: Double, rate: Int): Coef {
        val a = 10.0.pow(gain / 40)
        val w0 = 2 * PI * BANDS[i] / rate
        val cw = cos(w0)
        val sw = sin(w0)
        return when (i) {
            0, BANDS.size - 1 -> {
                val alpha = sw / 2 * sqrt(2.0)
                val k = 2 * sqrt(a) * alpha
                if (i == 0) {
                    val a0 = (a + 1) + (a - 1) * cw + k
                    Coef(
                        a * ((a + 1) - (a - 1) * cw + k) / a0,
                        2 * a * ((a - 1) - (a + 1) * cw) / a0,
                        a * ((a + 1) - (a - 1) * cw - k) / a0,
                        -2 * ((a - 1) + (a + 1) * cw) / a0,
                        ((a + 1) + (a - 1) * cw - k) / a0
                    )
                } else {
                    val a0 = (a + 1) - (a - 1) * cw + k
                    Coef(
                        a * ((a + 1) + (a - 1) * cw + k) / a0,
                        -2 * a * ((a - 1) + (a + 1) * cw) / a0,
                        a * ((a + 1) + (a - 1) * cw - k) / a0,
                        2 * ((a - 1) - (a + 1) * cw) / a0,
                        ((a + 1) - (a - 1) * cw - k) / a0
                    )
                }
            }
            else -> {
                val alpha = sw / (2 * Q)
                val a0 = 1 + alpha / a
                Coef((1 + alpha * a) / a0, -2 * cw / a0, (1 - alpha * a) / a0, -2 * cw / a0, (1 - alpha / a) / a0)
            }
        }
    }

    private fun headroom(gains: DoubleArray) = 10.0.pow(-max(0.0, gains.maxOrNull() ?: 0.0) * HEADROOM / 20)

    /**
     * Итоговая кривая в дБ на частотах `freqs` — для рисунка в шторке.
     * Считается по формуле, без звука.
     */
    fun response(freqs: DoubleArray, rate: Int = 44_100): DoubleArray {
        val g = DoubleArray(BANDS.size) { gains[it].toDouble() }
        val pre = 20 * log10(headroom(g))
        val coefs = BANDS.indices.map { coef(it, g[it], rate) }
        return DoubleArray(freqs.size) { k ->
            val w = 2 * PI * freqs[k] / rate
            var db = pre
            for (c in coefs) {
                // |H(e^jw)|² для биквада
                val c1 = cos(w); val c2 = cos(2 * w); val s1 = sin(w); val s2 = sin(2 * w)
                val nr = c.b0 + c.b1 * c1 + c.b2 * c2
                val ni = -(c.b1 * s1 + c.b2 * s2)
                val dr = 1 + c.a1 * c1 + c.a2 * c2
                val di = -(c.a1 * s1 + c.a2 * s2)
                db += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
            }
            db
        }
    }

    // ── Цепочка ──────────────────────────────────────────────────────────────

    /**
     * Обработка от входа до ограничителя — одна и та же для игры и для
     * сохранения. Звук — float, каналы вперемешку. Ограничитель — как
     * у ПК: порог −2 дБ, степень 12, атака 3 мс, отпускание 250 мс.
     */
    class Chain(private val rate: Int, private val channels: Int) {
        private var applied: Params? = null
        // Коэффициенты полос плоскими массивами: в тесном цикле без объектов
        private val k0 = DoubleArray(BANDS.size)
        private val k1 = DoubleArray(BANDS.size)
        private val k2 = DoubleArray(BANDS.size)
        private val m1 = DoubleArray(BANDS.size)
        private val m2 = DoubleArray(BANDS.size)
        private var pre = 1.0
        /** Память фильтров: [полоса × канал]. */
        private val s1 = DoubleArray(BANDS.size * channels)
        private val s2 = DoubleArray(BANDS.size * channels)
        private var env = 1.0
        private val attack = exp(-1.0 / (0.003 * rate))
        private val release = exp(-1.0 / (0.25 * rate))
        private val threshold = 10.0.pow(-2.0 / 20)

        fun reset() {
            s1.fill(0.0)
            s2.fill(0.0)
            env = 1.0
        }

        /** Обрабатывает `frames` кадров в `buf` на месте. @return включено ли что-то */
        fun process(buf: FloatArray, frames: Int, params: Params): Boolean {
            val g = params.gains ?: return false
            if (params !== applied) {
                for (b in BANDS.indices) {
                    val c = coef(b, g[b], rate)
                    k0[b] = c.b0; k1[b] = c.b1; k2[b] = c.b2; m1[b] = c.a1; m2[b] = c.a2
                }
                pre = headroom(g)
                applied = params
            }
            val end = frames * channels
            // Полоса за полосой по всему куску: прямая форма II транспонированная
            for (ch in 0 until channels) {
                for (b in BANDS.indices) {
                    val b0 = k0[b]; val b1 = k1[b]; val b2 = k2[b]; val a1 = m1[b]; val a2 = m2[b]
                    val at = b * channels + ch
                    var z1 = s1[at]
                    var z2 = s2[at]
                    var i = ch
                    while (i < end) {
                        val x = buf[i].toDouble()
                        val y = b0 * x + z1
                        z1 = b1 * x - a1 * y + z2
                        z2 = b2 * x - a2 * y
                        buf[i] = y.toFloat()
                        i += channels
                    }
                    s1[at] = z1
                    s2[at] = z2
                }
            }
            for (f in 0 until frames) {
                var peak = 0.0
                val base = f * channels
                for (ch in 0 until channels) {
                    val x = buf[base + ch] * pre
                    buf[base + ch] = x.toFloat()
                    peak = max(peak, abs(x))
                }
                // Нужное усиление для этого кадра и сглаживание: быстро вниз, медленно вверх
                // Выше порога — степень 12: остаётся 1/12 превышения в децибелах
                val want = if (peak > threshold) (threshold / peak).pow(11.0 / 12) else 1.0
                env = if (want < env) attack * env + (1 - attack) * want else release * env + (1 - release) * want
                if (env < 1.0) for (ch in 0 until channels) buf[base + ch] = (buf[base + ch] * env).toFloat()
            }
            return true
        }
    }

    // ── Спектр ───────────────────────────────────────────────────────────────

    private const val TAP = 4096
    private val tap = FloatArray(TAP)
    private var tapAt = 0
    @Volatile private var tapRate = 44_100

    /** Последние отсчёты, что ушли в динамик (моно), — для живого спектра. */
    private fun feed(buf: FloatArray, frames: Int, channels: Int, rate: Int) = synchronized(tap) {
        tapRate = rate
        for (f in 0 until frames) {
            var s = 0f
            for (ch in 0 until channels) s += buf[f * channels + ch]
            tap[tapAt] = s / channels
            tapAt = (tapAt + 1) % TAP
        }
    }

    /**
     * Уровень того, что звучит, в дБ на частотах `freqs` — окно Ханна на
     * 2048 отсчётов и быстрое преобразование Фурье.
     */
    fun spectrum(freqs: DoubleArray): DoubleArray {
        val n = 2048
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val rate: Int
        synchronized(tap) {
            rate = tapRate
            for (i in 0 until n) {
                val w = 0.5 - 0.5 * cos(2 * PI * i / n)
                re[i] = tap[(tapAt - n + i + TAP) % TAP] * w
            }
        }
        fft(re, im)
        return DoubleArray(freqs.size) { k ->
            val bin = (freqs[k] * n / rate).toInt().coerceIn(1, n / 2 - 1)
            val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin]) / (n / 4)
            20 * log10(mag + 1e-9)
        }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ── Звено плеера ─────────────────────────────────────────────────────────

    /**
     * Звено в цепочке звука ExoPlayer. Всегда включено: включать и
     * выключать звено на ходу плеер не умеет, поэтому выключенный
     * эквалайзер — это просто копия звука без изменений.
     */
    class Processor : BaseAudioProcessor() {
        private var chain: Chain? = null
        private var work = FloatArray(0)
        private var shorts = ShortArray(0)

        override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT) {
                throw AudioProcessor.UnhandledAudioFormatException(input)
            }
            chain = Chain(input.sampleRate, input.channelCount)
            return input
        }

        override fun queueInput(input: ByteBuffer) {
            val fmt = inputAudioFormat
            val size = input.remaining()
            if (size == 0) return
            val float = fmt.encoding == C.ENCODING_PCM_FLOAT
            val samples = size / (if (float) 4 else 2)
            if (work.size < samples) work = FloatArray(samples)
            val src = input.order(ByteOrder.nativeOrder())
            if (float) {
                src.asFloatBuffer().get(work, 0, samples)
            } else {
                if (shorts.size < samples) shorts = ShortArray(samples)
                src.asShortBuffer().get(shorts, 0, samples)
                for (i in 0 until samples) work[i] = shorts[i] / 32768f
            }
            input.position(input.position() + size)

            val frames = samples / fmt.channelCount
            chain?.process(work, frames, params)
            feed(work, frames, fmt.channelCount, fmt.sampleRate)

            val out = replaceOutputBuffer(size)
            if (float) {
                out.asFloatBuffer().put(work, 0, samples)
            } else {
                for (i in 0 until samples) shorts[i] = (work[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                out.asShortBuffer().put(shorts, 0, samples)
            }
            out.position(size)
            out.flip()
        }

        override fun onFlush() {
            chain?.reset()
        }

        override fun onReset() {
            chain = null
        }
    }
}
