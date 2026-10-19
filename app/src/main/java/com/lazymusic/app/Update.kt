package com.lazymusic.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Обновление с GitHub — как у Lazy Music на ПК.
 *
 * Выпуски лежат в публичном репозитории без кода: там только README и
 * APK. Рядом с APK — `latest.json`: версия, что нового, адрес и SHA-256.
 * Проверка — через 20 секунд после открытия и не чаще раза в 6 часов,
 * найденное предлагается плашкой. Скачанный APK сверяется по SHA-256, а
 * подпись проверяет сама система: чужой APK поверх этого не встанет.
 *
 * Ставить приложения Android разрешает только с согласия человека —
 * «Установка неизвестных приложений» для Lazy Music. Его просим один раз,
 * при первом обновлении.
 */
object Update {

    const val REPO = "BurnSIx-hub/lazy-music-android"
    const val RELEASES = "https://github.com/$REPO/releases"
    private const val LATEST = "$RELEASES/latest/download/latest.json"
    private const val EVERY_MS = 6L * 60 * 60 * 1000
    private const val FIRST_DELAY_MS = 20_000L

    class Found(
        val version: String,
        val code: Long,
        val notes: String,
        /** То же по-английски; у выпусков до 1.12.0 его нет. */
        val notesEn: String,
        val url: String,
        val sha256: String,
        val size: Long
    )

    var found by mutableStateOf<Found?>(null); private set
    var checking by mutableStateOf(false); private set
    var installing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var checkedAt by mutableStateOf(0L); private set

    val current: String
        get() = runCatching {
            App.instance.packageManager.getPackageInfo(App.instance.packageName, 0).versionName
        }.getOrNull() ?: "?"

    val currentCode: Long
        get() = runCatching {
            val info = App.instance.packageManager.getPackageInfo(App.instance.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrDefault(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var plaque: Plaque? = null
    private var snoozed = ""
    private var started = false

    /** Ждём разрешения на установку — продолжить, когда человек вернётся. */
    private var waitingPermission = false

    /** При открытии приложения: проверить, если пора. */
    fun onStart(activity: Activity) {
        checkedAt = Settings.updateCheckedAt
        if (waitingPermission && activity.packageManager.canRequestPackageInstalls()) {
            waitingPermission = false
            install(activity)
            return
        }
        if (started) {
            found?.let { if (!installing) offer(activity) }
            return
        }
        started = true
        if (!Settings.checkUpdates) return
        scope.launch {
            delay(FIRST_DELAY_MS)
            if (System.currentTimeMillis() - Settings.updateCheckedAt >= EVERY_MS) check(activity, quiet = true)
            else found?.let { offer(activity) }
        }
    }

    /** Спросить GitHub. `quiet` — без сообщения «стоит последняя». */
    fun check(activity: Activity, quiet: Boolean) {
        if (checking) return
        scope.launch {
            checking = true
            error = null
            try {
                val json = withContext(Dispatchers.IO) { JSONObject(fetchText(LATEST)) }
                val f = Found(
                    version = json.optString("version"),
                    code = json.optLong("versionCode"),
                    notes = json.optString("notes"),
                    notesEn = json.optString("notes_en"),
                    url = json.optString("url"),
                    sha256 = json.optString("sha256").lowercase(),
                    size = json.optLong("size")
                )
                Settings.updateCheckedAt = System.currentTimeMillis()
                checkedAt = Settings.updateCheckedAt
                if (f.code > currentCode && f.url.isNotBlank() && f.sha256.length == 64) {
                    found = f
                    Diag.i("на GitHub новая версия ${f.version} (${f.code})")
                    offer(activity)
                } else {
                    found = null
                    if (!quiet) Plaques.toast(tr("Стоит последняя версия — {ваша версия}", "ваша версия" to current))
                }
            } catch (t: Throwable) {
                error = t.message ?: t::class.simpleName
                Diag.w("проверка обновлений не удалась: ${Diag.chain(t)}")
            } finally {
                checking = false
            }
        }
    }

    private fun firstLine(text: String) =
        text.lines().map { it.trimStart('-', '*', '•', '#', ' ').trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun offer(activity: Activity) {
        val f = found ?: return
        if (snoozed == f.version || installing) return
        val sub = firstLine(if (Lang.english && f.notesEn.isNotBlank()) f.notesEn else f.notes).ifEmpty { tr("У вас {ваша версия}", "ваша версия" to current) }
        val actions = listOf(
            Plaque.Action(tr("Позже"), quiet = true) { snoozed = f.version; drop() },
            Plaque.Action(tr("Обновить")) { install(activity) }
        )
        val p = plaque
        if (p != null && p in Plaques.items) {
            p.text = tr("Новая версия Lazy Music {версия}", "версия" to f.version); p.sub = sub; p.actions = actions; p.progress = null
        } else {
            plaque = Plaques.notice(tr("Новая версия Lazy Music {версия}", "версия" to f.version), sub, actions)
        }
    }

    private fun drop() {
        plaque?.close()
        plaque = null
    }

    private fun mb(n: Long) =
        if (Lang.english) String.format(Locale.US, "%.1f MB", n / 1048576.0)
        else String.format("%.1f МБ", n / 1048576.0).replace('.', ',')

    /** Скачать, сверить и отдать системе на установку. */
    fun install(activity: Activity) {
        val f = found ?: return
        if (installing) return
        val p = plaque?.takeIf { it in Plaques.items } ?: Plaques.notice("").also { plaque = it }
        // Без разрешения система не даст поставить — просим и ждём возвращения
        if (!activity.packageManager.canRequestPackageInstalls()) {
            waitingPermission = true
            p.text = tr("Разрешите Lazy Music устанавливать приложения")
            p.sub = tr("Один раз: так приложение сможет обновляться само")
            p.progress = null
            p.actions = listOf(
                Plaque.Action(tr("Позже"), quiet = true) { waitingPermission = false; drop() },
                Plaque.Action(tr("Разрешить")) {
                    runCatching {
                        activity.startActivity(
                            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}"))
                        )
                    }
                }
            )
            return
        }
        installing = true
        p.text = tr("Загрузка Lazy Music {версия}…", "версия" to f.version)
        p.sub = ""
        p.actions = emptyList()
        p.progress = 0f
        val app = activity.applicationContext
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    download(f, File(app.cacheDir, "update.apk")) { got, total ->
                        scope.launch {
                            p.progress = if (total > 0) got.toFloat() / total else 0f
                            p.sub = if (total > 0) tr("{скачано} из {всего}", "скачано" to mb(got), "всего" to mb(total)) else mb(got)
                        }
                    }
                }
                p.text = tr("Установка обновления")
                p.sub = ""
                p.progress = 1f
                withContext(Dispatchers.IO) { commit(app, apk) }
            } catch (t: Throwable) {
                installing = false
                Diag.w("обновление не удалось: ${Diag.chain(t)}")
                p.text = tr("Обновление не удалось")
                p.sub = t.message ?: t::class.simpleName.orEmpty()
                p.progress = null
                p.actions = listOf(
                    Plaque.Action(tr("Закрыть"), quiet = true) { drop() },
                    Plaque.Action(tr("Ещё раз")) { install(activity) }
                )
            }
        }
    }

    /** Отказ установщика или «отмена» в системном окне. */
    internal fun failed(message: String) {
        installing = false
        val p = plaque ?: return
        p.text = tr("Обновление не удалось")
        p.sub = message
        p.progress = null
        p.actions = listOf(Plaque.Action(tr("Закрыть"), quiet = true) { drop() })
    }

    private fun download(f: Found, to: File, progress: (Long, Long) -> Unit): File {
        val conn = open(f.url)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode !in 200..299) throw IOException("GitHub ответил ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: f.size
            var got = 0L
            var told = 0L
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        got += n
                        if (got - told > 256 * 1024) { told = got; progress(got, total) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != f.sha256) {
            to.delete()
            throw IOException("файл скачался битым (контрольная сумма не сошлась)")
        }
        return to
    }

    /** Сессия установщика: система покажет своё окно «Обновить приложение?». */
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("lazy-music.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
        apk.delete()
    }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("GitHub ответил ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LazyMusic-Android")
        }
}

/**
 * Ответ установщика. Система просит подтверждения — показываем её окно;
 * не вышло — говорим на плашке.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: tr("установка отменена")
                Diag.w("установщик: $why")
                Update.failed(why)
            }
        }
    }
}
