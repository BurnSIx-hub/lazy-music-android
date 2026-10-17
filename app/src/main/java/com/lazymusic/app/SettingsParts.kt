package com.lazymusic.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «только что», «5 мин назад», «16 октября, 13:04» — как на ПК. */
fun ago(ms: Long): String {
    if (ms <= 0) return "ни разу"
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "только что"
        s < 3600 -> "${Math.round(s / 60.0)} мин назад"
        s < 86_400 -> "${Math.round(s / 3600.0)} ч назад"
        else -> SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(ms))
    }
}

/** Раздел настроек — карточкой, как на ПК. */
@Composable
fun Section(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                SmallHint(hint)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SmallHint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)

/** Строка с выключателем справа и пояснением под названием. */
@Composable
fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (!hint.isNullOrBlank()) SmallHint(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Обновления с GitHub — см. [Update]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdatesSection(activity: Activity) {
    val f = Update.found
    Section("Обновления", "Версия ${Update.current}. Новые выпуски приходят с GitHub.") {
        if (f != null) {
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Доступна ${f.version}", style = MaterialTheme.typography.titleSmall)
                    if (f.notes.isNotBlank()) SmallHint(f.notes)
                }
            }
        }
        // Галочка — «не обновлять»: включена, когда проверка выключена
        var off by remember { mutableStateOf(!Settings.checkUpdates) }
        SwitchRow(
            "Не обновлять автоматически",
            "Обновления проверяются автоматически. Чтобы установить новую версию, нажмите кнопку «Обновить».",
            off
        ) { off = it; Settings.checkUpdates = !it }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (f != null) {
                Button(onClick = { Update.install(activity) }, enabled = !Update.installing) {
                    Text(if (Update.installing) "Установка новой версии…" else "Обновить до ${f.version}")
                }
            }
            OutlinedButton(onClick = { Update.check(activity, quiet = false) }, enabled = !Update.checking && !Update.installing) {
                if (Update.checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Проверить обновление")
            }
            TextButton(onClick = {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Update.RELEASES))) }
            }) { Text("Все версии на GitHub") }
        }
        Spacer(Modifier.height(6.dp))
        SmallHint("Проверено: ${ago(Update.checkedAt)}" + (Update.error?.let { " · $it" } ?: ""))
    }
}

/** Дизлайки — см. [Taste]. */
@Composable
fun DislikesSection() {
    val muted = Taste.mutedArtists()
    Section(
        "Не нравится",
        "Убирает трек из радио и «Может понравиться». Похожее попадается реже: тот же исполнитель, " +
            "его соседи по каталогу, те же приметы в названии вроде «nightcore», «sped up», «live». " +
            "Что лежит в ваших плейлистах, фильтр смягчает."
    ) {
        if (muted.isNotEmpty()) {
            SmallHint("Эти исполнители будут попадаться реже: ${muted.joinToString(", ")}")
            Spacer(Modifier.height(8.dp))
        }
        if (Taste.list.isEmpty()) SmallHint("Пока пусто.")
        for (r in Taste.list) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Artwork(r.art, 40)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SmallHint("${artistOf(r.artist)} · ${ago(r.at)}")
                }
                TextButton(onClick = { Taste.remove(r.id) }) { Text("Вернуть") }
            }
        }
    }
}
