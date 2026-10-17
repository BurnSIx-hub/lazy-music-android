package com.lazymusic.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbUpOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

/**
 * Меню трека — шторка снизу, а не окно поверх всего, как на ПК: те же
 * пункты, раскладка по плейлистам галочками — за один заход можно и
 * добавить в один список, и убрать из другого.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun TrackMenu(
    track: Track,
    host: PlayerHost,
    cached: Boolean,
    onDismiss: () -> Unit,
    onRadio: (Track) -> Unit,
    onArtist: (String) -> Unit,
    onDownload: (Track) -> Unit,
    onRemoveDownload: (Track) -> Unit,
    onChanged: () -> Unit
) {
    var names by remember { mutableStateOf(emptyList<String>()) }
    var inLists by remember { mutableStateOf(emptySet<String>()) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(track.id, revision) {
        // Понравившееся — тоже плейлист, но у него своя строка с сердцем
        names = Settings.playlistNames().filter { it != Likes.LIST }
        inLists = Settings.playlistsWith(track.id)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Artwork(track.artworkUrl, 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (track.artist.isNotBlank()) {
                        Text(track.artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }

            val liked = track.id in Likes
            Item(
                if (liked) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt,
                if (liked) "Убрать из понравившегося" else "Нравится",
                accent = liked
            ) { Likes.toggle(track); onChanged() }

            val disliked = Taste.list.any { it.id == track.id }
            Item(
                if (disliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
                if (disliked) "Снять дизлайк" else "Не нравится",
                if (disliked) "Трек снова может попасться в радио и подборке"
                else "Убрать из радио и подборки, похожих треков станет меньше",
                accent = disliked
            ) { Dislike.toggle(host, track); onDismiss() }

            Item(Icons.Default.Radio, "Радио по треку", "Очередь похожих треков от YouTube") {
                onRadio(track); onDismiss()
            }
            Item(Icons.Default.PlaylistPlay, "Играть следующим") {
                host.playNext(track); onDismiss()
            }
            if (track.artist.isNotBlank()) {
                Item(Icons.Default.Person, "К исполнителю", artistOf(track.artist)) {
                    onArtist(track.artist); onDismiss()
                }
            }

            // ── Скачивание ───────────────────────────────────────────────────
            val running = Downloads.progress[track.id]
            val failure = Downloads.failures[track.id]
            when {
                running != null -> Item(
                    null, "Скачивается… ${(running * 100).toInt()}%", "Нажмите, чтобы отменить",
                    progress = running
                ) { Downloads.cancel(track.id) }
                cached -> Item(Icons.Default.DeleteOutline, "Убрать скачанное", "Трек снова будет играть из сети") {
                    onRemoveDownload(track)
                }
                else -> Item(
                    Icons.Default.Download, "Скачать",
                    if (failure != null) "не вышло: $failure" else "Загрузить трек на ваше устройство",
                    error = failure != null
                ) { onDownload(track) }
            }

            Item(Icons.Default.Edit, "Переименовать", "Под таким же названием будет на компьютере и в модуле") {
                renaming = true
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("В плейлисты", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            for (name in names) {
                val present = name in inLists
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (present) Settings.removeFromPlaylist(name, track.id)
                            else Settings.addToPlaylist(name, track)
                            revision++
                            onChanged()
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (present) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    else Spacer(Modifier.width(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(name, color = if (present) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Item(Icons.Default.Add, "Новый плейлист") { creating = true }
            Spacer(Modifier.size(12.dp))
        }
    }

    if (creating) {
        NameDialog(
            title = "Новый плейлист",
            confirm = "Создать",
            onDismiss = { creating = false },
            onConfirm = { name ->
                // Создаём и сразу кладём трек: окно открывали ради него
                Settings.createPlaylist(name)
                Settings.addToPlaylist(name.trim(), track)
                creating = false
                revision++
                onChanged()
                Plaques.toast("«${track.title}» — в новом плейлисте «${name.trim()}»")
            }
        )
    }
    if (renaming) {
        NameDialog(
            title = "Своё название",
            confirm = "Сохранить",
            initial = Settings.trackName(track.id) ?: track.title,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                Settings.rename(track.id, name)
                renaming = false
                onChanged()
                onDismiss()
            }
        )
    }
}

/** Строка меню: значок, название, пояснение мелко. */
@Composable
private fun Item(
    icon: ImageVector?,
    title: String,
    sub: String? = null,
    accent: Boolean = false,
    error: Boolean = false,
    progress: Float? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            progress != null -> CircularProgressIndicator(
                progress = { progress.coerceAtLeast(0.02f) }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp
            )
            icon != null -> Icon(
                icon, contentDescription = null,
                tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            if (!sub.isNullOrBlank()) {
                Text(
                    sub, style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
