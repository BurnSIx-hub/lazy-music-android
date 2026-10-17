package com.lazymusic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * «Нравится» — 👍 у трека (до 2026-10-17 — сердце).
 *
 * Отдельного хранилища у лайков нет: понравившееся — это обычный плейлист
 * с именем [LIST]. Так он сам попадает и в библиотеку, и в перенос на
 * компьютер ([Share]), и в меню трека, а список, ввезённый из YouTube
 * Music, с первого же дня оказывается тем самым, который пополняет 👍.
 * Имя взято у YouTube Music дословно — чтобы человек узнал свой список.
 *
 * Новое кладётся **в начало**: понравившееся читают сверху, от свежего
 * к давнему, и ввезённый список приехал именно в таком порядке.
 *
 * [ids] нужен ради скорости. Плейлист лежит в настройках одной строкой
 * JSON, и спрашивать его про каждый трек на экране значило бы разбирать
 * её десятки раз за кадр.
 */
object Likes {

    const val LIST = "Понравившаяся музыка"

    /** Идентификаторы понравившегося. Меняется — 👍 перерисовываются сами. */
    var ids by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Кому ещё сказать о перемене. Слушатель один — служба проигрывания:
     * у неё сердце в уведомлении, и Compose до него не дотягивается.
     */
    var onChange: (() -> Unit)? = null

    operator fun contains(videoId: String): Boolean = videoId in ids

    /**
     * Перечитать из настроек. Нужно после всего, что меняет список в обход
     * [toggle]: ввоз файлом, галочка в меню трека, удаление плейлиста.
     */
    fun load() {
        ids = Settings.playlist(LIST).mapTo(HashSet()) { it.id }
        onChange?.invoke()
    }

    /** @return true, если трек теперь в понравившемся */
    fun toggle(track: Track): Boolean {
        val liked = track.id !in ids
        if (liked) {
            Settings.prependToPlaylist(LIST, listOf(track))
            ids = ids + track.id
        } else {
            Settings.removeFromPlaylist(LIST, track.id)
            ids = ids - track.id
        }
        onChange?.invoke()
        return liked
    }

    /**
     * Дописать пачку — из YouTube Music. Уже лежащее пропускается, порядок
     * внутри пачки сохраняется.
     *
     * @return сколько треков оказалось новыми
     */
    fun addAll(tracks: List<Track>): Int {
        val added = Settings.prependToPlaylist(LIST, tracks)
        if (added > 0) load()
        return added
    }
}
