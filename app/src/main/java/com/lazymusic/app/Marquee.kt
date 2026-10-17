package com.lazymusic.app

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Строка, которая не режется многоточием, а медленно проезжает, если не
 * влезает (просьба пользователя 2026-10-18: «Clair Obscur: Expedition 33 |
 * Full Or…» не прочесть). Влезает — стоит на месте, как обычный текст.
 *
 * Только для того, что на виду по одному: название и исполнитель играющего,
 * заголовок плейлиста. В списках треков не ставим — десятки бегущих строк
 * разом мельтешат и тратят батарею.
 */
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    center: Boolean = false
) {
    // Короткое — по центру или с края, как задано; длинное ограничено
    // шириной и едет внутри неё
    Box(modifier.fillMaxWidth(), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
        Text(
            text = text,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                // Сначала дать прочесть начало, потом поехать
                initialDelayMillis = 1_500,
                repeatDelayMillis = 2_000,
                spacing = androidx.compose.foundation.MarqueeSpacing(48.dp),
                velocity = 30.dp
            )
        )
    }
}
