package com.lazymusic.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/**
 * Что и сколько слушали — для «Итогов года», как у Spotify.
 *
 * Хранится сводкой по годам, а не журналом прослушиваний: итогам нужны
 * только суммы, а журнал за год разросся бы до тысяч строк. Вид тот же,
 * что у ПК (`ui/js/listening.js`), — сводки телефона и компьютера
 * складываются без пересчёта:
 *
 * ```
 * { "v": 1, "years": { "2026": {
 *     "sec": всего секунд, "days": { "2026-10-19": секунд },
 *     "hours": [24 числа — секунд по часам суток],
 *     "tracks": { id: { "t": название, "a": исполнитель, "n": прослушиваний, "s": секунд } },
 *     "artists": { исполнитель: { "n": …, "s": … } } } } }
 * ```
 *
 * Прослушанным трек считается с 30 секунд, как у Spotify. Время идёт,
 * пока звучит звук: пауза и подгрузка не считаются.
 *
 * Итоги общие с компьютером: свою сводку телефон отдаёт ему со сводкой
 * библиотеки ([Hub]), а в ответ получает сумму остальных — компьютера и
 * других телефонов. Она лежит отдельно (`listening-others.json`) и
 * прибавляется только при показе: прислали заново — заменили.
 */
object Listening {

    /** С какого времени трек засчитан как прослушанный. */
    const val PLAY_MS = 30_000L

    private const val FILE = "listening.json"
    private const val OTHERS = "listening-others.json"

    private var data: JSONObject? = null
    private var dirty = false
    private var others: JSONObject? = null

    /** Растёт с каждым прибавлением — по нему сводка с компьютером знает, что слать заново. */
    @Volatile
    var revision = 0L
        private set

    @Synchronized
    private fun data(context: Context): JSONObject = data ?: runCatching {
        JSONObject(File(context.filesDir, FILE).readText())
    }.getOrElse { JSONObject().put("v", 1).put("years", JSONObject()) }.also { data = it }

    /** Своя сводка целиком — копией: компьютеру её шлют из другого потока. */
    @Synchronized
    fun own(context: Context): JSONObject = JSONObject(data(context).toString())

    @Synchronized
    private fun others(context: Context): JSONObject = others ?: runCatching {
        JSONObject(File(context.filesDir, OTHERS).readText())
    }.getOrElse { JSONObject() }.also { others = it }

    /** Отпечаток присланного компьютером: тот же — слать заново незачем. */
    @Synchronized
    fun othersTag(context: Context): String = others(context).optString("tag")

    /** Компьютер прислал сумму остальных: `{ tag, data }`. */
    @Synchronized
    fun setOthers(context: Context, got: JSONObject) {
        if (got.optJSONObject("data")?.optJSONObject("years") == null) return
        others = got
        runCatching {
            val tmp = File(context.filesDir, "$OTHERS.tmp")
            tmp.writeText(got.toString())
            tmp.renameTo(File(context.filesDir, OTHERS))
        }.onFailure { Diag.w("сводка прослушиваний с компьютера не записалась: ${Diag.chain(it)}") }
    }

    /** Год целиком — своё и присланное компьютером, сложенное; ничего — null. */
    @Synchronized
    fun year(context: Context, year: Int): JSONObject? {
        val key = year.toString()
        val mine = data(context).getJSONObject("years").optJSONObject(key)
        val theirs = others(context).optJSONObject("data")?.optJSONObject("years")?.optJSONObject(key)
        if (mine == null && theirs == null) return null
        val sum = emptyYear()
        mine?.let { addYear(sum, it) }
        theirs?.let { addYear(sum, it) }
        return sum
    }

    private fun emptyYear() = JSONObject()
        .put("sec", 0.0).put("days", JSONObject()).put("hours", JSONArray(DoubleArray(24).toList()))
        .put("tracks", JSONObject()).put("artists", JSONObject())

    /** Прибавить к году [into] год [from] — как `listening.rs` на ПК. */
    internal fun addYear(into: JSONObject, from: JSONObject) {
        into.put("sec", into.optDouble("sec", 0.0) + from.optDouble("sec", 0.0))
        val days = into.getJSONObject("days")
        from.optJSONObject("days")?.let { src ->
            for (d in src.keys()) days.put(d, days.optDouble(d, 0.0) + src.optDouble(d, 0.0))
        }
        val hours = into.getJSONArray("hours")
        from.optJSONArray("hours")?.let { src ->
            for (i in 0 until minOf(24, src.length())) hours.put(i, hours.optDouble(i, 0.0) + src.optDouble(i, 0.0))
        }
        for (key in listOf("tracks", "artists")) {
            val dst = into.getJSONObject(key)
            val src = from.optJSONObject(key) ?: continue
            for (k in src.keys()) {
                val v = src.optJSONObject(k) ?: continue
                val e = dst.optJSONObject(k) ?: JSONObject().put("n", 0).put("s", 0.0).also { dst.put(k, it) }
                e.put("n", e.optInt("n") + v.optInt("n"))
                e.put("s", e.optDouble("s", 0.0) + v.optDouble("s", 0.0))
                // Название и исполнитель трека — какие есть, если своих нет
                for (field in listOf("t", "a")) {
                    if (e.optString(field).isEmpty() && v.optString(field).isNotEmpty()) e.put(field, v.optString(field))
                }
            }
        }
    }

    /** Добавить [ms] звучания трека; [count] — засчитать прослушивание. */
    @Synchronized
    fun add(context: Context, item: MediaItem, ms: Long, count: Boolean) {
        if (ms <= 0 && !count) return
        val now = LocalDateTime.now()
        val years = data(context).getJSONObject("years")
        val y = years.optJSONObject(now.year.toString()) ?: emptyYear()
            .also { years.put(now.year.toString(), it) }
        val sec = ms / 1000.0
        y.put("sec", y.optDouble("sec", 0.0) + sec)
        val day = now.toLocalDate().toString()
        val days = y.getJSONObject("days")
        days.put(day, days.optDouble(day, 0.0) + sec)
        val hours = y.getJSONArray("hours")
        hours.put(now.hour, hours.optDouble(now.hour, 0.0) + sec)

        val meta = item.mediaMetadata
        val title = plainText(meta.title?.toString().orEmpty())
        val artist = artistOf(plainText(meta.artist?.toString().orEmpty()))
        val tracks = y.getJSONObject("tracks")
        val t = tracks.optJSONObject(item.mediaId) ?: JSONObject().put("n", 0).put("s", 0.0)
            .also { tracks.put(item.mediaId, it) }
        // Название — последнее увиденное: свои переименования доезжают сюда же
        t.put("t", title).put("a", artist).put("s", t.optDouble("s", 0.0) + sec)
        if (count) t.put("n", t.optInt("n") + 1)
        if (artist.isNotEmpty()) {
            val artists = y.getJSONObject("artists")
            val a = artists.optJSONObject(artist) ?: JSONObject().put("n", 0).put("s", 0.0)
                .also { artists.put(artist, it) }
            a.put("s", a.optDouble("s", 0.0) + sec)
            if (count) a.put("n", a.optInt("n") + 1)
        }
        dirty = true
        revision++
    }

    /** Записать на диск, если есть что. Через временный файл: обрыв не портит сводку. */
    @Synchronized
    fun save(context: Context) {
        if (!dirty) return
        val text = data(context).toString()
        runCatching {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(context.filesDir, FILE))
            dirty = false
        }.onFailure { Diag.w("сводка прослушиваний не записалась: ${Diag.chain(it)}") }
    }

    /**
     * Следит за плеером в службе проигрывания ([PlaybackService]): она
     * живёт и при погашенном экране. Раз в полминуты сбрасывает набежавшее
     * в сводку — убитый системой процесс потеряет не больше.
     */
    class Tracker(private val context: Context, player: Player) : Player.Listener {

        private val main = Handler(Looper.getMainLooper())
        private var item: MediaItem? = player.currentMediaItem

        /** Начало текущего отрезка звучания; 0 — не звучит. */
        private var since = 0L

        /** Сколько звучало это проигрывание трека и засчитано ли оно. */
        private var heard = 0L
        private var counted = false

        private val tick = object : Runnable {
            override fun run() {
                flush()
                save(context)
                if (since != 0L) main.postDelayed(this, TICK_MS)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            main.removeCallbacks(tick)
            if (isPlaying) {
                since = SystemClock.elapsedRealtime()
                main.postDelayed(tick, TICK_MS)
            } else {
                flush()
                since = 0L
                save(context)
            }
        }

        /** Новый трек или тот же по кругу (повтор) — новое проигрывание. */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            flush()
            item = mediaItem
            heard = 0
            counted = false
        }

        fun stop() {
            main.removeCallbacks(tick)
            flush()
            since = 0L
            save(context)
        }

        private fun flush() {
            if (since == 0L) return
            val now = SystemClock.elapsedRealtime()
            val ms = now - since
            since = now
            val current = item ?: return
            heard += ms
            val count = !counted && heard >= PLAY_MS
            if (count) counted = true
            add(context, current, ms, count)
        }

        private companion object {
            const val TICK_MS = 30_000L
        }
    }
}
