package com.lazymusic.app

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * «Итоги года», как у Spotify, — из сводки прослушиваний ([Listening]),
 * своей и присланной компьютером. Считаются при открытии: сводка маленькая,
 * а итоги так всегда свежие. Тот же расчёт — в `ui/js/wrapped.js` на ПК.
 *
 * Показываются в декабре и в январе — за год, который кончается или только
 * что кончился. Остальное время их не видно: пусть будут неожиданностью.
 */
object Wrapped {

    /** Со скольких прослушиваний за год итогам есть что показать. */
    const val MIN_PLAYS = 10

    /** Сколько любимых треков играет кнопка на последнем экране. */
    const val PLAY_TOP = 50

    data class TopTrack(val id: String, val title: String, val artist: String, val plays: Int, val minutes: Long)

    data class TopArtist(val name: String, val plays: Int, val minutes: Long)

    /** Часть суток: ночь — с 0 до 6, утро — до 12, день — до 18, вечер — до 24. */
    enum class Part(val title: String) {
        NIGHT(trKey("по ночам")), MORNING(trKey("по утрам")), DAY(trKey("днём")), EVENING(trKey("по вечерам"))
    }

    data class Summary(
        val year: Int,
        val minutes: Long,
        val plays: Int,
        /** Разных треков и исполнителей, прослушанных хоть раз. */
        val trackCount: Int,
        val artistCount: Int,
        /** Сначала самые прослушиваемые — [PLAY_TOP] штук, на экране первые пять. */
        val topTracks: List<TopTrack>,
        val topArtists: List<TopArtist>,
        /** Дней с музыкой — из [span] дней с начала счёта до конца года или сегодня. */
        val days: Int,
        val span: Int,
        /** Самая длинная серия дней с музыкой подряд. */
        val streak: Int,
        /** Минут по дням — для календаря. */
        val daily: Map<LocalDate, Long>,
        val bestDay: LocalDate?,
        val bestDayMinutes: Long,
        /** Минут по часам суток — 24 числа. */
        val hours: List<Long>,
        val part: Part,
        val peakHour: Int,
        /** Минут по месяцам — 12 чисел, январь первым. */
        val months: List<Long>,
        /** Самый музыкальный месяц, 1–12. */
        val topMonth: Int
    ) {
        val enough get() = plays >= MIN_PLAYS
    }

    /** За какой год итоги сейчас: декабрь — этот, январь — прошлый, иначе никакой. */
    fun season(today: LocalDate = LocalDate.now()): Int? = when (today.monthValue) {
        12 -> today.year
        1 -> today.year - 1
        else -> null
    }

    /** Итоги на сейчас — если пора и есть что показать. */
    fun ready(context: Context, today: LocalDate = LocalDate.now()): Summary? {
        val year = season(today) ?: return null
        return of(context, year, today)?.takeIf { it.enough }
    }

    /** Итоги года, как есть, без проверки сезона; ничего не слушали — null. */
    fun of(context: Context, year: Int, today: LocalDate = LocalDate.now()): Summary? =
        Listening.year(context, year)?.let { summary(it, year, today) }

    fun summary(y: JSONObject, year: Int, today: LocalDate = LocalDate.now()): Summary {
        fun min(sec: Double) = (sec / 60).roundToLong()

        val tracks = y.optJSONObject("tracks") ?: JSONObject()
        val top = tracks.keys().asSequence().mapNotNull { id ->
            val t = tracks.optJSONObject(id) ?: return@mapNotNull null
            val n = t.optInt("n")
            if (n <= 0) return@mapNotNull null
            TopTrack(id, t.optString("t").ifBlank { id }, t.optString("a"), n, min(t.optDouble("s", 0.0))) to t.optDouble("s", 0.0)
        }.sortedWith(compareByDescending<Pair<TopTrack, Double>> { it.first.plays }.thenByDescending { it.second })
            .map { it.first }.toList()

        val artists = y.optJSONObject("artists") ?: JSONObject()
        val topArtists = artists.keys().asSequence().mapNotNull { name ->
            val a = artists.optJSONObject(name) ?: return@mapNotNull null
            if (a.optInt("n") <= 0 || name.isBlank()) return@mapNotNull null
            TopArtist(name, a.optInt("n"), min(a.optDouble("s", 0.0))) to a.optDouble("s", 0.0)
        }.sortedWith(compareByDescending<Pair<TopArtist, Double>> { it.second }.thenByDescending { it.first.plays })
            .map { it.first }.toList()

        // Дни: с музыкой — где набралось хоть одно прослушивание
        val daysObj = y.optJSONObject("days") ?: JSONObject()
        val days = daysObj.keys().asSequence().mapNotNull { key ->
            runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to daysObj.optDouble(key, 0.0) }
        }.filter { it.first.year == year }.toList()
        val heard = days.filter { it.second * 1000 >= Listening.PLAY_MS }.map { it.first }.sorted()
        var streak = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in heard) {
            run = if (prev != null && ChronoUnit.DAYS.between(prev, d) == 1L) run + 1 else 1
            streak = maxOf(streak, run)
            prev = d
        }
        val first = days.filter { it.second > 0 }.minOfOrNull { it.first }
        val last = minOf(today, LocalDate.of(year, 12, 31))
        val span = first?.let { (ChronoUnit.DAYS.between(it, last) + 1).toInt().coerceAtLeast(1) } ?: 0
        val best = days.maxByOrNull { it.second }

        val months = LongArray(12)
        val monthSec = DoubleArray(12)
        for ((d, sec) in days) monthSec[d.monthValue - 1] += sec
        for (i in 0 until 12) months[i] = min(monthSec[i])

        val hourSec = DoubleArray(24)
        y.optJSONArray("hours")?.let { for (i in 0 until minOf(24, it.length())) hourSec[i] = it.optDouble(i, 0.0) }
        val parts = DoubleArray(4)
        for (h in 0 until 24) parts[h / 6] += hourSec[h]

        return Summary(
            year = year,
            minutes = min(y.optDouble("sec", 0.0)),
            plays = top.sumOf { it.plays },
            trackCount = top.size,
            artistCount = topArtists.size,
            topTracks = top.take(PLAY_TOP),
            topArtists = topArtists.take(5),
            days = heard.size,
            span = span,
            streak = streak,
            daily = days.associate { it.first to min(it.second) },
            bestDay = best?.first,
            bestDayMinutes = best?.let { min(it.second) } ?: 0,
            hours = hourSec.map { min(it) },
            part = Part.entries[parts.indices.maxBy { parts[it] }],
            peakHour = hourSec.indices.maxBy { hourSec[it] },
            months = months.toList(),
            topMonth = monthSec.indices.maxBy { monthSec[it] } + 1
        )
    }

    /**
     * Показ без настоящих данных — чтобы посмотреть экраны до декабря:
     * случайный, но правдоподобный год из треков библиотеки.
     */
    fun demo(library: List<Track>, year: Int = LocalDate.now().year): Summary {
        val rnd = java.util.Random(year.toLong())
        val y = JSONObject().put("sec", 0.0).put("days", JSONObject())
            .put("hours", org.json.JSONArray(DoubleArray(24).toList()))
            .put("tracks", JSONObject()).put("artists", JSONObject())
        val pool = library.distinctBy { it.id }.shuffled(rnd).take(120)
        if (pool.isEmpty()) return summary(y, year)
        val start = LocalDate.of(year, 1, 1)
        var total = 0.0
        for (day in 0 until 330) {
            if (rnd.nextInt(10) < 2) continue
            val date = start.plusDays(day.toLong())
            var sec = 0.0
            repeat(3 + rnd.nextInt(18)) {
                // Любимые — чаще: квадрат случайного тянет к началу списка
                val t = pool[(pool.size * rnd.nextDouble() * rnd.nextDouble()).toInt()]
                val artist = artistOf(plainText(t.artist))
                val s = 120.0 + rnd.nextInt(120)
                sec += s
                val e = y.getJSONObject("tracks").optJSONObject(t.id)
                    ?: JSONObject().put("t", plainText(t.title)).put("a", artist).put("n", 0).put("s", 0.0)
                        .also { y.getJSONObject("tracks").put(t.id, it) }
                e.put("n", e.optInt("n") + 1).put("s", e.optDouble("s") + s)
                if (artist.isNotBlank()) {
                    val a = y.getJSONObject("artists").optJSONObject(artist)
                        ?: JSONObject().put("n", 0).put("s", 0.0).also { y.getJSONObject("artists").put(artist, it) }
                    a.put("n", a.optInt("n") + 1).put("s", a.optDouble("s") + s)
                }
                val hour = listOf(8, 13, 18, 21, 22, 23, 0, 1)[rnd.nextInt(8)]
                val hours = y.getJSONArray("hours")
                hours.put(hour, hours.optDouble(hour) + s)
            }
            y.getJSONObject("days").put(date.toString(), sec)
            total += sec
        }
        y.put("sec", total)
        return summary(y, year, LocalDate.of(year, 12, 31))
    }
}
