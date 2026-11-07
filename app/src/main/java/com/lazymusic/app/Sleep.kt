package com.lazymusic.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Таймер сна: пауза через N минут или в конце того, что играет, — трека,
 * выпуска или главы книги. Как sleep.js на ПК. Отсчитывает служба
 * ([Podcasts.Tracker]): экран может уснуть раньше музыки.
 */
object Sleep {
    /** Когда встать, мс; 0 — не по времени. */
    var until by mutableStateOf(0L)
    /** Встать в конце трека или главы. */
    var atEnd by mutableStateOf(false)

    val MINUTES = listOf(15, 30, 45, 60, 90)

    val on get() = until > 0 || atEnd

    fun reset() {
        until = 0
        atEnd = false
    }

    /** «29 мин» или «конец» — для кнопки. */
    fun left(): String = when {
        atEnd -> tr("конец")
        until > 0 -> tr("{N} мин", "N" to ((until - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(1))
        else -> ""
    }
}

@Composable
fun SleepDialog(onDismiss: () -> Unit) {
    val t = Podcasts.tracker
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Таймер сна")) },
        text = {
            Column {
                if (Sleep.on) {
                    Text(
                        tr("Пауза: {когда}", "когда" to Sleep.left()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                for (m in Sleep.MINUTES) {
                    TextButton(onClick = { t?.sleepIn(m); onDismiss() }, enabled = t != null, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Через {N} мин", "N" to m), modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { t?.sleepAtEnd(); onDismiss() }, enabled = t != null, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("В конце трека или главы"), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (Sleep.on) TextButton(onClick = { t?.sleepOff(); onDismiss() }) { Text(tr("Выключить таймер")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}
