package com.lazymusic.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.roundToInt

private val Red = Color(0xFFFF4D55)

/**
 * Шторка эквалайзера — как на ПК: кривая с живым спектром под ней,
 * десять ползунков (двойное касание — в ноль), готовые настройки и
 * «Скачать изменённый трек».
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@UnstableApi
@Composable
fun EqSheet(host: PlayerHost, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B1B1F))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(LazyEqIcon, contentDescription = null, tint = Red, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Эквалайзер"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        tr("Двойной щелчок по ползунку — в ноль"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Switch(
                    checked = Eq.on,
                    onCheckedChange = { Eq.switch(it) },
                    modifier = Modifier.semantics { contentDescription = tr("Включить эквалайзер") }
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = tr("Закрыть")) }
            }

            Spacer(Modifier.height(12.dp))
            Curve(playing = host.playing)

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eq.BANDS.indices.forEach { i -> Band(i) }
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Eq.PRESETS.forEach { p ->
                    FilterChip(
                        selected = Eq.on && Eq.preset == p.key,
                        onClick = { Eq.choose(p.key) },
                        label = { Text(tr(p.name)) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = { host.current?.let { Export.start(context, it) } },
                enabled = host.current != null
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Скачать изменённый трек"))
            }
            Text(
                tr("В M4A, с эквалайзером, если он включён. Папка «Музыка/Lazy Music»."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
            )
        }
    }
}

/** Кривая на холсте: частоты по логарифму от 20 Гц до 20 кГц, ±LIMIT дБ. */
@UnstableApi
@Composable
private fun Curve(playing: Boolean) {
    var tick by remember { mutableIntStateOf(0) }
    // Пока шторка открыта и музыка идёт — спектр живой
    LaunchedEffect(playing) {
        while (playing) {
            delay(50)
            tick++
        }
    }
    val n = 96
    val freqs = remember { DoubleArray(n) { 20 * 1000.0.pow(it / (n - 1.0)) } }
    val line = MaterialTheme.colorScheme.outlineVariant
    val bars = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val curve = if (Eq.on) Red else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val gains = Eq.gains
    Canvas(
        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        @Suppress("UNUSED_EXPRESSION") gains
        val w = size.width
        val h = size.height
        drawLine(line, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        if (playing) {
            val live = Eq.spectrum(freqs)
            val bw = w / n
            live.forEachIndexed { k, v ->
                val level = ((v + 100) / 80).toFloat().coerceIn(0f, 1f)
                drawRect(bars, Offset(k * bw, h - level * h), Size((bw - 1).coerceAtLeast(1f), level * h))
            }
        }
        val db = Eq.response(freqs)
        val path = Path()
        db.forEachIndexed { k, v ->
            val x = k / (n - 1f) * w
            val y = h / 2 - (v.coerceIn(-12.0, 12.0) / 12.0).toFloat() * (h / 2 - 4)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, curve, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Ползунок полосы стоя: снизу минус, сверху плюс. */
@UnstableApi
@Composable
private fun Band(i: Int) {
    val g = Eq.gains[i]
    val track = MaterialTheme.colorScheme.outlineVariant
    val thumb = if (Eq.on) Red else MaterialTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(30.dp)) {
        Text(
            if (g > 0) "+$g" else "$g",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        val limit = Eq.LIMIT
        Canvas(
            Modifier.width(30.dp).height(120.dp)
                .pointerInput(i) {
                    fun at(y: Float) = (limit - (y / size.height) * 2 * limit).roundToInt().coerceIn(-limit, limit)
                    detectVerticalDragGestures(
                        onDragStart = { Eq.setBand(i, at(it.y)) },
                        onVerticalDrag = { change, _ -> Eq.setBand(i, at(change.position.y)) }
                    )
                }
                .pointerInput(i) {
                    detectTapGestures(onDoubleTap = { Eq.setBand(i, 0) })
                }
        ) {
            val cx = size.width / 2
            drawRoundRect(track, Offset(cx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
            val y = (limit - g) / (2f * limit) * size.height
            drawCircle(thumb, 7.dp.toPx(), Offset(cx, y.coerceIn(7.dp.toPx(), size.height - 7.dp.toPx())))
        }
        Spacer(Modifier.height(6.dp))
        Text(tr(Eq.LABELS[i]), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}
