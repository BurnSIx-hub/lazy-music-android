package com.lazymusic.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Синяя лента плашек — та же, что на ПК (`--blue`). */
val Blue = Color(0xFF3EA6FF)

/**
 * Плашки внизу экрана, над плеером, — как на ПК: «Не нравится … · Отменить»
 * с лентой, которая убывает справа налево, и плашки хода («Скачиваю…»,
 * «Новая версия…») с кнопками.
 *
 * Плашка — наблюдаемый объект: поменять ей текст, кнопки или долю хода
 * можно в любой момент, и она перерисуется сама.
 */
class Plaque internal constructor(
    text: String,
    sub: String,
    actions: List<Action>,
    /** Сколько живёт плашка с «Отменить»; null — пока её не закроют. */
    val timedMs: Long?,
    private val onTimeout: (() -> Unit)?
) {
    class Action(val label: String, val quiet: Boolean = false, val onClick: () -> Unit)

    var text by mutableStateOf(text)
    var sub by mutableStateOf(sub)
    var actions by mutableStateOf(actions)

    /** Доля хода 0…1 для ленты; null — ленты нет. */
    var progress by mutableStateOf<Float?>(null)

    internal var settled = false

    fun close() {
        Plaques.items.remove(this)
    }

    internal fun expire() {
        if (settled) return
        settled = true
        close()
        onTimeout?.invoke()
    }
}

object Plaques {

    /** Больше трёх сразу — старые уходят: экран телефона не резиновый. */
    private const val MAX = 3

    val items = mutableStateListOf<Plaque>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Короткое сообщение без кнопок: само уходит через [ms]. */
    fun toast(text: String, sub: String = "", ms: Long = 3_500L): Plaque {
        val p = notice(text, sub)
        scope.launch {
            delay(ms)
            p.close()
        }
        return p
    }

    /** Плашка до закрытия: кнопками или кодом. */
    fun notice(text: String, sub: String = "", actions: List<Plaque.Action> = emptyList()): Plaque =
        Plaque(text, sub, actions, null, null).also(::push)

    /**
     * Плашка с «Отменить» и убывающей лентой. Не отменили за [ms] —
     * сработает [onDone]; отменили — [onUndo].
     */
    fun undoable(text: String, sub: String, ms: Long, onUndo: () -> Unit, onDone: () -> Unit): Plaque {
        lateinit var p: Plaque
        p = Plaque(text, sub, listOf(Plaque.Action("Отменить") {
            if (p.settled) return@Action
            p.settled = true
            p.close()
            onUndo()
        }), ms, onDone)
        push(p)
        return p
    }

    private fun push(p: Plaque) {
        items.add(p)
        while (items.size > MAX) items.first().expire()
    }
}

/** Стопка плашек. Стоит над мини-плеером. */
@Composable
fun PlaqueHost() {
    if (Plaques.items.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (p in Plaques.items.toList()) {
            androidx.compose.runtime.key(p) { PlaqueView(p) }
        }
    }
}

/** Сколько знаков кнопок умещается рядом с текстом плашки; длиннее — под ним. */
private const val BUTTONS_BESIDE = 16

/**
 * Заголовок длиннее этого с двумя кнопками рядом обрезается («YouTube просит
 * подтвердить, что вы не…», S23) — тогда кнопки тоже под текстом.
 */
private const val TEXT_BESIDE = 30

@Composable
private fun PlaqueButton(a: Plaque.Action) {
    TextButton(onClick = a.onClick) {
        Text(
            a.label,
            color = if (a.quiet) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f) else Blue,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PlaqueView(p: Plaque) {
    // Лента «Отменить»: от полной к пустой, правый край едет влево
    val tape = remember(p) { Animatable(1f) }
    if (p.timedMs != null) {
        LaunchedEffect(p) {
            tape.animateTo(0f, tween(p.timedMs.toInt(), easing = LinearEasing))
            p.expire()
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Длинные кнопки («Закрыть» и «Что не нашлось») рядом с текстом
        // оставляют ему узкую колонку, и заголовок обрезается (S23) —
        // такие встают отдельной строкой под текстом
        val below = p.actions.sumOf { it.label.length } > BUTTONS_BESIDE ||
            p.actions.size > 1 && p.text.length > TEXT_BESIDE
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = if (below) 0.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = if (below) 10.dp else 0.dp)) {
                    Text(
                        p.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (p.sub.isNotBlank()) {
                        // Четыре строки: пояснения импорта («Яндекс пускает только…»)
                        // длиной в сотню знаков, в две строки рядом с кнопкой не влезают
                        Text(
                            p.sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!below) for (a in p.actions) PlaqueButton(a)
                if (p.actions.isEmpty()) Spacer(Modifier.width(10.dp))
            }
            if (below) {
                Row(
                    Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    for (a in p.actions) PlaqueButton(a)
                }
            }
            val share = if (p.timedMs != null) tape.value else p.progress
            if (share != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(Blue.copy(alpha = 0.14f))) {
                    Box(
                        Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp)
                            .clip(RoundedCornerShape(0.dp)).background(Blue)
                    )
                }
            }
        }
    }
}
