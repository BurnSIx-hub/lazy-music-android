package com.lazymusic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Что не нравится — 👎 у трека. Тот же фильтр, что у Lazy Music на ПК
 * (`taste.js`), с теми же весами.
 *
 * Дизлайк убирает трек из радио и «Может понравиться» насовсем, а похожее
 * на него прореживает, но не вырезает: одна осечка не должна закрывать
 * целый жанр. Похожим считается:
 *
 * - тот же исполнитель — с каждым дизлайком реже; его треки в плейлистах
 *   смягчают: любимому исполнителю один промах почти ничего не стоит;
 * - соседи исполнителя по каталогу Deezer — чуть реже, если их самих нет
 *   в плейлистах;
 * - приметы в названии — «nightcore», «sped up», «remix», «live»…; если
 *   такое же лежит в плейлистах, примета не в счёт;
 * - то же название у другого исполнителя (каверы, перезаливки) — реже,
 *   у того же исполнителя — под запрет.
 *
 * «Оставить или убрать» решает не монетка при каждом сборе, а постоянный
 * порог трека: проходит, пока вес выше порога. Новые дизлайки только сужают
 * пропущенное, и очередь не мерцает от пересбора к пересбору.
 */
object Taste {

    /** Каждый дизлайк исполнителя оставляет от его треков такую долю. */
    private const val ARTIST = 0.45
    private const val NEIGHBOUR = 0.8
    private const val TAG = 0.6
    private const val SAME_TITLE = 0.5
    private const val NEIGHBOURS = 8

    /** Очередь радио после прореживания короче не делаем — добираем наименее похожим. */
    const val MIN_AHEAD = 6

    class Rec(
        val id: String,
        val title: String,
        val artist: String,
        val art: String?,
        val at: Long,
        var near: List<String> = emptyList()
    ) {
        fun track() = Track(id, title, artist, art)
    }

    /** Дизлайкнутые, свежие первыми. Меняется — экран перерисовывается сам. */
    var list by mutableStateOf<List<Rec>>(emptyList())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ── Приметы в названии ───────────────────────────────────────────────────

    /** Слово целиком; со звёздочкой — начало слова («ускоренн*»). */
    private val TAGS: List<Pair<String, Regex>> = listOf(
        "nightcore" to "nightcore",
        "speed" to "sped up|speed up|ускоренн*",
        "slowed" to "slowed|reverb|замедлен*",
        "8d" to "8d",
        "remix" to "remix*|ремикс*|bootleg|mashup|мэшап|мешап",
        "live" to "live|концерт*|unplugged",
        "cover" to "cover|кавер*",
        "lofi" to "lofi|lo-fi|lo fi",
        "phonk" to "phonk|фонк",
        "bass" to "bass boosted|bassboosted",
        "karaoke" to "karaoke|караоке|минус*",
        "hours" to "\\d+ ?(hours?|hrs?|час(а|ов)?)",
        "acoustic" to "acoustic|акустик*",
        "piano" to "piano|пианино|фортепиано",
        "orchestra" to "orchestra*|оркестр*|symphon*",
        "metal" to "metal|метал*",
        "rock" to "rock|рок",
        "epic" to "epic|эпичн*",
        "ost" to "ost|soundtrack|саундтрек*|theme",
        "chip" to "8.?bit|chiptune",
        "edm" to "dubstep|trap|drum and bass|dnb|hardstyle|edm",
        "vocaloid" to "vocaloid|hatsune miku",
    ).map { (tag, words) ->
        val alts = words.split('|').map { w ->
            if (w.endsWith('*')) w.dropLast(1) else "$w(?![\\p{L}\\p{N}])"
        }
        tag to Regex("(?<![\\p{L}\\p{N}])(?:${alts.joinToString("|")})", RegexOption.IGNORE_CASE)
    }

    private fun tagsOf(title: String): List<String> = TAGS.filter { it.second.containsMatchIn(title) }.map { it.first }

    private val NOISE = Regex(
        "(?<![\\p{L}\\p{N}])(official|music|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|mv|clip|клип|текст|премьера)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE
    )
    private val DASH = Regex("\\s[-–—]\\s")

    private fun flat(v: String) = v.lowercase().filter { it.isLetterOrDigit() }

    /** Как `artistKey` на ПК: без «- Topic», только буквы и цифры. */
    fun artistKey(name: String) = flat(artistOf(name))

    /** Название без исполнителя впереди и без «Official Video». Короткие не в счёт. */
    private fun titleKey(t: Track): String {
        var s = t.title.lowercase()
        val parts = s.split(DASH)
        val a = artistKey(t.artist)
        if (parts.size > 1 && a.isNotEmpty() && flat(parts[0]).contains(a)) s = parts.drop(1).joinToString(" ")
        val k = flat(s.replace(NOISE, " "))
        return if (k.length >= 10) k else ""
    }

    // ── Счётчики ─────────────────────────────────────────────────────────────

    private var ids = emptySet<String>()
    private var artists = HashMap<String, Int>()
    private var near = HashMap<String, Int>()
    private var tags = HashMap<String, Int>()
    private var titles = HashMap<String, MutableSet<String>>()

    /** Что лежит в плейлистах: исполнители и приметы. Пересчёт — по смене библиотеки. */
    private class Own(val artists: Map<String, Int>, val tags: Map<String, Int>)

    private var own: Own? = null
    private var ownRevision = -1L

    private fun bump(map: HashMap<String, Int>, key: String, by: Int = 1) {
        if (key.isNotEmpty()) map[key] = (map[key] ?: 0) + by
    }

    private fun reindex() {
        ids = list.mapTo(HashSet()) { it.id }
        artists = HashMap(); near = HashMap(); tags = HashMap(); titles = HashMap()
        for (r in list) {
            val a = artistKey(r.artist)
            bump(artists, a)
            for (n in r.near) if (n != a) bump(near, n)
            for (tag in tagsOf(r.title)) bump(tags, tag)
            val k = titleKey(r.track())
            if (k.isNotEmpty()) titles.getOrPut(k) { HashSet() }.add(a)
        }
    }

    private fun ownCounts(): Own {
        val cached = own
        if (cached != null && ownRevision == Settings.libraryRevision) return cached
        val a = HashMap<String, Int>()
        val t = HashMap<String, Int>()
        val seen = HashSet<String>()
        for (name in Settings.playlistNames()) {
            for (track in Settings.playlist(name)) {
                if (!seen.add(track.id)) continue
                bump(a, artistKey(track.artist))
                for (tag in tagsOf(track.title)) bump(t, tag)
            }
        }
        return Own(a, t).also { own = it; ownRevision = Settings.libraryRevision }
    }

    operator fun contains(videoId: String) = videoId in ids

    /**
     * Какая доля от трека остаётся: 1 — как раньше, 0 — под запретом.
     * `seedArtist` — исполнитель, с которого собрано радио: его человек
     * выбрал сам, поэтому ему дизлайки вдвое мягче.
     */
    fun weight(t: Track, seedArtist: String = ""): Double {
        if (list.isEmpty()) return 1.0
        if (t.id in ids) return 0.0
        val mine = ownCounts()
        val a = artistKey(t.artist)
        val seed = artistKey(seedArtist)
        var w = 1.0
        val k = titleKey(t)
        if (k.isNotEmpty()) {
            titles[k]?.let { who ->
                if (a in who) return 0.0
                w *= SAME_TITLE
            }
        }
        if (a.isNotEmpty()) {
            val kept = mine.artists[a] ?: 0
            val d = artists[a] ?: 0
            if (d > 0) w *= Math.pow(ARTIST, d / (1 + kept / 2.0) / (if (a == seed) 2.0 else 1.0))
            val n = near[a] ?: 0
            if (n > 0 && kept == 0 && a != seed) w *= Math.pow(NEIGHBOUR, n.toDouble())
        }
        for (tag in tagsOf(t.title)) {
            val d = (tags[tag] ?: 0) - (mine.tags[tag] ?: 0) / 4.0
            if (d > 0) w *= Math.pow(TAG, d)
        }
        return w
    }

    /** Постоянный порог трека в [0, 1): FNV-1a от идентификатора, как на ПК. */
    private fun threshold(id: String): Double {
        var x = 2166136261L.toInt()
        for (c in id) {
            x = x xor c.code
            x *= 16777619
        }
        return (x.toLong() and 0xffffffffL) / 4294967296.0
    }

    /**
     * Пропускает список через вкус: запрещённое — вон, похожее — реже.
     * Короче `min` не режет: добирает наименее похожим, но не запрещённым.
     */
    fun screen(tracks: List<Track>, seedArtist: String = "", min: Int = 0): List<Track> {
        if (list.isEmpty()) return tracks
        val keep = HashSet<Track>()
        val spare = mutableListOf<Pair<Track, Double>>()
        for (t in tracks) {
            val w = weight(t, seedArtist)
            if (w >= 1 || (w > 0 && threshold(t.id) < w)) keep += t
            else if (w > 0) spare += t to w
        }
        spare.sortedByDescending { it.second }.take((min - keep.size).coerceAtLeast(0)).forEach { keep += it.first }
        return tracks.filter { it in keep }
    }

    // ── Запись ───────────────────────────────────────────────────────────────

    fun load() {
        list = Settings.dislikes().mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val near = o.optJSONArray("near") ?: JSONArray()
            Rec(
                id = id,
                title = o.optString("title").ifBlank { id },
                artist = o.optString("artist"),
                art = o.optString("art").takeIf { it.isNotBlank() },
                at = o.optLong("at"),
                near = (0 until near.length()).map { near.optString(it) }.filter { it.isNotBlank() }
            )
        }
        reindex()
    }

    private fun save() {
        reindex()
        Settings.saveDislikes(list.map { r ->
            JSONObject().put("id", r.id).put("title", r.title).put("artist", r.artist)
                .put("art", r.art.orEmpty()).put("at", r.at).put("near", JSONArray(r.near))
        })
    }

    fun add(track: Track): Rec {
        val rec = Rec(track.id, track.title, track.artist, track.artworkUrl, System.currentTimeMillis())
        list = listOf(rec) + list.filterNot { it.id == track.id }
        save()
        return rec
    }

    fun remove(videoId: String) {
        if (videoId !in ids) return
        list = list.filterNot { it.id == videoId }
        save()
    }

    /**
     * Дизлайк устоялся — узнаём у каталога соседей исполнителя. Те же
     * соседи у прежнего дизлайка того же исполнителя — берём оттуда.
     */
    fun settle(rec: Rec, then: () -> Unit) {
        scope.launch {
            if (rec.id !in ids) return@launch
            val a = artistKey(rec.artist)
            val known = list.firstOrNull { it !== rec && artistKey(it.artist) == a && it.near.isNotEmpty() }
            rec.near = when {
                known != null -> known.near
                a.isEmpty() -> emptyList()
                else -> withContext(Dispatchers.IO) {
                    runCatching { Deezer.artistInfo(rec.artist)?.related.orEmpty() }.getOrDefault(emptyList())
                }.take(NEIGHBOURS).map { artistKey(it.name) }.filter { it.isNotEmpty() }
            }
            if (rec.id !in ids) return@launch
            // Пересоздаём список: поле поменялось у того же объекта, а
            // Compose следит за самим списком
            list = list.toList()
            save()
            then()
        }
    }

    /** Исполнители, которых фильтр приглушил заметно, — для настроек. */
    fun mutedArtists(): List<String> {
        val mine = ownCounts()
        val names = LinkedHashMap<String, String>()
        for (r in list) {
            val a = artistKey(r.artist)
            if (a.isEmpty() || a in names) continue
            val share = Math.pow(ARTIST, (artists[a] ?: 0) / (1 + (mine.artists[a] ?: 0) / 2.0))
            if (share < 0.5) names[a] = artistOf(r.artist)
        }
        return names.values.toList()
    }
}
