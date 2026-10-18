package com.lazymusic.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Настройки и свои списки — то же, что модуль на ПК держит в настройках мира:
 * ключ YouTube Data API, переименования треков и собственные плейлисты.
 *
 * Ключ отвечает только за метаданные (поиск, плейлисты). Звук через него
 * не идёт — см. [YtDataSource].
 *
 * Свои плейлисты хранят трек целиком, а не один идентификатор: иначе при
 * открытии списка пришлось бы идти в API за каждым названием, а смысл своих
 * списков ровно в том, чтобы они открывались мгновенно и работали офлайн.
 */
object Settings {

    private const val FILE = "lazy-music"
    private const val KEY_API = "api_key"
    private const val KEY_RENAMES = "renames"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_WIDE_RADIO = "wide_radio"

    private lateinit var prefs: SharedPreferences

    fun attach(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /**
     * Достраивать ли радио другими исполнителями — см. [Radio.wider].
     *
     * По умолчанию да: без этого радио YouTube крутит одного артиста.
     * Выключать имеет смысл на дорогом или медленном соединении: добор
     * стоит десяток-другой запросов уже во время игры.
     */
    var wideRadio: Boolean
        get() = prefs.getBoolean(KEY_WIDE_RADIO, true)
        set(value) = prefs.edit().putBoolean(KEY_WIDE_RADIO, value).apply()

    // ── Обновление yt-dlp ────────────────────────────────────────────────────

    private const val KEY_ENGINE_CHECKED = "engine_checked_at"
    private const val KEY_ENGINE_REJECTED = "engine_rejected"

    /**
     * Когда последний раз удалось спросить у GitHub свежую сборку.
     * Хранится здесь, а не в памяти: процесс живёт сутками, но и убивают
     * его часто, а ходить в сеть при каждом воскрешении незачем.
     */
    var engineCheckedAt: Long
        get() = prefs.getLong(KEY_ENGINE_CHECKED, 0L)
        set(value) = prefs.edit().putLong(KEY_ENGINE_CHECKED, value).apply()

    /**
     * Сборка, которая скачалась, но не запустилась, — см. [Engine]. Пока на
     * GitHub лежит она же, заново её не тянем; выйдет следующая — попробуем.
     */
    var engineRejected: String
        get() = prefs.getString(KEY_ENGINE_REJECTED, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ENGINE_REJECTED, value).apply()

    // ── Раздача библиотеки на компьютер ──────────────────────────────────────

    private const val KEY_SHARE = "share_library"
    private const val KEY_SHARE_TOKEN = "share_token"

    /**
     * Отдавать ли библиотеку по сети — см. [Share].
     *
     * По умолчанию нет. Wi-Fi бывает не только домашний, и висящий порт
     * в чужой сети человек включать не просил.
     */
    var shareLibrary: Boolean
        get() = prefs.getBoolean(KEY_SHARE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHARE, value).apply()

    /**
     * Код, который человек переносит в модуль. Заводится сам при первом
     * спросе и дальше не меняется: иначе после каждого запуска пришлось бы
     * вводить заново.
     */
    val shareToken: String
        get() = prefs.getString(KEY_SHARE_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: newShareToken()

    /**
     * Завести новый код — старый перестаёт подходить сразу.
     *
     * Буквы подобраны так, чтобы не путались при наборе: ни нуля с «O»,
     * ни единицы с «l».
     */
    fun newShareToken(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val fresh = (1..8).map { alphabet.random() }.joinToString("")
        prefs.edit().putString(KEY_SHARE_TOKEN, fresh).apply()
        return fresh
    }

    // ── Компьютер ────────────────────────────────────────────────────────────

    private const val KEY_HUB_ADDRESS = "hub_address"
    private const val KEY_HUB_TOKEN = "hub_token"
    private const val KEY_HUB_ID = "hub_id"
    private const val KEY_HUB_NAME = "hub_name"
    private const val KEY_HUB_PEER = "hub_peer"
    private const val KEY_HUB_APPLIED = "hub_applied"
    private const val KEY_HUB_SYNCED = "hub_synced"

    var hubAddress: String
        get() = prefs.getString(KEY_HUB_ADDRESS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HUB_ADDRESS, value).apply()

    /** Постоянный ключ, выданный компьютером при сопряжении. Пусто — не сопряжён. */
    var hubToken: String
        get() = prefs.getString(KEY_HUB_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HUB_TOKEN, value).apply()

    /** По нему компьютер узнаётся, когда роутер выдал ему другой адрес. */
    var hubId: String
        get() = prefs.getString(KEY_HUB_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HUB_ID, value).apply()

    var hubName: String
        get() = prefs.getString(KEY_HUB_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HUB_NAME, value).apply()

    /** Ревизия сведённой библиотеки, которую телефон принял последней. */
    var hubApplied: Long
        get() = prefs.getLong(KEY_HUB_APPLIED, 0L)
        set(value) = prefs.edit().putLong(KEY_HUB_APPLIED, value).apply()

    var hubLastSync: Long
        get() = prefs.getLong(KEY_HUB_SYNCED, 0L)
        set(value) = prefs.edit().putLong(KEY_HUB_SYNCED, value).apply()

    /**
     * Имя этого телефона для компьютера. Заводится один раз и живёт вместе
     * с библиотекой: стёрли данные приложения — компьютер увидит новый
     * телефон и сведёт с нуля, только складывая, ничего не удаляя.
     */
    val hubPeer: String
        get() = prefs.getString(KEY_HUB_PEER, null)?.takeIf { it.isNotBlank() }
            ?: (1..16).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
                .also { prefs.edit().putString(KEY_HUB_PEER, it).apply() }

    // ── Правки библиотеки ────────────────────────────────────────────────────

    /** Растёт при каждой правке плейлистов и названий. */
    @Volatile
    var libraryRevision = 0L
        private set

    /** Кому сказать о правке — сводке с компьютером ([Hub]). */
    var onLibraryChanged: (() -> Unit)? = null

    private fun changed() {
        libraryRevision++
        onLibraryChanged?.invoke()
        // Плейлисты без сети докачивают новое (Offline)
        Offline.libraryChanged()
    }

    /**
     * Библиотека для сводки: треки как лежат, без подстановки своих названий —
     * названия едут отдельной картой.
     */
    fun libraryForSync(): JSONObject {
        val all = raw()
        val lists = JSONArray()
        for (name in all.keys()) {
            lists.put(JSONObject().put("name", name).put("tracks", all.optJSONArray(name) ?: JSONArray()))
        }
        return JSONObject().put("playlists", lists).put("names", renames())
    }

    /**
     * Заменяет библиотеку сведённой с компьютера. Порядок списков — как
     * пришёл. О правке никому не говорит: сводить это обратно незачем.
     *
     * @return true, если что-то действительно поменялось
     */
    fun replaceLibrary(lib: JSONObject): Boolean {
        val next = JSONObject()
        val lists = lib.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until lists.length()) {
            val p = lists.optJSONObject(i) ?: continue
            val name = p.optString("name").trim()
            if (name.isEmpty() || next.has(name)) continue
            val src = p.optJSONArray("tracks") ?: JSONArray()
            val tracks = JSONArray()
            for (j in 0 until src.length()) {
                val t = src.optJSONObject(j) ?: continue
                val id = t.optString("id")
                if (id.isBlank()) continue
                tracks.put(
                    JSONObject()
                        .put("id", id)
                        .put("title", t.optString("title").ifBlank { id })
                        .put("artist", t.optString("artist"))
                        .put("art", t.optString("art"))
                )
            }
            next.put(name, tracks)
        }
        val names = lib.optJSONObject("names") ?: JSONObject()
        if (next.toString() == raw().toString() && names.toString() == renames().toString()) return false
        prefs.edit()
            .putString(KEY_PLAYLISTS, next.toString())
            .putString(KEY_RENAMES, names.toString())
            .apply()
        libraryRevision++
        return true
    }

    // ── Переименования ───────────────────────────────────────────────────────

    /** Своё название трека, если задано. Названия с YouTube бывают нечитаемы. */
    fun trackName(videoId: String): String? =
        renames().optString(videoId).takeIf { it.isNotBlank() }

    fun rename(videoId: String, name: String?) {
        val all = renames()
        if (name.isNullOrBlank()) all.remove(videoId) else all.put(videoId, name.trim())
        prefs.edit().putString(KEY_RENAMES, all.toString()).apply()
        changed()
    }

    /** Все свои названия целиком — для передачи на компьютер, см. [Share]. */
    fun renamesJson(): JSONObject = renames()

    private fun renames(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_RENAMES, "{}").orEmpty()) }
            .getOrElse { JSONObject() }

    // ── Свои плейлисты ───────────────────────────────────────────────────────

    /** Имена списков в том порядке, в котором их завели. */
    fun playlistNames(): List<String> = raw().keys().asSequence().toList()

    fun playlist(name: String): List<Track> = raw().optJSONArray(name).tracks()

    fun createPlaylist(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val all = raw()
        if (all.has(clean)) return false
        all.put(clean, JSONArray())
        save(all)
        Diag.i("плейлист «$clean»: заведён")
        return true
    }

    fun deletePlaylist(name: String) {
        val all = raw()
        val size = all.optJSONArray(name)?.length() ?: 0
        all.remove(name)
        save(all)
        Diag.i("плейлист «$name»: удалён, было $size")
    }

    /**
     * Кладёт трек **наверх** списка, как «Мне нравится» в Яндексе. Внизу
     * его не находили: у знакомой в списке из Яндекса 1179 треков, свежие
     * сверху, и добавленный уезжал 1180-м (2026-10-18). Так же и на ПК.
     *
     * @return false, если трек в списке уже был — тогда ничего не меняем.
     */
    fun addToPlaylist(name: String, track: Track): Boolean {
        val all = raw()
        val old = all.optJSONArray(name) ?: JSONArray()
        for (i in 0 until old.length()) {
            if (old.optJSONObject(i)?.optString("id") == track.id) {
                Diag.i("плейлист «$name»: ${track.id} уже есть, место ${i + 1} из ${old.length()}")
                return false
            }
        }
        val arr = JSONArray().put(track.json())
        for (i in 0 until old.length()) {
            old.optJSONObject(i)?.let { arr.put(it) }
        }
        all.put(name, arr)
        save(all)
        Diag.i("плейлист «$name»: ${track.id} добавлен наверх, стало ${arr.length()}")
        return true
    }

    /**
     * Дописывает сразу много — для ввоза файлом ([Import]).
     *
     * Отдельно от [addToPlaylist] ради одной записи вместо тысячи: тот
     * перечитывает и сохраняет всё хранилище на каждый трек, и на списке
     * в тысячу песен это тысяча разборов растущего JSON.
     *
     * @return сколько треков действительно добавилось
     */
    fun addManyToPlaylist(name: String, tracks: List<Track>): Int {
        if (tracks.isEmpty()) return 0
        val all = raw()
        val arr = all.optJSONArray(name) ?: JSONArray()
        val have = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("id")?.let { have.add(it) }
        }
        var added = 0
        for (track in tracks) {
            if (!have.add(track.id)) continue
            arr.put(track.json())
            added++
        }
        if (added == 0) return 0
        all.put(name, arr)
        save(all)
        Diag.i("плейлист «$name»: в конец добавлено $added, стало ${arr.length()}")
        return added
    }

    /**
     * Кладёт треки **в начало** списка, пропуская уже лежащие в нём.
     * Порядок внутри добавляемых сохраняется. Списка ещё нет — заводит.
     *
     * Нужно понравившемуся ([Likes]): его читают сверху, от свежего
     * к давнему.
     *
     * @return сколько треков действительно добавилось
     */
    fun prependToPlaylist(name: String, tracks: List<Track>): Int {
        if (tracks.isEmpty()) return 0
        val all = raw()
        val old = all.optJSONArray(name) ?: JSONArray()
        val have = HashSet<String>(old.length())
        for (i in 0 until old.length()) {
            old.optJSONObject(i)?.optString("id")?.let { have.add(it) }
        }
        val merged = JSONArray()
        var added = 0
        for (track in tracks) {
            if (track.id.isBlank() || !have.add(track.id)) continue
            merged.put(track.json())
            added++
        }
        if (added == 0) return 0
        for (i in 0 until old.length()) {
            old.optJSONObject(i)?.let { merged.put(it) }
        }
        all.put(name, merged)
        save(all)
        Diag.i("плейлист «$name»: в начало добавлено $added, стало ${merged.length()}")
        return added
    }

    fun removeFromPlaylist(name: String, videoId: String) {
        val all = raw()
        val arr = all.optJSONArray(name) ?: return
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != videoId) kept.put(o)
        }
        all.put(name, kept)
        save(all)
        Diag.i("плейлист «$name»: $videoId убран, стало ${kept.length()}")
    }

    /** В каких списках этот трек уже лежит — чтобы кнопка «плюс» знала своё состояние. */
    fun playlistsWith(videoId: String): Set<String> =
        playlistNames().filterTo(mutableSetOf()) { name ->
            playlist(name).any { it.id == videoId }
        }

    private fun raw(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_PLAYLISTS, "{}").orEmpty()) }
            .getOrElse { JSONObject() }

    private fun save(all: JSONObject) {
        prefs.edit().putString(KEY_PLAYLISTS, all.toString()).apply()
        changed()
    }

    /**
     * Кладёт трек на место `at` — для «Отменить» у дизлайка: трек
     * возвращается в понравившееся туда же, откуда ушёл.
     */
    fun insertIntoPlaylist(name: String, track: Track, at: Int) {
        val all = raw()
        val old = all.optJSONArray(name) ?: JSONArray()
        val out = JSONArray()
        var placed = false
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("id") == track.id) return
            if (i == at) { out.put(track.json()); placed = true }
            out.put(o)
        }
        if (!placed) out.put(track.json())
        all.put(name, out)
        save(all)
        Diag.i("плейлист «$name»: ${track.id} возвращён на место ${at + 1}")
    }

    /**
     * Кладёт треки по местам `at` одной записью — для импорта из Яндекса
     * ([YandexImport]): находки приезжают пачками, а порядок нужен как в
     * Яндексе. Места считаются по очереди, с учётом уже вставленных; уже
     * лежащие в списке треки пропускаются.
     */
    fun insertManyIntoPlaylist(name: String, placed: List<Pair<Track, Int>>) {
        if (placed.isEmpty()) return
        val all = raw()
        val arr = all.optJSONArray(name) ?: JSONArray()
        val list = (0 until arr.length()).mapNotNullTo(mutableListOf()) { arr.optJSONObject(it) }
        val have = list.mapTo(HashSet()) { it.optString("id") }
        var added = 0
        for ((track, at) in placed) {
            if (!have.add(track.id)) continue
            list.add(at.coerceIn(0, list.size), track.json())
            added++
        }
        if (added == 0) return
        all.put(name, JSONArray(list))
        save(all)
        Diag.i("плейлист «$name»: импорт положил $added, стало ${list.size}")
    }

    // ── Плейлисты без сети ───────────────────────────────────────────────────

    private const val KEY_OFFLINE = "offline_lists"
    private const val KEY_OFFLINE_MOBILE = "offline_on_mobile"

    /** Плейлисты, скачанные для игры без сети, — см. [Offline]. */
    var offlineLists: Set<String>
        get() = runCatching {
            val arr = JSONArray(prefs.getString(KEY_OFFLINE, "[]").orEmpty())
            (0 until arr.length()).mapTo(mutableSetOf()) { arr.optString(it) }
        }.getOrDefault(emptySet())
        set(value) = prefs.edit().putString(KEY_OFFLINE, JSONArray(value.toList()).toString()).apply()

    /** Докачивать плейлисты и по мобильной сети. По умолчанию — только по Wi-Fi. */
    var offlineOnMobile: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_MOBILE, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_MOBILE, value).apply()

    // ── Не нравится ──────────────────────────────────────────────────────────

    private const val KEY_DISLIKES = "dislikes"

    /** Дизлайки — см. [Taste]. Свои у телефона, со сводкой не ездят. */
    fun dislikes(): List<JSONObject> {
        val arr = array(KEY_DISLIKES)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun saveDislikes(list: List<JSONObject>) {
        prefs.edit().putString(KEY_DISLIKES, JSONArray(list).toString()).apply()
    }

    // ── Эквалайзер ───────────────────────────────────────────────────────────

    private const val KEY_EQ = "equalizer"

    /** `{on, preset, gains}` — см. [Eq]. */
    var equalizer: JSONObject?
        get() = runCatching { JSONObject(prefs.getString(KEY_EQ, "").orEmpty()) }.getOrNull()
        set(value) = prefs.edit().putString(KEY_EQ, value?.toString().orEmpty()).apply()

    // ── Обновления приложения ────────────────────────────────────────────────

    private const val KEY_CHECK_UPDATES = "check_updates"
    private const val KEY_UPDATE_CHECKED = "update_checked_at"
    private const val KEY_SEEN_VERSION = "seen_version"

    /** Проверять ли выход новой версии на GitHub — см. [Update]. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean(KEY_CHECK_UPDATES, true)
        set(value) = prefs.edit().putBoolean(KEY_CHECK_UPDATES, value).apply()

    var updateCheckedAt: Long
        get() = prefs.getLong(KEY_UPDATE_CHECKED, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECKED, value).apply()

    /** Версия, чьё «Что нового?» уже закрыли. Пусто — не закрывали ни разу. */
    var seenVersion: String
        get() = prefs.getString(KEY_SEEN_VERSION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SEEN_VERSION, value).apply()

    // ── Недавно слушанное ────────────────────────────────────────────────────

    private const val KEY_RECENT = "recent"
    private const val RECENT_LIMIT = 30

    /**
     * Запоминает трек как последний слушанный. Повторы всплывают наверх,
     * а не плодятся: список должен читаться как история, а не как журнал.
     */
    fun rememberPlayed(track: Track) {
        if (track.id.isBlank()) return
        val kept = JSONArray()
        kept.put(track.json())
        val old = array(KEY_RECENT)
        for (i in 0 until old.length()) {
            if (kept.length() >= RECENT_LIMIT) break
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("id") == track.id) continue
            kept.put(o)
        }
        prefs.edit().putString(KEY_RECENT, kept.toString()).apply()
    }

    /** История прослушивания, свежее — первым. */
    fun recentTracks(): List<Track> = array(KEY_RECENT).tracks()

    fun clearRecent() {
        prefs.edit().remove(KEY_RECENT).apply()
    }

    // ── Может понравиться ────────────────────────────────────────────────────

    private const val KEY_PICKS = "picks"
    private const val KEY_PICKS_AT = "picks_at"
    private const val KEY_PICKS_SEED = "picks_seed"

    /** Последняя собранная подборка. Пустая — значит ещё не собирали. */
    fun picks(): List<Track> = array(KEY_PICKS).tracks()

    /**
     * Отпечаток вкуса, по которому собиралась подборка. Сменился — значит
     * человек слушает уже другое, и подборку пора пересобрать, не дожидаясь
     * срока годности.
     */
    fun picksSeed(): String = prefs.getString(KEY_PICKS_SEED, "").orEmpty()

    /** Сколько подборка лежит. Для несобранной — заведомо много. */
    fun picksAge(): Long {
        val at = prefs.getLong(KEY_PICKS_AT, 0L)
        return if (at <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - at
    }

    fun savePicks(tracks: List<Track>, seed: String) {
        val arr = JSONArray()
        tracks.forEach { arr.put(it.json()) }
        prefs.edit()
            .putString(KEY_PICKS, arr.toString())
            .putString(KEY_PICKS_SEED, seed)
            .putLong(KEY_PICKS_AT, System.currentTimeMillis())
            .apply()
    }

    // ── Треки в хранилище ────────────────────────────────────────────────────

    /**
     * Трек хранится целиком, а не одним идентификатором: иначе за каждым
     * названием пришлось бы идти в API, а смысл своих списков ровно в том,
     * чтобы они открывались мгновенно и работали офлайн.
     */
    private fun Track.json(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("art", artworkUrl.orEmpty())

    /** Переименование подставляется при чтении: оно может появиться позже записи. */
    private fun JSONArray?.tracks(): List<Track> {
        val arr = this ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(
                id = id,
                title = trackName(id) ?: plainText(o.optString("title")).ifBlank { id },
                artist = plainText(o.optString("artist")),
                artworkUrl = o.optString("art").takeIf { it.isNotBlank() }
                    ?: Radio.thumbnailUrl(id)
            )
        }
    }

    private fun array(key: String): JSONArray =
        runCatching { JSONArray(prefs.getString(key, "[]").orEmpty()) }.getOrElse { JSONArray() }

    // ── Перенос ключа с компьютера ───────────────────────────────────────────

    /**
     * Подхватывает ключ из файла `api-key.txt` в папке кэша, если он там есть,
     * и сразу удаляет файл.
     *
     * Ключ длинный, набирать его на телефоне мучительно, а вставить мышью
     * на компьютере — секунда. Поэтому: положить файл рядом с треками
     * (`adb push`), приложение заберёт его при следующем запуске.
     *
     * Файл удаляется намеренно: ключ должен жить в настройках приложения,
     * а не валяться в открытой папке.
     *
     * @return true, если ключ был принят
     */
    fun importKeyFromFile(context: Context): Boolean {
        val file = File(Cache.dir(context), "api-key.txt")
        if (!file.isFile) return false
        val key = runCatching {
            file.readLines().map(String::trim).firstOrNull { it.isNotBlank() }
        }.getOrNull()
        file.delete()
        if (key.isNullOrBlank()) return false
        apiKey = key
        return true
    }
}
