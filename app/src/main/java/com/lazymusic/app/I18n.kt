package com.lazymusic.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/**
 * Язык надписей. Текст в коде — русский, он же ключ перевода:
 * `tr("Закрыть")`. Английский лежит в `assets/i18n/en.json` под тем же
 * русским текстом; перевода нет — остаётся русский.
 *
 * Подстановки — `{имя}`, значения рядом: `tr("Найдено: {число}", "число" to n)`.
 * Имена в скобках те же, что видны в файле текстов на переписывание.
 *
 * [english] — состояние Compose: переключили язык — экран перерисовался.
 */
object Lang {

    /** "" — как в системе, "ru", "en". */
    val CHOICES = listOf("", "ru", "en")

    var english by mutableStateOf(false); private set

    private var en: Map<String, String> = emptyMap()

    fun attach(context: Context) {
        en = runCatching {
            val json = JSONObject(context.assets.open("i18n/en.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrElse {
            Diag.w("перевод не загрузился: ${Diag.chain(it)}")
            emptyMap()
        }
        apply()
    }

    /**
     * Выбрать язык по настройке. «Как в системе» — русский только у
     * русской системы: остальным английский понятнее. Зовётся и при
     * создании экрана — язык системы могли сменить, пока приложение жило.
     */
    fun apply() {
        english = when (Settings.lang) {
            "ru" -> false
            "en" -> true
            else -> Locale.getDefault().language != "ru"
        }
    }

    fun choose(code: String) {
        Settings.lang = code
        apply()
    }

    fun of(ru: String): String = if (english) en[ru] ?: ru else ru
}

/** Надпись на языке приложения; `ru` — русский текст, он же ключ. */
fun tr(ru: String): String = Lang.of(ru)

fun tr(ru: String, vararg subs: Pair<String, Any?>): String {
    var s = Lang.of(ru)
    for ((name, value) in subs) s = s.replace("{$name}", value.toString())
    return s
}

/**
 * Русский текст, который переводится позже, при показе (`tr(preset.name)`):
 * он живёт в списке, собранном один раз, а язык можно сменить на ходу.
 * Сам ничего не делает — метка для файла текстов и проверки перевода.
 */
fun trKey(ru: String): String = ru

/** «1 трек», «2 трека», «5 треков» — или «1 track», «5 tracks». */
fun plural(n: Long, one: String, few: String, many: String, enOne: String, enMany: String): String {
    if (Lang.english) return "$n ${if (n == 1L) enOne else enMany}"
    val form = when {
        n % 100 in 11..14 -> many
        n % 10 == 1L -> one
        n % 10 in 2..4 -> few
        else -> many
    }
    return "$n $form"
}
