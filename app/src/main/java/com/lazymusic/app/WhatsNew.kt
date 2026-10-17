package com.lazymusic.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * «Что нового?» — чёрная карточка 300×400 при первом запуске после
 * обновления, как на ПК. Перечень — раздел этой версии из `CHANGES.md`
 * (лежит в assets) и под ним три прошлых: кто пропустил выпуск, увидит и
 * его. На свежей установке не показываем: там новое всё. Висит, пока не
 * закроют крестиком, — до тех пор и при следующих запусках.
 */
object WhatsNew {

    class Section(val version: String, val items: List<String>)

    /** Что показать; пусто — карточки нет. */
    var sections by mutableStateOf<List<Section>>(emptyList()); private set

    /**
     * Метка закрытой карточки: версия и номер сборки. Одна версия может
     * выйти не одной сборкой — тогда карточка покажется ещё раз.
     */
    private val mark get() = "${Update.current}/${Update.currentCode}"

    fun check(context: Context) {
        val version = Update.current
        if (Settings.seenVersion == mark) return
        val all = parse(runCatching {
            context.assets.open("CHANGES.md").bufferedReader().use { it.readText() }
        }.getOrDefault(""))
        val from = all.indexOfFirst { it.version == version }
        // Прежде ничего не отмечали, но уже слушали — значит, это обновление
        val updated = Settings.seenVersion.isNotEmpty() || Settings.recentTracks().isNotEmpty()
        if (!updated || from < 0 || all[from].items.isEmpty()) {
            Settings.seenVersion = mark
            return
        }
        sections = all.drop(from).take(4).filter { it.items.isNotEmpty() }
    }

    fun close() {
        Settings.seenVersion = mark
        sections = emptyList()
    }

    /** Разделы «## версия» и пункты «- …» под ними. */
    private fun parse(text: String): List<Section> {
        val out = mutableListOf<Section>()
        var version: String? = null
        var items = mutableListOf<String>()
        for (line in text.lines()) {
            if (line.startsWith("## ")) {
                version?.let { out += Section(it, items) }
                version = line.removePrefix("## ").trim()
                items = mutableListOf()
            } else if (version != null) {
                val item = line.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim()
                if (item.isNotEmpty()) items += item
            }
        }
        version?.let { out += Section(it, items) }
        return out
    }
}

private val Muted = Color(0xFF9B9BA6)
private val Faint = Color(0xFF6C6C76)
private val Accent = Color(0xFFFF4D55)

/** Карточка. Стоит в правом нижнем углу, над плеером. */
@Composable
fun WhatsNewCard(modifier: Modifier = Modifier) {
    val sections = WhatsNew.sections
    if (sections.isEmpty()) return
    Box(
        modifier
            .size(300.dp, 400.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, Color(0xFF2A2A30), RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp)) {
            Text(
                "Что нового?",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Fonts.display,
                modifier = Modifier.padding(end = 32.dp)
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                sections.forEachIndexed { i, s ->
                    if (i > 0) {
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider(color = Color(0xFF2A2A30))
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        "Lazy Music ${s.version}",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )
                    for (item in s.items) {
                        Row(Modifier.padding(bottom = if (i == 0) 10.dp else 8.dp)) {
                            Text("•", color = if (i == 0) Accent else Faint, modifier = Modifier.width(14.dp))
                            Text(
                                item,
                                color = if (i == 0) Color.White else Muted,
                                fontSize = if (i == 0) 14.sp else 13.sp,
                                lineHeight = if (i == 0) 20.sp else 18.sp
                            )
                        }
                    }
                }
            }
        }
        IconButton(
            onClick = { WhatsNew.close() },
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
        }
    }
}
