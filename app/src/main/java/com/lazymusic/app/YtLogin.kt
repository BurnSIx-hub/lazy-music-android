package com.lazymusic.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Вход в YouTube для yt-dlp — против «Sign in to confirm you're not a bot».
 *
 * Эту проверку YouTube устраивает подозрительным адресам, чаще всего
 * серверам VPN: у знакомой в России 2026-10-18 треки не играли, и смена
 * сервера не помогала. С входом в аккаунт её обычно нет — так советует и
 * сам yt-dlp в тексте ошибки.
 *
 * Вход — тот же, что во встроенном браузере [YtMusicActivity]: человек
 * входит там на странице Google, пароля приложение не видит. Отсюда cookies
 * этого браузера для youtube.com пишутся файлом в закрытую папку приложения
 * и отдаются yt-dlp ключом `--cookies` — только ему и только на этом
 * телефоне, по сети никуда.
 *
 * **Только когда проверка случилась** ([botCheck]), и потом [WINDOW_MS]:
 * со входом yt-dlp не может идти быстрым путём (visionos cookies не
 * принимает), трек запускается дольше, а аккаунт лишний раз не светится.
 * Риск всё же есть — Google может ограничить аккаунт, через который много
 * качают; пользователь о нём знает и решил так (2026-10-18).
 */
object YtLogin {

    private const val FILE = "yt-cookies.txt"

    /** Сколько после проверки на бота ходим со входом. */
    private const val WINDOW_MS = 6 * 60 * 60 * 1000L

    /** Не чаще этого напоминаем войти. */
    private const val ASK_EVERY_MS = 10 * 60 * 1000L

    /** По ним видно, что вход есть: без них cookies — просто посетителя. */
    private val AUTH = setOf("SAPISID", "__Secure-3PAPISID", "LOGIN_INFO")

    /** Проверка на бота в тексте ошибки. Апостроф бывает и типографский — без него. */
    private val BOT = listOf("not a bot", "sign in to confirm")

    /**
     * До какого времени ходим со входом. В настройках, а не только в памяти:
     * иначе после каждого перезапуска первая попытка шла без входа, упиралась
     * в ту же проверку и теряла секунд пять (журнал знакомой, 2026-10-19).
     */
    private var until: Long
        get() = Settings.ytLoginUntil
        set(value) { Settings.ytLoginUntil = value }
    @Volatile private var asked = 0L

    private val main = Handler(Looper.getMainLooper())

    fun isBotCheck(text: String) = BOT.any { text.contains(it, ignoreCase = true) }

    /** Файл для `--cookies`, если сейчас ходим со входом и вход есть; иначе null. */
    fun jarIfNeeded(context: Context): File? =
        if (System.currentTimeMillis() < until) jar(context) else null

    /**
     * YouTube потребовал проверку: ближайшие часы — со входом.
     * @return файл для `--cookies`; null — входа нет
     */
    fun botCheck(context: Context): File? {
        until = System.currentTimeMillis() + WINDOW_MS
        return jar(context)
    }

    /**
     * Файл со входом. Пишется один раз: дальше его ведёт сам yt-dlp — он
     * сохраняет туда обновлённые YouTube cookies, и подменять их старыми из
     * браузера не нужно. Входа в браузере нет — файла тоже нет.
     */
    @Synchronized
    fun jar(context: Context): File? {
        val file = File(context.filesDir, FILE)
        val cookies = browserCookies()
        if (cookies.none { it.first in AUTH }) {
            file.delete()
            return null
        }
        if (!file.exists()) {
            file.writeText(netscape(cookies))
            Diag.i("вход в YouTube передан yt-dlp: ${cookies.size} cookies")
        }
        return file
    }

    /** Передан ли вход yt-dlp — для сводки в журнале ([Diag.export]). */
    fun saved(context: Context) = File(context.filesDir, FILE).exists()

    /** Вышли из аккаунта во встроенном браузере — yt-dlp тоже забывает вход. */
    fun forget(context: Context) {
        File(context.filesDir, FILE).delete()
        until = 0L
    }

    /** Подсказать войти — плашкой с кнопкой, не чаще раза в [ASK_EVERY_MS]. */
    fun ask(context: Context) {
        val now = System.currentTimeMillis()
        if (now - asked < ASK_EVERY_MS) return
        asked = now
        val app = context.applicationContext
        main.post {
            lateinit var p: Plaque
            p = Plaques.notice(
                "Авторизуйтесь в YouTube, чтобы он убедился, что вы не бот",
                "Войдите в YouTube — это минимизирует шанс этой ошибки",
                listOf(
                    Plaque.Action("Закрыть", quiet = true) { p.close() },
                    Plaque.Action("Войти") {
                        p.close()
                        app.startActivity(
                            Intent(app, YtMusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            )
        }
    }

    // ── Внутреннее ───────────────────────────────────────────────────────────

    /**
     * Cookies браузера для youtube.com. Читаются на главном потоке: зовут
     * отсюда из потоков загрузки, а браузерные хранилища лучше трогать с
     * главного.
     */
    private fun browserCookies(): List<Pair<String, String>> {
        var raw: String? = null
        val read = { raw = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull() }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            read()
        } else {
            val done = CountDownLatch(1)
            main.post { read(); done.countDown() }
            done.await(3, TimeUnit.SECONDS)
        }
        return raw.orEmpty().split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }
    }

    /**
     * Формат cookies, который читает yt-dlp (Netscape). Браузер срок не
     * отдаёт — ставим месяц: дальше файл ведёт yt-dlp. Cookies с приставкой
     * `__Host-` живут только на своём домене, остальные — на всём youtube.com.
     */
    private fun netscape(cookies: List<Pair<String, String>>): String {
        val expires = System.currentTimeMillis() / 1000 + 30L * 24 * 60 * 60
        return buildString {
            append("# Netscape HTTP Cookie File\n")
            for ((name, value) in cookies) {
                val host = name.startsWith("__Host-")
                val domain = if (host) "www.youtube.com" else ".youtube.com"
                val sub = if (host) "FALSE" else "TRUE"
                append("$domain\t$sub\t/\tTRUE\t$expires\t$name\t$value\n")
            }
        }
    }
}
