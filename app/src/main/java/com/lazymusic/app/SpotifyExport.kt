package com.lazymusic.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Перенос из выгрузки данных Spotify — окна. Сами списки разбирает
 * [Spotify.readExport], ищет на YouTube [YandexImport.importLists].
 * То же, что `spotifyExport.js` на ПК.
 */

/** Как получить выгрузку; [onPick] — выбрать архив, [onPage] — страница Spotify. */
@Composable
fun SpotifyExportHow(onPick: () -> Unit, onPage: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Выгрузка данных Spotify")) },
        text = {
            Column {
                Text(tr("Для длинных плейлистов и «Любимых треков». На сайте Spotify: Аккаунт " +
                    "→ Конфиденциальность → «Скачать ваши данные», отметьте «Данные " +
                    "аккаунта». Архив придёт на почту, обычно это занимает несколько дней."))
                Spacer(Modifier.height(8.dp))
                Text(tr("Когда Spotify пришлёт письмо, скачайте архив по ссылке в нём, затем " +
                    "выберите здесь my_spotify_data.zip целиком, распаковывать не нужно."))
            }
        },
        confirmButton = { TextButton(onClick = onPick) { Text(tr("Выбрать архив")) } },
        dismissButton = { TextButton(onClick = onPage) { Text(tr("Открыть страницу Spotify")) } }
    )
}

/** Что перенести: всё отмечено, «Любимые треки» — первыми. */
@Composable
fun SpotifyExportChoose(lists: List<Spotify.ExportList>, onImport: (List<Spotify.ExportList>) -> Unit, onDismiss: () -> Unit) {
    val picks = remember(lists) { mutableStateListOf(*Array(lists.size) { true }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Что перенести")) },
        text = {
            Column {
                Text(
                    tr("Плейлисты появятся сразу, треки ищутся на YouTube по очереди. " +
                        "Длинные списки — это долго. Если закрыть приложение, поиск " +
                        "продолжится при следующем запуске."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(lists) { i, l ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth().clickable { picks[i] = !picks[i] }.padding(vertical = 2.dp)
                        ) {
                            Checkbox(checked = picks[i], onCheckedChange = { picks[i] = it })
                            Text(l.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                trackCount(l.tracks.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(lists.filterIndexed { i, _ -> picks[i] }) }, enabled = picks.any { it }) {
                Text(tr("Перенести"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}
