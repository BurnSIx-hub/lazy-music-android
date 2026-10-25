package com.lazymusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Скачивание при свёрнутом приложении (просьба 2026-10-25).
 *
 * Качают [Downloads] и очередь [Offline] в своей области выполнения, но
 * свёрнутое приложение без музыки Android замораживает — скачивание
 * вставало до следующего открытия. Пока есть что качать, эта служба держит
 * процесс на переднем плане с уведомлением и снимается сама, когда всё
 * скачано. Пока играет музыка, процесс и так держит [PlaybackService].
 *
 * Поднять службу переднего плана Android разрешает, пока приложение на
 * экране, — то есть когда человек сам нажал «Скачать» или включил плейлист
 * без сети. Из фона (сеть вернулась, а приложение свёрнуто) — не всегда:
 * тогда качаем как раньше, пока процесс не заморозят.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTE_ID = 7

        /** Без работы столько — снимаемся. Очередь отдыхает между треками 1,5–3 с. */
        private const val IDLE_MS = 6_000L
        private const val TICK_MS = 1_000L

        /** Будильник держим кусками: зависни служба — сам отпустится. */
        private const val WAKE_MS = 10 * 60_000L

        /** Отказ поднять службу из фона пишем в журнал не чаще раза в столько. */
        private const val REFUSED_LOG_MS = 10 * 60_000L

        @Volatile private var running = false
        @Volatile private var refusedAt = 0L

        /** Есть работа — держать процесс. Зовёт [Downloads.fetch] перед каждым треком. */
        fun hold(context: Context) {
            if (running) return
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
                running = true
            } catch (t: Throwable) {
                // Android 12+ из фона не пускает — качаем без службы
                val now = System.currentTimeMillis()
                if (now - refusedAt > REFUSED_LOG_MS) {
                    refusedAt = now
                    Diag.w("скачивание: служба не поднялась из фона (${t.javaClass.simpleName}) — качаю без неё")
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var shown: String? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promote()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watch?.isActive != true) watch = scope.launch { watch() }
        return START_NOT_STICKY
    }

    /** На передний план. false — Android не пустил (поднят из фона). */
    private fun promote(): Boolean = try {
        channel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTE_ID, note(), type)
        true
    } catch (t: Throwable) {
        Diag.w("скачивание: передний план не дали (${t.javaClass.simpleName})")
        false
    }

    private suspend fun watch() {
        wake = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LazyMusic:download")
            ?.apply { setReferenceCounted(false) }
        var idleSince = 0L
        var woken = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (Downloads.progress.isNotEmpty() || Offline.busy) {
                idleSince = 0L
            } else if (idleSince == 0L) {
                idleSince = now
            } else if (now - idleSince >= IDLE_MS) {
                break
            }
            // Экран погас — процессор не засыпает посреди трека
            if (now - woken > WAKE_MS / 2) {
                wake?.acquire(WAKE_MS)
                woken = now
            }
            refresh()
            delay(TICK_MS)
        }
        // Новый трек после этого — поднимет службу заново
        running = false
        stopSelf()
    }

    /** Уведомление меняем, только когда поменялось, что в нём написано. */
    private fun refresh() {
        val text = text()
        val percent = percent()
        val key = "$text|$percent"
        if (key == shown) return
        shown = key
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, note()) }
    }

    /** Что качается и сколько ещё в очереди. */
    private fun text(): String {
        val title = Downloads.progress.keys.firstOrNull()?.let { Downloads.title(it) }
        val left = Offline.left
        return listOfNotNull(
            title?.takeIf { it.isNotBlank() }?.let { "«$it»" },
            if (left > 1) tr("ещё {треки}", "треки" to trackCount(left - 1)) else null,
        ).joinToString(" · ")
    }

    private fun percent(): Int =
        Downloads.progress.values.firstOrNull()?.let { (it * 100).toInt() } ?: 0

    private fun note(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = percent()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Скачивание музыки"))
            .setContentText(text())
            .setProgress(100, percent, percent == 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Скачивание"), NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** Android 14+ ограничивает такую службу шестью часами в сутки — уступаем. */
    override fun onTimeout(startId: Int) {
        Diag.w("скачивание: Android снял службу по времени")
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Diag.w("скачивание: Android снял службу по времени")
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { if (wake?.isHeld == true) wake?.release() }
        super.onDestroy()
    }
}
