package com.lazymusic.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «только что», «5 мин назад», «16 октября, 13:04» — как на ПК. */
fun ago(ms: Long): String {
    if (ms <= 0) return tr("ни разу")
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> tr("только что")
        s < 3600 -> tr("{минут} мин назад", "минут" to Math.round(s / 60.0))
        s < 86_400 -> tr("{часов} ч назад", "часов" to Math.round(s / 3600.0))
        Lang.english -> SimpleDateFormat("MMMM d, HH:mm", Locale.ENGLISH).format(Date(ms))
        else -> SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(ms))
    }
}

/**
 * Группы настроек — те же пять, что на ПК. Названия и пояснения — ключи
 * перевода: список собирается один раз, а язык меняют на ходу.
 */
enum class SettingsGroup(val title: String, val hint: String, val icon: ImageVector) {
    MAIN(trKey("Общие"), trKey("Язык, обновления, ключ YouTube Data API"), Icons.Default.Settings),
    MUSIC(trKey("Музыка"), trKey("Радио и «Не нравится»"), Icons.Default.MusicNote),
    LINK(trKey("Сопряжение"), trKey("Компьютер и модуль Foundry"), Icons.Default.Devices),
    FILES(trKey("Скачанное"), trKey("Кэш и скачивание плейлистов"), Icons.Default.Download),
    SERVICE(trKey("Служебное"), trKey("yt-dlp и журнал"), Icons.Default.Build),
}

/**
 * Первая страница настроек — список групп, как в настройках Android.
 * Вышла новая версия — у «Основного» вместо пояснения «Доступна …».
 */
@Composable
fun SettingsGroups(onOpen: (SettingsGroup) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        for (g in SettingsGroup.entries) {
            val found = Update.found.takeIf { g == SettingsGroup.MAIN }
            ListItem(
                headlineContent = { Text(tr(g.title)) },
                supportingContent = {
                    if (found != null) Text(tr("Доступна {версия}", "версия" to found.version), color = MaterialTheme.colorScheme.primary)
                    else Text(tr(g.hint))
                },
                leadingContent = { Icon(g.icon, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(g) }
            )
        }
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

/**
 * Пояснение, где ник Telegram (`@имя`) — ссылка: нажал — открылся чат.
 * Для «Журнала»: туда пользователь вписал, кому слать логи (2026-10-18).
 */
@Composable
fun LinkHint(text: String) {
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val shown = buildAnnotatedString {
        var at = 0
        for (m in TG_NICK.findAll(text)) {
            append(text.substring(at, m.range.first))
            withLink(LinkAnnotation.Url("https://t.me/${m.groupValues[1]}", link)) { append(m.value) }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }
    Text(shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Ник Telegram: 5–32 знака, буквы, цифры и подчёркивание. */
private val TG_NICK = Regex("""(?<![\w@])@([A-Za-z][A-Za-z0-9_]{4,31})\b""")

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

/**
 * Язык: как в системе, русский или английский — см. [Lang]. Названия
 * языков — на самих языках: кто не читает по-русски, найдёт «English».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageSection() {
    var choice by remember { mutableStateOf(Settings.lang) }
    Section(tr("Язык")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (code in Lang.CHOICES) {
                FilterChip(
                    selected = choice == code,
                    onClick = { choice = code; Lang.choose(code) },
                    label = {
                        Text(
                            when (code) {
                                "ru" -> "Русский"
                                "en" -> "English"
                                else -> tr("Как в системе")
                            }
                        )
                    }
                )
            }
        }
    }
}

/** Обновления с GitHub — см. [Update]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdatesSection(activity: Activity) {
    val f = Update.found
    Section(tr("Обновления"), tr("Версия {версия}. Новые выпуски приходят с GitHub.", "версия" to Update.current)) {
        if (f != null) {
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("Доступна {версия}", "версия" to f.version), style = MaterialTheme.typography.titleSmall)
                    if (f.notes.isNotBlank()) SmallHint(f.notes)
                }
            }
        }
        // Галочка — «не обновлять»: включена, когда проверка выключена
        var off by remember { mutableStateOf(!Settings.checkUpdates) }
        SwitchRow(
            tr("Не обновлять автоматически"),
            tr("Обновления проверяются автоматически. Чтобы установить новую версию, нажмите кнопку «Обновить»."),
            off
        ) { off = it; Settings.checkUpdates = !it }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (f != null) {
                Button(onClick = { Update.install(activity) }, enabled = !Update.installing) {
                    Text(if (Update.installing) tr("Установка новой версии…") else tr("Обновить до {версия}", "версия" to f.version))
                }
            }
            OutlinedButton(onClick = { Update.check(activity, quiet = false) }, enabled = !Update.checking && !Update.installing) {
                if (Update.checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(tr("Проверить обновление"))
            }
            TextButton(onClick = {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Update.RELEASES))) }
            }) { Text(tr("Все версии на GitHub")) }
        }
        Spacer(Modifier.height(6.dp))
        SmallHint(tr("Проверено: {когда}", "когда" to ago(Update.checkedAt)) + (Update.error?.let { " · $it" } ?: ""))
    }
}

/** Дизлайки — см. [Taste]. */
@Composable
fun DislikesSection() {
    val muted = Taste.mutedArtists()
    Section(
        tr("Не нравится"),
        tr("Убирает трек из радио и «Может понравиться». Похожее попадается реже: тот же " +
            "исполнитель, его соседи по каталогу, те же приметы в названии вроде " +
            "«nightcore», «sped up», «live». Что лежит в ваших плейлистах, фильтр смягчает.")
    ) {
        if (muted.isNotEmpty()) {
            SmallHint(tr("Эти исполнители будут попадаться реже: {исполнители}", "исполнители" to muted.joinToString(", ")))
            Spacer(Modifier.height(8.dp))
        }
        if (Taste.list.isEmpty()) SmallHint(tr("Пока пусто."))
        for (r in Taste.list) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Artwork(r.art, 40)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SmallHint("${artistOf(r.artist)} · ${ago(r.at)}")
                }
                TextButton(onClick = { Taste.remove(r.id) }) { Text(tr("Вернуть")) }
            }
        }
    }
}
