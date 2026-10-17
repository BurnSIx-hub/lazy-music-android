package com.lazymusic.app

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Радио по треку. Собирается двумя кругами — и это здесь главное решение.
 *
 * **Ближний круг** ([near]) — микс YouTube `RD<videoId>`. Даровой, ключа не
 * просит, отвечает за секунды. Но проверено 2026-09-10 на трёх затравках:
 * без авторизации он отдаёт почти исключительно того же исполнителя.
 * Поэтому играть с него можно сразу, а разнообразием он не блещет.
 *
 * **Дальний круг** ([wider]) — другие исполнители, близкие по духу. Стоит
 * десятки секунд, поэтому собирается уже во время игры и досыпается в
 * очередь. Источника два:
 *
 * 1. [Deezer] — кто кого слушает вместе. Так устроены и Last.fm, и радио
 *    больших сервисов: похожесть считается не по звуку, а по пересечению
 *    слушателей. Работает для всего, что есть в каталоге.
 * 2. Обход соседей — миксы YouTube уже по тем трекам ближнего круга, что
 *    оказались от **других** исполнителей. Обычный случайный обход графа
 *    похожести на один шаг. Нужен там, где каталог бессилен: игровые
 *    саундтреки, доуджин, всё мелкое и нишевое.
 *
 * Обе ленты сплетает [mix] — с ограничением на исполнителя, чтобы
 * разнообразие стояло в начале очереди, а не в её хвосте.
 *
 * Виды миксов `RDAMVM` и `RDEM` без аккаунта недоступны — проверено, отвечают
 * «unviewable» и 400. Работает только `RD`.
 */
object Radio {

    private const val TAG = "LazyMusic"

    /** Разделитель полей. Управляющий символ, в названиях роликов не встречается. */
    private const val SEP = "\u0001"

    // ── Ближний круг ─────────────────────────────────────────────────────────

    /**
     * Микс YouTube по самому ролику, без него самого: затравка встанет
     * в очередь головой и без нашей помощи.
     *
     * Пустой список означает, что YouTube микс не собрал: так бывает
     * с не-музыкальными роликами.
     */
    suspend fun near(seed: Track, limit: Int = 40): List<Track> {
        val rows = probe(listOf(mixUrl(seed.id)), limit)
        val tracks = rows.asSequence()
            .filterNot { it.id == seed.id }
            .distinctBy { it.id }
            // Перезаливки возвращаем настоящему автору — иначе они сойдут
            // за других исполнителей и займут начало очереди
            .map { row ->
                row.toTrack(
                    artist = artistOf(seed.artist).takeIf { titleNames(row.title, seed.artist) }
                )
            }
            .toList()
        Log.i(TAG, "ближний круг по ${seed.id}: ${tracks.size} треков")
        return tracks
    }

    // ── Дальний круг ─────────────────────────────────────────────────────────

    /**
     * Треки других исполнителей. Ничего из [near] и ничего от затравочного
     * исполнителя сюда не попадает — в этом весь смысл.
     *
     * Ошибки внутри проглатываются намеренно: это добавка к уже играющей
     * очереди, а не обязательство. Не вышло — радио просто останется
     * таким, каким его собрал YouTube.
     */
    suspend fun wider(seed: Track, near: List<Track>, want: Int = 14): List<Track> {
        val known = HashSet<String>(near.size + 1)
        near.mapTo(known) { it.id }
        known += seed.id
        val seedArtist = artistKey(seed)
        // Названия ближнего круга: там лежит вся дискография затравки,
        // и по ним она узнаётся, даже когда подписана иначе
        val nearTitles = (near.asSequence().map { it.title } + seed.title)
            .map(::norm)
            .filterTo(HashSet()) { it.length >= TITLE_MATCH }
        val out = mutableListOf<Track>()

        /** Единственная дверь в дальний круг: через неё проходят оба источника. */
        fun accept(track: Track) {
            if (track.id in known) return
            if (artistKey(track) == seedArtist) return
            if (disguised(track.title, seed, nearTitles)) {
                Log.i(TAG, "отбросил «${track.title.take(50)}»: затравка под другим именем")
                return
            }
            known += track.id
            out += track
        }

        // 1. Каталог: единственный источник, который смотрит дальше одного артиста
        val suggestions = runCatching { withContext(Dispatchers.IO) {
            Deezer.similar(seed.artist, seed.title, want)
        } }
            .onFailure { Log.w(TAG, "Deezer промолчал: ${it.message}") }
            .getOrDefault(emptyList())

        runCatching { resolve(suggestions) }
            .onFailure { Log.w(TAG, "поиск по подсказкам не удался: ${it.message}") }
            .getOrDefault(emptyList())
            .forEach(::accept)

        // 2. Каталог нишевую музыку не знает. Тогда шаг в сторону по самому
        //    YouTube: просим микс уже не по затравке, а по её соседям
        if (out.size < want / 2) {
            val hops = near.asSequence()
                .filter { artistKey(it).isNotEmpty() && artistKey(it) != seedArtist }
                .distinctBy { artistKey(it) }
                .take(HOPS)
                .map { mixUrl(it.id) }
                .toList()
            if (hops.isNotEmpty()) {
                val rows = runCatching { probe(hops, HOP_DEPTH) }
                    .onFailure { Log.w(TAG, "обход соседей не удался: ${it.message}") }
                    .getOrDefault(emptyList())
                rows.forEach { accept(it.toTrack()) }
            }
        }

        Log.i(TAG, "дальний круг по ${seed.id}: ${out.size} треков")
        return out
    }

    // ── Поиск подсказок на YouTube ───────────────────────────────────────────

    /**
     * Находит на YouTube то, что предложил каталог, — **все запросы одним
     * заходом yt-dlp**: запуск Python стоит секунды, оптом заметно дешевле.
     *
     * Имя исполнителя берётся из каталога, а не у канала-загрузчика: у того
     * оно бывает каким угодно, а по имени считается разнообразие очереди.
     * Связать строку вывода с тем, что искали, помогает `playlist_title` —
     * у поиска это сама поисковая строка.
     */
    suspend fun resolve(suggestions: List<Deezer.Suggestion>): List<Track> {
        if (suggestions.isEmpty()) return emptyList()
        val byQuery = suggestions.associateBy { it.query }
        val found = probe(suggestions.map { "ytsearch1:${it.query}" }, 1)
            .map { it.toTrack(artist = byQuery[it.source]?.artist) }
        Log.i(TAG, "поиск подсказок: спросили ${suggestions.size}, нашлось ${found.size}")
        return found
    }

    /**
     * Песни исполнителя — поиск в YouTube Music по разделу «Песни», как на
     * ПК. Отвечает за пару секунд и отдаёт чистые названия, но имени
     * исполнителя у строк нет: проставляем то, по которому искали. Изредка
     * туда попадает чужой кавер — цена того, что идентификатора канала нет.
     */
    suspend fun artistSongs(name: String): List<Track> {
        val url = "https://music.youtube.com/search?q=${java.net.URLEncoder.encode(name, "UTF-8")}#songs"
        return probe(listOf(url), 40).distinctBy { it.id }.map { it.toTrack(artist = artistOf(name)) }
    }

    // ── Сплетение ────────────────────────────────────────────────────────────

    /**
     * Сплетает ленты в одну очередь.
     *
     * Правила те же, что у радио больших сервисов: ленты обходим по кругу,
     * чтобы чужие исполнители стояли не в хвосте, а вперемешку с самого
     * начала, и на одного исполнителя берём не больше [perArtist] треков.
     *
     * Отброшенные квотой не теряются: если после отбора очередь выходит
     * короче [limit], их дописывают в конец. Короткая очередь хуже
     * однообразной — у нишевой музыки похожих может не найтись вовсе,
     * и тогда пусть играет то, что есть.
     *
     * [head] — трек, который обязан стоять первым: тот, что уже играет.
     */
    fun mix(
        head: Track?,
        lanes: List<List<Track>>,
        perArtist: Int = 2,
        limit: Int = 40
    ): List<Track> {
        val out = mutableListOf<Track>()
        val seen = HashSet<String>()
        val used = HashMap<String, Int>()
        val spare = mutableListOf<Track>()

        fun take(track: Track) {
            out += track
            val key = artistKey(track)
            if (key.isNotEmpty()) used[key] = (used[key] ?: 0) + 1
        }

        if (head != null && seen.add(head.id)) take(head)

        val cursor = IntArray(lanes.size)
        var moved = true
        while (out.size < limit && moved) {
            moved = false
            for (lane in lanes.indices) {
                if (out.size >= limit) break
                val list = lanes[lane]
                var i = cursor[lane]
                while (i < list.size) {
                    val track = list[i++]
                    if (!seen.add(track.id)) continue
                    val key = artistKey(track)
                    if (key.isNotEmpty() && (used[key] ?: 0) >= perArtist) {
                        spare += track
                        continue
                    }
                    take(track)
                    moved = true
                    break
                }
                cursor[lane] = i
            }
        }

        // Хвосты лент, до которых обход не дошёл
        for (lane in lanes.indices) {
            val list = lanes[lane]
            for (i in cursor[lane] until list.size) {
                if (seen.add(list[i].id)) spare += list[i]
            }
        }

        for (track in spare) {
            if (out.size >= limit) break
            out += track
        }
        return out
    }

    /**
     * Обложка без обращения к API: у превью YouTube предсказуемый адрес.
     * `--flat-playlist` картинок не отдаёт, а тратить на них запрос жалко.
     */
    fun thumbnailUrl(videoId: String) = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    // ── Внутреннее ───────────────────────────────────────────────────────────

    /** Сколько соседей опрашиваем при обходе и по сколько треков берём с каждого. */
    private const val HOPS = 2
    private const val HOP_DEPTH = 20

    private fun mixUrl(videoId: String) =
        "https://www.youtube.com/watch?v=$videoId&list=RD$videoId"

    private fun artistKey(track: Track) = track.artist.trim().lowercase()

    /** Только буквы и цифры: знаки, регистр и пробелы в названиях гуляют как хотят. */
    private fun norm(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Короче этого сравнивать нельзя. Имя «ZUN» нашлось бы в середине чужого
     * слова, а название «War» — почти в каждом втором ролике.
     */
    private const val ARTIST_MATCH = 5
    private const val TITLE_MATCH = 10

    /**
     * Стоит ли имя исполнителя прямо в заголовке ролика.
     *
     * Проверено 2026-09-12: в миксе YouTube по затравке из ULTRAKILL
     * 34 ролика из 39 пришли с автоканала «Heaven Pierce Her - Topic»,
     * а ещё два — с фанатского канала «Hakita», и в заголовке у них
     * «Heaven Pierce Her - No Devil Lived On (ULTRAKILL 8-2 Theme #2)».
     * По каналу это другой исполнитель, по заголовку — тот же самый.
     * Верить надо заголовку: иначе перезаливки сойдут за разнообразие
     * и займут начало очереди, ради которого всё и затевалось.
     */
    private fun titleNames(title: String, artist: String): Boolean {
        val name = norm(artistOf(artist))
        return name.length >= ARTIST_MATCH && norm(title).contains(name)
    }

    /**
     * Затравочный исполнитель под другим именем — или попросту повтор.
     *
     * Сверки имён в дальнем круге мало: каталог может подписать ту же
     * музыку иначе, а поиск — найти **другую заливку** той же вещи, и по
     * идентификатору она не поймается. Поэтому смотрим на заголовок:
     * названо ли там имя затравки, и не звучало ли такое название уже
     * в ближнем круге.
     */
    private fun disguised(title: String, seed: Track, nearTitles: Set<String>): Boolean {
        val flat = norm(title)
        if (flat.isEmpty()) return false
        if (titleNames(title, seed.artist)) return true
        return nearTitles.any { flat.contains(it) }
    }

    /**
     * Строка вывода yt-dlp. `source` — заголовок списка, из которого трек
     * приехал: у поиска это сама поисковая строка, и по ней находится, что
     * именно мы искали.
     */
    class Row(
        val id: String,
        val title: String,
        val uploader: String,
        val source: String
    ) {
        fun toTrack(artist: String? = null) = Track(
            id = id,
            title = Settings.trackName(id) ?: title.ifBlank { id },
            artist = artist?.trim() ?: artistOf(uploader),
            artworkUrl = thumbnailUrl(id)
        )
    }

    /**
     * Один заход yt-dlp сразу по нескольким адресам: и миксы, и поисковые
     * запросы. Запуск Python стоит секунды, так что просить всё оптом
     * заметно дешевле, чем поодиночке.
     *
     * [strict] — для импорта ([YandexImport]): без сети не висеть минутами
     * (по умолчанию yt-dlp ждёт соединения 20 с и пробует четырежды; на ПК
     * замерено 2026-10-17 — заход из восьми поисков шёл 2,5 минуты), и
     * пустой заход с ошибкой — это беда, а не «ничего не нашлось».
     */
    internal suspend fun probe(urls: List<String>, perUrl: Int, strict: Boolean = false): List<Row> =
        withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(urls).apply {
                addOption("--flat-playlist")
                addOption("--playlist-end", perUrl.toString())
                addOption("--no-warnings")
                if (strict) {
                    addOption("--socket-timeout", "10")
                    addOption("--extractor-retries", "1")
                }
                // Пара, без которой один недоступный ролик губит весь заход:
                // обёртка youtubedl-android молчит про ненулевой код выхода
                // только когда в запросе есть оба этих ключа сразу. Строки
                // JSON летят в тот же поток и отсеиваются при разборе —
                // разделителя в них нет
                addOption("--ignore-errors")
                addOption("--dump-json")
                addOption("--print", "%(id)s$SEP%(title)s$SEP%(uploader)s$SEP%(playlist_title)s")
            }
            val response = try {
                Engine.extracting { YoutubeDL.getInstance().execute(request) }
            } catch (t: Throwable) {
                throw IOException("yt-dlp не отдал ленту: ${t.message}", t)
            }
            // Ненулевой код — это `--ignore-errors` проглотил чью-то беду.
            // Без этой строки было бы видно только, что находок меньше,
            // чем просили, и гадать пришлось бы вслепую
            if (response.exitCode != 0) {
                Log.w(TAG, "yt-dlp вышел с ${response.exitCode}: ${response.err.trim().take(400)}")
            }
            val rows = parse(response.out)
            if (strict && rows.isEmpty() && response.exitCode != 0) {
                throw IOException("YouTube не ответил: ${response.err.trim().lineSequence().lastOrNull().orEmpty().take(200)}")
            }
            rows
        }

    private fun parse(out: String): List<Row> = out.lineSequence()
        .mapNotNull { line ->
            val parts = line.split(SEP)
            if (parts.size < 2) return@mapNotNull null
            val id = field(parts, 0)
            if (id.isEmpty()) return@mapNotNull null
            Row(
                id = id,
                title = field(parts, 1),
                uploader = field(parts, 2),
                source = field(parts, 3)
            )
        }
        .toList()

    /** Недоступное поле yt-dlp печатает как `NA` — это не значение. */
    private fun field(parts: List<String>, index: Int): String =
        parts.getOrNull(index)?.trim()?.takeUnless { it == "NA" }.orEmpty()
}
