package com.lazymusic.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал, который переживает перезапуск и читается с компьютера.
 *
 * Системный журнал Android для этого не годится: он кольцевой, общий на
 * весь телефон, и строки приложения вымываются оттуда за минуты. Когда
 * пришла жалоба «постоянно вылазит 403», в нём нашлось четыре строки за
 * последние три минуты — и ни одной ошибки. Разбираться пришлось по коду
 * и догадкам, а не по фактам.
 *
 * Поэтому всё, что касается добычи звука, — отказы, повторы, смены сети,
 * обновления yt-dlp — пишется ещё и в файл:
 *
 * ```
 * adb shell cat /sdcard/Android/data/com.lazymusic.app/files/log/lazy-music.log
 * ```
 *
 * **Чего здесь нет намеренно:** ссылок на поток и адресов. В ссылке зашит
 * адрес телефона и подпись, по которой звук отдают; папка приложения
 * открыта для `adb` и резервных копий, и класть туда такое незачем. Для
 * разбора хватает того, что от ссылки остаётся: сколько ей минут, какой
 * клиент её выдал и к какому семейству адресов она привязана.
 */
object Diag {

    private const val TAG = "LazyMusic"

    /** Больше этого файл не растёт: старое уезжает в соседний, тот затирается. */
    private const val LIMIT = 256 * 1024L

    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun attach(context: Context) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "log")
        dir.mkdirs()
        file = File(dir, "lazy-music.log")
    }

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    /**
     * Суть отказа yt-dlp — строка «ERROR: …». Его текст — весь вывод в
     * ошибки, и предупреждения идут первыми: у знакомой 2026-10-18 экран
     * заняли «ffmpeg-location … does not exist» и «No title found», а сама
     * причина не влезла. Нет такой строки — вся цепочка, как раньше.
     */
    fun cause(error: Throwable, limit: Int = 300): String {
        val all = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        return all.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("ERROR:") }?.take(limit)
            ?: chain(error, limit)
    }

    /** Вся цепочка причин одной строкой: у ExoPlayer суть обычно на дне. */
    fun chain(error: Throwable, limit: Int = 400): String =
        generateSequence(error) { it.cause }
            .mapNotNull { e -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName }
            .distinct()
            .joinToString(" <- ")
            .take(limit)

    /**
     * Журнал одним файлом — отправить разработчику кнопкой в настройках
     * (просьба 2026-10-18: жалобы знакомой приходят снимками экрана, а
     * что было до них — не видно).
     *
     * Сверху — сводка: версии, телефон, есть ли ключ и вход, плейлисты
     * с числом треков. Ниже — оба файла журнала, старый и текущий. Файл
     * уходит из рук, поэтому вычищен ещё раз ([scrub]): ключ, адреса,
     * хвосты ссылок, почта и имя компьютера.
     */
    @Synchronized
    fun export(context: Context): File {
        val now = Date()
        val out = StringBuilder()
        val app = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        out.appendLine("Lazy Music ${app?.versionName ?: "?"} (${app?.versionCode ?: "?"})")
        out.appendLine("Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        out.appendLine("Выгружен ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
        out.appendLine("yt-dlp ${Engine.version ?: "встроенный"}")
        out.appendLine("Ключ API: ${if (Settings.apiKey.isBlank()) "нет" else "есть"}")
        out.appendLine("Вход в YouTube для yt-dlp: ${if (YtLogin.saved(context)) "есть" else "нет"}")
        out.appendLine("Компьютер: ${if (Settings.hubName.isBlank()) "не сопряжён" else "сопряжён"}")
        out.appendLine(
            "Без сети: ${Settings.offlineLists.size} пл., по мобильной — " +
                if (Settings.offlineOnMobile) "да" else "нет"
        )
        for ((name, done) in Offline.progress(context)) out.appendLine("  «$name» — скачано $done")
        out.appendLine("Плейлисты:")
        for (name in Settings.playlistNames()) {
            out.appendLine("  «$name» — ${Settings.playlist(name).size}")
        }
        val dir = file?.parentFile
        for (name in listOf("lazy-music.1.log", "lazy-music.log")) {
            val f = dir?.let { File(it, name) }?.takeIf { it.exists() } ?: continue
            out.appendLine().appendLine("── $name ──")
            runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { out.appendLine(it) }
        }
        val share = File(context.cacheDir, "logs").apply { mkdirs() }
        share.listFiles()?.forEach { it.delete() }
        val target = File(share, "lazy-music-log-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(now)}.txt")
        target.writeText(scrub(out.toString(), Settings.hubName))
        return target
    }

    private val URL = Regex("""(https?)://([^/\s"'<>?#]+)[^\s"'<>]*""")
    private val API_KEY = Regex("""AIza[0-9A-Za-z_\-]{20,}""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val IPV4 = Regex("""(?<![\d.])(\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    private val IPV6 = Regex("""(?<![\w:])([0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![\w:])""")

    /**
     * Убирает то, чему не место в отправленном файле. От ссылки остаётся
     * сервер: в пути и параметрах бывают ключ, адрес телефона и подпись.
     * Похожее на IPv6 трогаем, только если в нём есть буква или «::» —
     * иначе это время вроде 17:56:03.
     */
    fun scrub(text: String, hub: String = ""): String {
        var s = URL.replace(text) { "${it.groupValues[1]}://${it.groupValues[2]}/…" }
        s = API_KEY.replace(s, "‹ключ›")
        s = EMAIL.replace(s, "‹почта›")
        s = IPV4.replace(s, "‹адрес›")
        s = IPV6.replace(s) { m ->
            if (m.value.contains("::") || m.value.any { it.isLetter() }) "‹адрес›" else m.value
        }
        // Имя компьютера бывает именем человека
        hub.trim().takeIf { it.length >= 3 }?.let { s = s.replace(it, "‹компьютер›") }
        return s
    }

    @Synchronized
    private fun write(level: String, message: String) {
        if (level == "W") Log.w(TAG, message) else Log.i(TAG, message)
        val target = file ?: return
        runCatching {
            if (target.length() > LIMIT) {
                val old = File(target.parentFile, "lazy-music.1.log")
                old.delete()
                target.renameTo(old)
            }
            target.appendText("${stamp.format(Date())} $level $message" + System.lineSeparator())
        }
    }
}
