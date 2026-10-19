package com.lazymusic.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Оболочка приложения: четыре вкладки, мини-плеер над ними и настройки.
 *
 * Проигрыванием заведует [PlayerHost], который говорит со службой. Здесь
 * только состояние экранов — вкладка, поиск, открытое меню трека.
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    private lateinit var host: PlayerHost

    // Вкладки. Открываемся на «Главной»: «Сейчас» при холодном запуске пусто
    private var tab by mutableStateOf(Tab.HOME)

    // Поиск
    private var query by mutableStateOf("")
    private var playlistUrl by mutableStateOf("")
    private var results by mutableStateOf(emptyList<Track>())
    private var resultsTitle by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)

    /** Какая именно работа идёт — чтобы кружок крутился в нужной кнопке. */
    private var job by mutableStateOf<Job?>(null)

    /** Закладка на следующую страницу находок. Пусто — страница последняя. */
    private var searchPage by mutableStateOf<String?>(null)

    /** Подсказки под строкой поиска и задача, которая их тянет. */
    private var hints by mutableStateOf(emptyList<String>())

    /** Свои треки под набранное — в подсказках идут первыми. */
    private var ownHints by mutableStateOf(emptyList<Find.Hit>())

    /**
     * С какой вкладки пришли на «Сейчас». «Назад» возвращает туда: включил
     * песню из поиска — и обратно к тем же находкам, а не на «Главную»
     * с пустой строкой (жалоба пользователя 2026-10-18).
     */
    private var cameFrom by mutableStateOf<Tab?>(null)

    /** Трек, пришедший ссылкой lazymusic:// с переходника, — играет, как подключится плеер. */
    private var opening by mutableStateOf<Track?>(null)
    private var hintJob: kotlinx.coroutines.Job? = null

    // Прочее состояние экранов
    private var menuTrack by mutableStateOf<Track?>(null)
    private var settingsOpen by mutableStateOf(false)
    /** Открытая группа настроек; null — список групп. */
    private var settingsGroup by mutableStateOf<SettingsGroup?>(null)
    private var eqOpen by mutableStateOf(false)

    /** Открытая страница исполнителя — поверх вкладки, «назад» к ней же. */
    private var artist by mutableStateOf<String?>(null)
    private var revision by mutableStateOf(0)
    private var cachedIds by mutableStateOf(emptySet<String>())
    private var cachedTracks by mutableStateOf(emptyList<Track>())
    private var recent by mutableStateOf(emptyList<Track>())

    private enum class Tab { HOME, NOW, LIBRARY, SEARCH }

    private enum class Job { SEARCH, MORE, PLAYLIST, ENGINE }

    private companion object {
        /** Столько кружок держится на экране даже у мгновенного ответа. */
        const val MIN_BUSY_MS = 450L

        /** Пауза после последней буквы, прежде чем спрашивать подсказки. */
        const val HINT_DELAY_MS = 250L
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Язык системы могли сменить, пока приложение жило
        Lang.apply()
        host = PlayerHost(this)
        requestNotificationsIfNeeded()
        // Без ключа — сразу в группу, где он задаётся
        if (!Settings.hasApiKey) {
            settingsOpen = true
            settingsGroup = SettingsGroup.MAIN
        }
        WhatsNew.check(this)
        // Проверка импорта на образцах ответов Яндекса — из Испании он
        // отвечает 451. Только в отладочной сборке:
        // `am start -n … --es yandex_api http://127.0.0.1:8765` при `adb reverse`
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra("yandex_api")?.let { YandexImport.debugApi = it }
            // Проверка запасного пути добычи: `--ez fast_off true`
            if (intent.getBooleanExtra("fast_off", false)) Engine.pauseFast()
            // Проверка входа для yt-dlp: `--ez bot_mode true` — как будто YouTube
            // потребовал подтвердить, что не бот
            if (intent.getBooleanExtra("bot_mode", false) && YtLogin.botCheck(this) == null) YtLogin.ask(this)
            // Как выглядит просьба войти: `--ez ask_login true`
            if (intent.getBooleanExtra("ask_login", false)) YtLogin.ask(this)
        }
        // Поворот экрана пересоздаёт окно с тем же intent — трек не повторяем
        if (savedInstanceState == null) takeLink(intent)
        setContent { LazyMusicTheme { Shell() } }
    }

    /** Ссылка пришла в уже открытое приложение (singleTask). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLink(intent)
    }

    /** `lazymusic://play/<id>?t=<название>&a=<исполнитель>` — как на ПК (links.rs). */
    private fun takeLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!uri.scheme.equals("lazymusic", ignoreCase = true) || uri.host != "play") return
        val id = uri.pathSegments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) } ?: return
        opening = Track(
            id = id,
            title = uri.getQueryParameter("t").orEmpty().take(200).ifBlank { id },
            artist = uri.getQueryParameter("a").orEmpty().take(200),
            artworkUrl = Radio.thumbnailUrl(id)
        )
        Diag.i("ссылка: открыть $id")
    }

    override fun onStart() {
        super.onStart()
        host.connect()
        refreshCache()
        recent = Settings.recentTracks()
        // Подборка собирается полминуты, поэтому только когда протухла
        Recommend.refreshIfStale(this)
        // Свежий yt-dlp: сам процесс живёт сутками, так что «при запуске»
        // для него значит мало — проверяем и при каждом возвращении на экран
        Engine.refreshIfDue(this)
        // Сюда же возвращаются с экрана YouTube Music — список мог вырасти
        Likes.load()
        revision++
        // Ввоз идёт до появления экрана, поэтому сказать о нём можно
        // только здесь — и только раз
        if (Import.arrived > 0) {
            status = tr("Ввезено треков: {число}", "число" to Import.arrived)
            Import.clear()
            revision++
        }
        // Android замораживает приложение, когда экран гаснет, и раздача
        // молчит до возвращения. Обычно она оживает сама, но если поток
        // приёма всё же оборвался — здесь он поднимется заново. При живом
        // сервере вызов ничего не делает
        if (Settings.shareLibrary) Share.start()
        // Пока экран был закрыт, на компьютере или в модуле могли что-то
        // поправить — сводим сразу, без паузы
        Hub.soon(0, "открытие")
        // Импорт ждал YouTube — может, его как раз включили (VPN), не ждать минуту
        YandexImport.nudge()
        // Новая версия на GitHub — проверка через 20 с и не чаще раза в 6 ч
        Update.onStart(this)
    }

    /** Строка поиска на «Главной»: ищет на вкладке «Поиск». */
    private fun searchFromHome() {
        if (query.isBlank()) {
            tab = Tab.SEARCH
            return
        }
        tab = Tab.SEARCH
        val q = query.trim()
        if (Links.videoIdFrom(q) != null || Links.playlistIdFrom(q) != null || Yandex.link(q) != null) {
            // Вставили ссылку — открываем её, а не ищем
            playlistUrl = q
            query = ""
            openLink()
        } else {
            runSearch()
        }
    }

    /** Вкладку сменили — страницы поверх неё закрываются. */
    private fun goTab(t: Tab) {
        tab = t
        artist = null
        settingsOpen = false
    }

    /** На «Сейчас» — с памятью, откуда пришли (см. [cameFrom]). */
    private fun goNow() {
        if (tab != Tab.NOW) cameFrom = tab
        goTab(Tab.NOW)
    }

    /** Открывает адрес в браузере — для подсказки, где взять ключ. */
    private fun openInBrowser(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { status = tr("не нашлось, чем открыть ссылку") }
    }

    /**
     * Журнал — файлом через «Поделиться»: в Telegram, почту, куда угодно.
     * Собирается в фоне — в нём сводка по плейлистам и до полумегабайта строк.
     */
    private fun sendLog() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { Diag.export(this@MainActivity) }.getOrNull() }
            if (file == null) {
                Plaques.toast(tr("Не вышло собрать логи"))
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, tr("Логи Lazy Music"))
                // Через окно выбора право читать файл доезжает только с clipData
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(send, tr("Отправить логи"))) }
                .onFailure { Plaques.toast(tr("Нет приложения, которое примет файл")) }
        }
    }

    /** Сердце: положить трек в понравившееся или убрать оттуда. */
    private fun like(track: Track) {
        Likes.toggle(track)
        revision++
    }

    /**
     * Экран, который забирает понравившееся из YouTube Music. Отдельная
     * активность: там свой браузер со своим входом в аккаунт, и смешивать
     * его жизнь с плеером незачем. Что он принёс, подхватит [onStart].
     */
    private fun openYtMusic() {
        startActivity(Intent(this, YtMusicActivity::class.java))
    }

    /** Скачать трек в кэш. Ход показывает сама кнопка, поэтому busy не трогаем. */
    private fun download(track: Track) = Downloads.start(this, track)

    private fun removeDownload(track: Track) {
        Downloads.remove(this, track.id)
        refreshCache()
    }

    /**
     * Стирает скачанное целиком. Играющий трек при этом не обрывается:
     * Android держит уже открытый файл живым до конца чтения.
     */
    private fun clearCache() {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) {
                val files = Cache.dir(this@MainActivity).listFiles()
                    ?.filter { it.isFile && it.name != "api-key.txt" }
                    .orEmpty()
                val bytes = files.sumOf { it.length() }
                files.forEach { it.delete() }
                bytes
            }
            refreshCache()
            status = tr("кэш очищен, освободилось {МБ} МБ", "МБ" to (freed / (1024 * 1024)))
        }
    }

    /** Играть из недавнего. Тот же трек не перезапускаем — просто пауза/пуск. */
    private fun playRecent(index: Int) {
        val track = recent.getOrNull(index) ?: return
        if (track.id == host.current?.id) host.toggle() else host.play(recent, index)
    }

    /** Играть из подборки. Очередью становится вся витрина — есть что слушать дальше. */
    private fun playPick(index: Int) {
        val picks = Recommend.tracks
        if (index !in picks.indices) return
        host.play(picks, index)
        goNow()
    }

    override fun onStop() {
        host.disconnect()
        super.onStop()
    }

    // ── Действия ─────────────────────────────────────────────────────────────

    /**
     * Перечитывает папку кэша. Имя файла — идентификатор ролика, названия
     * взять неоткуда, поэтому подставляем своё переименование, если оно есть,
     * а иначе сам идентификатор. Зато это работает без ключа и без сети.
     */
    private fun refreshCache() {
        lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) {
                Cache.dir(this@MainActivity).listFiles()
                    ?.filter { it.isFile && it.name != "api-key.txt" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.mapNotNull { it.name.substringBeforeLast('.', "").takeIf(String::isNotBlank) }
                    .orEmpty()
            }
            cachedIds = ids.toSet()
            cachedTracks = ids.map { id ->
                Track(
                    id = id,
                    title = Settings.trackName(id) ?: id,
                    artworkUrl = Radio.thumbnailUrl(id)
                )
            }
        }
    }

    /**
     * Набранное изменилось — просим подсказки, но не сразу.
     *
     * Задержка тут обязательна: без неё запрос уходил бы на каждую букву,
     * и к слову из восьми букв мы бы восемь раз сходили в сеть, чтобы
     * показать ответ на седьмую. Четверть секунды — примерно пауза между
     * нажатиями при быстром наборе.
     */
    private fun onQueryChanged(text: String) {
        query = text
        hintJob?.cancel()
        if (text.isBlank()) {
            hints = emptyList()
            ownHints = emptyList()
            return
        }
        hintJob = lifecycleScope.launch {
            // Своё ищется на месте и сразу, без задержки: сети тут нет
            ownHints = withContext(Dispatchers.Default) { Find.inLibrary(text) }
            delay(HINT_DELAY_MS)
            hints = Suggest.forQuery(text)
        }
    }

    /** Подсказки больше не нужны: либо выбрали, либо ищут набранное. */
    private fun dropHints() {
        hintJob?.cancel()
        hints = emptyList()
        ownHints = emptyList()
    }

    /** Свой трек из подсказок — играет в своём плейлисте, с его места. */
    private fun playOwn(hit: Find.Hit) {
        dropHints()
        val list = Settings.playlist(hit.playlist)
        val at = list.indexOfFirst { it.id == hit.track.id }
        if (at >= 0) host.play(list, at) else host.play(listOf(hit.track), 0)
        goNow()
    }

    private fun useHint(text: String) {
        query = text
        dropHints()
        runSearch()
    }

    private fun runSearch() {
        dropHints()
        val q = query.trim()
        if (q.isEmpty()) return
        // Старые находки убираем сразу: экран обязан измениться в момент
        // нажатия, иначе непонятно, началось что-нибудь или нет
        results = emptyList()
        resultsTitle = ""
        searchPage = null
        work(tr("ищу «{запрос}»…", "запрос" to q), Job.SEARCH) {
            val found = YouTubeApi.search(q)
            if (found.tracks.isEmpty()) error(tr("ничего не нашлось"))
            results = found.tracks
            resultsTitle = tr("Поиск: {запрос}", "запрос" to q)
            searchPage = found.nextPage
            tr("найдено: {число}", "число" to found.tracks.size)
        }
    }

    /**
     * Дозагрузка следующей страницы находок.
     *
     * Отдельная кнопка, а не подгрузка при прокрутке: страница стоит те же
     * 100 единиц квоты, что и новый поиск, — сотую долю суточной. Тратить
     * её надо по нажатию, а не оттого, что список случайно долистали.
     */
    private fun loadMore() {
        val q = query.trim()
        val page = searchPage ?: return
        if (q.isEmpty()) return
        work(tr("догружаю…"), Job.MORE) {
            val found = YouTubeApi.search(q, page)
            // Повторы бывают: страницы у API слегка перекрываются
            val known = results.mapTo(HashSet()) { it.id }
            val fresh = found.tracks.filterNot { it.id in known }
            results = results + fresh
            searchPage = found.nextPage
            if (fresh.isEmpty()) tr("новых находок нет") else tr("добавлено: {число}", "число" to fresh.size)
        }
    }

    /**
     * Открывает вставленную ссылку — на плейлист или на отдельный ролик.
     *
     * Поле одно на оба случая: пользователю незачем знать, какую из двух
     * ссылок он вставил. Плейлист берётся предпочтительно, но только если
     * есть ключ; ролик играется **без ключа вообще** — его разбирает yt-dlp.
     * Благодаря этому приложение полезно сразу после установки.
     */
    private fun openLink() {
        val raw = playlistUrl.trim()
        if (raw.isEmpty()) return
        // Яндекс Музыка — импорт в свой плейлист, ход виден на плашке
        if (YandexImport.start(raw)) return
        val playlistId = Links.playlistIdFrom(raw)
        val videoId = Links.videoIdFrom(raw)

        if (playlistId != null && Settings.hasApiKey) {
            results = emptyList()
            resultsTitle = ""
            work(tr("открываю плейлист…"), Job.PLAYLIST) {
                val loaded = YouTubeApi.playlistItems(playlistId)
                if (loaded.isEmpty()) error(tr("в плейлисте нет доступных треков"))
                results = loaded
                resultsTitle = YouTubeApi.playlistTitle(playlistId) ?: tr("Плейлист")
                tr("загружено: {число}", "число" to loaded.size)
            }
            return
        }

        if (videoId != null) {
            work(tr("открываю ролик…"), Job.PLAYLIST) {
                val track = Links.track(this@MainActivity, videoId)
                results = listOf(track)
                resultsTitle = tr("По ссылке")
                host.play(listOf(track), 0)
                goNow()
                tr("играю: {название трека}", "название трека" to track.title)
            }
            return
        }

        status = if (playlistId != null)
            tr("это плейлист, а для плейлистов нужен ключ API — задайте его в настройках")
        else
            tr("не разобрал ссылку: нужна ссылка на ролик или плейлист YouTube")
    }

    /**
     * Радио. Вся работа — в [RadioJob], то есть в стороне от этого экрана.
     *
     * Здесь нарочно нет ни [work], ни `lifecycleScope`: сбор длится
     * десятки секунд, и всё это время человек волен свернуть приложение,
     * погасить экран или уйти в другую вкладку. Раньше это сбор и убивало.
     */
    private fun startRadio(seed: Track) {
        RadioJob.start(this, seed)
        goNow()
    }

    private fun updateEngine() = work(tr("проверяю yt-dlp…"), Job.ENGINE) {
        // Единственная здесь блокирующая работа — её и уводим в фон сами
        val outcome = withContext(Dispatchers.IO) { Engine.update(this@MainActivity, "вручную") }
        "yt-dlp ${outcome.describe()}"
    }

    /**
     * Общая обвязка: занятость и показ ошибки текстом.
     *
     * Блок выполняется **на главном потоке** — и это важно: `MediaController`
     * принимает команды только оттуда, иначе падает с «called from a wrong
     * thread». Долгая работа уходит в фон внутри себя: [YouTubeApi] и [Radio]
     * сами переключаются на [Dispatchers.IO].
     */
    private fun work(pending: String, kind: Job, block: suspend () -> String) {
        if (busy) return
        busy = true
        job = kind
        status = pending
        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching { block() }
                .getOrElse { it.message ?: it::class.simpleName.orEmpty() }

            // Поиск нередко отвечает за треть секунды. Без этой задержки
            // кружок моргает быстрее, чем глаз успевает заметить, и человек
            // жмёт кнопку снова, думая, что промахнулся.
            val shown = SystemClock.elapsedRealtime() - started
            if (shown < MIN_BUSY_MS) delay(MIN_BUSY_MS - shown)

            status = outcome
            busy = false
            job = null
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── Оболочка ─────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Shell() = Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(if (settingsOpen) settingsGroup?.let { tr(it.title) } ?: tr("Настройки") else tabTitle(tab))
                    },
                    navigationIcon = {
                        if (settingsOpen) {
                            IconButton(onClick = ::settingsBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = tr("Назад"))
                            }
                        }
                    },
                    actions = {
                        // Эквалайзер — значком приложения, как кнопка рядом с громкостью на ПК
                        IconButton(onClick = { eqOpen = true }) {
                            Icon(
                                LazyEqIcon,
                                contentDescription = tr("Эквалайзер"),
                                tint = if (Eq.on) androidx.compose.ui.graphics.Color(0xFFFF4D55)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!settingsOpen) {
                            IconButton(onClick = { settingsOpen = true }) {
                                Icon(Icons.Default.Settings, contentDescription = tr("Настройки"))
                            }
                        }
                    }
                )
                // Занятость должна быть видна на любом экране: радио запускают
                // и с «Сейчас», и из меню трека, а раньше её показывал только
                // поиск — потому нажатие на «Радио» и выглядело как промах
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    if (busy || RadioJob.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            Column {
                // Плашки — над плеером: «Отменить» у дизлайка, ход скачивания, новая версия
                PlaqueHost()
                if (tab != Tab.NOW || artist != null || settingsOpen) MiniPlayer(host, ::like) { goNow() }
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.HOME,
                        onClick = {
                            goTab(Tab.HOME)
                            recent = Settings.recentTracks()
                            Recommend.refreshIfStale(this@MainActivity)
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(tr("Главная")) }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.NOW,
                        onClick = { goNow() },
                        icon = { Icon(Icons.Default.PlayCircle, contentDescription = null) },
                        // Короче, чем заголовок: «Играет сейчас» в нижнем меню
                        // переносится на две строки
                        label = { Text(tr("Сейчас"), maxLines = 1) }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.LIBRARY,
                        onClick = { goTab(Tab.LIBRARY); revision++ },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                        label = { Text(tr("Плейлисты")) }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SEARCH,
                        onClick = { goTab(Tab.SEARCH) },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text(tr("Поиск")) }
                    )
                }
            }
        }
    ) { pad ->
        val keep = rememberSaveableStateHolder()
        Box(Modifier.fillMaxSize().padding(pad)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val openArtist = artist
            when {
                settingsOpen -> SettingsPage()
                openArtist != null -> ArtistScreen(
                    name = openArtist,
                    revision = revision,
                    cachedIds = cachedIds,
                    currentId = host.current?.id.orEmpty(),
                    onBack = { artist = null },
                    onOpenArtist = { artist = it },
                    onPlay = { list, index -> host.play(list, index); goNow() },
                    onRadio = ::startRadio,
                    onTrackMenu = { menuTrack = it }
                )
                // Каждая вкладка помнит своё — открытый плейлист, место
                // прокрутки, находки, — пока на другой
                else -> keep.SaveableStateProvider(tab) { when (tab) {
                Tab.HOME -> HomeScreen(
                    recent = recent,
                    picks = Recommend.tracks,
                    picksBusy = Recommend.running,
                    cachedIds = cachedIds,
                    currentId = host.current?.id.orEmpty(),
                    playing = host.playing,
                    onPlayRecent = ::playRecent,
                    onPlayPick = ::playPick,
                    onRefreshPicks = { Recommend.refresh(this@MainActivity) },
                    onTrackMenu = { menuTrack = it },
                    onArtist = { artist = it },
                    query = query,
                    onQueryChange = ::onQueryChanged,
                    hints = hints,
                    ownHints = ownHints,
                    onOwnHint = ::playOwn,
                    onUseHint = { query = it; dropHints(); searchFromHome() },
                    onSearch = ::searchFromHome
                )

                Tab.NOW -> NowPlayingScreen(
                    host = host,
                    cachedIds = cachedIds,
                    busy = busy,
                    gathering = RadioJob.phase == RadioJob.Phase.NEAR,
                    widening = RadioJob.phase == RadioJob.Phase.WIDE,
                    status = RadioJob.note ?: status.takeIf { busy },
                    onRadio = ::startRadio,
                    onDownload = ::download,
                    onRemoveDownload = ::removeDownload,
                    onAddToPlaylist = { menuTrack = it },
                    onLike = ::like,
                    onArtist = { artist = it }
                )

                Tab.LIBRARY -> LibraryScreen(
                    revision = revision,
                    cachedIds = cachedIds,
                    cachedTracks = cachedTracks,
                    currentId = host.current?.id.orEmpty(),
                    onPlay = { list, index -> host.play(list, index); goNow() },
                    onTrackMenu = { menuTrack = it },
                    onChanged = { revision++ },
                    onImportLikes = ::openYtMusic
                )

                Tab.SEARCH -> SearchScreen(
                    query = query,
                    onQueryChange = ::onQueryChanged,
                    hints = hints,
                    ownHints = ownHints,
                    onOwnHint = ::playOwn,
                    onUseHint = ::useHint,
                    playlistUrl = playlistUrl,
                    onPlaylistUrlChange = { playlistUrl = it },
                    busy = busy,
                    searching = job == Job.SEARCH,
                    opening = job == Job.PLAYLIST,
                    loadingMore = job == Job.MORE,
                    hasMore = searchPage != null,
                    onLoadMore = ::loadMore,
                    status = status,
                    results = results,
                    resultsTitle = resultsTitle,
                    cachedIds = cachedIds,
                    currentId = host.current?.id.orEmpty(),
                    hasKey = Settings.hasApiKey,
                    onSearch = ::runSearch,
                    onOpenPlaylist = ::openLink,
                    onPlay = { index -> host.play(results, index); goNow() },
                    onTrackMenu = { menuTrack = it }
                )
                } }
            }
        }
            // «Что нового?» — в правом нижнем углу, над плеером
            WhatsNewCard(Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }

        // Системная «назад» возвращает на «Главную», а не сворачивает
        // приложение. Внутренние экраны библиотеки перехватывают её раньше —
        // у вложенного обработчика приоритет выше.
        BackHandler(enabled = tab != Tab.HOME && !settingsOpen && artist == null) {
            val back = cameFrom?.takeIf { tab == Tab.NOW && it != Tab.HOME }
            cameFrom = null
            if (back != null) {
                tab = back
                return@BackHandler
            }
            tab = Tab.HOME
            recent = Settings.recentTracks()
            Recommend.refreshIfStale(this@MainActivity)
        }
        BackHandler(enabled = settingsOpen) { settingsBack() }
        // Закрыли настройки — в следующий раз они откроются со списка групп
        LaunchedEffect(settingsOpen) { if (!settingsOpen) settingsGroup = null }

        menuTrack?.let { track ->
            TrackMenu(
                track = track,
                host = host,
                cached = track.id in cachedIds,
                onDismiss = { menuTrack = null },
                onRadio = ::startRadio,
                onArtist = { settingsOpen = false; artist = it },
                onDownload = ::download,
                onRemoveDownload = ::removeDownload,
                onChanged = { revision++ }
            )
        }

        if (eqOpen) EqSheet(host) { eqOpen = false }

        // Понравившееся — обычный плейлист, и правят его не только сердцем:
        // галочкой в меню трека, ввозом, забором из YouTube Music. Любая
        // правка библиотеки двигает revision — по нему и перечитываем
        LaunchedEffect(revision) { Likes.load() }

        // Сводка с компьютером поменяла библиотеку — перечитать списки
        LaunchedEffect(Hub.applied) { if (Hub.applied > 0) revision++ }

        // Импорт из Яндекса положил находки в плейлист
        LaunchedEffect(YandexImport.changes) { if (YandexImport.changes > 0) revision++ }

        // Трек по ссылке lazymusic:// — играем, как только плеер подключился
        LaunchedEffect(host.connected, opening) {
            val track = opening ?: return@LaunchedEffect
            if (!host.connected) return@LaunchedEffect
            opening = null
            host.play(listOf(track), 0)
            goNow()
        }
        ImportMissingDialog()

        // Кэш мог пополниться, пока играли или качали
        LaunchedEffect(host.current?.id, Downloads.version) { refreshCache() }

        // Заиграло другое — «Продолжить» на Главной показывает его, как на ПК
        LaunchedEffect(host.current?.id) { recent = Settings.recentTracks() }
    }

    private fun tabTitle(t: Tab) = when (t) {
        Tab.HOME -> "Lazy Music"
        Tab.NOW -> tr("Играет сейчас")
        Tab.LIBRARY -> tr("Плейлисты")
        Tab.SEARCH -> tr("Поиск")
    }

    /**
     * Настройки — как на ПК, группами: сначала список из пяти групп
     * ([SettingsGroups]), в группе — её разделы. Одной лентой их стало
     * больше, чем удобно листать (2026-10-19).
     */
    @Composable
    private fun SettingsPage() {
        val group = settingsGroup
        // Своя прокрутка у каждой группы: открыл другую — она сверху
        val scroll = key(group) { rememberScrollState() }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 6.dp)) {
            when (group) {
                null -> SettingsGroups { settingsGroup = it }
                SettingsGroup.MAIN -> {
                    LanguageSection()
                    UpdatesSection(this@MainActivity)
                    var keyInput by remember { mutableStateOf(Settings.apiKey) }
                    // Ключ прячем: он виден через плечо и попадает в снимки экрана
                    var reveal by remember { mutableStateOf(false) }
                    Section(
                        "YouTube Data API",
                        tr("Создайте ключ в Google Cloud Console: проект → «YouTube Data API v3» → «Создать учётные данные» → ключ API.")
                    ) {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text(tr("Ключ YouTube Data API v3")) },
                            singleLine = true,
                            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { reveal = !reveal }) {
                                    Icon(
                                        if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (reveal) tr("Скрыть") else tr("Показать")
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row {
                            TextButton(onClick = {
                                Settings.apiKey = keyInput
                                Plaques.toast(if (keyInput.isNotBlank()) tr("Ключ сохранён") else tr("Ключ убран"))
                            }) { Text(tr("Сохранить")) }
                            TextButton(onClick = {
                                openInBrowser("https://console.cloud.google.com/apis/library/youtube.googleapis.com")
                            }) { Text(tr("Открыть Google Cloud Console")) }
                        }
                    }
                }
                SettingsGroup.MUSIC -> {
                    Section(tr("Радио")) {
                        var wide by remember { mutableStateOf(Settings.wideRadio) }
                        SwitchRow(
                            tr("Разнообразить радио"),
                            tr("Подбирает похожие треки. Сбор занимает до минуты и идёт уже во время игры."),
                            wide
                        ) { wide = it; Settings.wideRadio = it }
                    }
                    DislikesSection()
                }
                SettingsGroup.LINK -> {
                    Section(tr("Компьютер")) { HubSection() }
                    Section(tr("Модуль Foundry")) {
                        ShareSection()
                        TextButton(onClick = {
                            settingsOpen = false
                            openYtMusic()
                        }) {
                            Text(tr("Забрать понравившееся из YouTube Music"))
                        }
                    }
                }
                SettingsGroup.FILES -> {
                    Section(tr("Скачанное")) {
                        // Плейлисты без сети (Offline) по умолчанию качаются только по Wi-Fi
                        var mobile by remember { mutableStateOf(Settings.offlineOnMobile) }
                        SwitchRow(
                            tr("Скачивать плейлисты по мобильной сети"),
                            if (mobile) tr("Плейлисты качаются и по мобильной сети")
                            else tr("Плейлисты, скачанные для игры без сети, докачиваются только по Wi-Fi"),
                            mobile
                        ) {
                            mobile = it
                            Settings.offlineOnMobile = it
                            if (it) Offline.kick()
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr("В кэше: {число} шт., {МБ} МБ", "число" to cachedIds.size, "МБ" to (Cache.sizeBytes(this@MainActivity) / (1024 * 1024))),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (cachedIds.isNotEmpty()) {
                            // Подтверждение вторым нажатием: окно внутри окна здесь
                            // было бы капризным, а потерять скачанное обидно
                            var confirm by remember { mutableStateOf(false) }
                            TextButton(onClick = {
                                if (confirm) {
                                    clearCache()
                                    confirm = false
                                } else confirm = true
                            }) {
                                Text(
                                    if (confirm) tr("Точно удалить всё скачанное?") else tr("Очистить кэш"),
                                    color = if (confirm) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                SettingsGroup.SERVICE -> {
                    Section("yt-dlp") {
                        // Обновляется он сам — см. Engine. Кнопка оставлена
                        // на случай, когда хочется убедиться своими глазами
                        Text(
                            tr("yt-dlp {версия yt-dlp} — обновляется сам, при запуске и когда " +
                                "добыча звука ломается.", "версия yt-dlp" to (Engine.version ?: tr("встроенный"))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = ::updateEngine, enabled = !busy) {
                            Text(tr("Проверить обновление"))
                        }
                        Notice(status, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Section(tr("Журнал")) {
                        // Ник Telegram в пояснении — ссылка (LinkHint)
                        LinkHint(tr("Все логи. В случае какого-либо сбоя отправьте логи @keyno21"))
                        TextButton(onClick = ::sendLog) { Text(tr("Отправить логи")) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    /** «Назад» в настройках: из группы — к списку групп, из списка — вон. */
    private fun settingsBack() {
        if (settingsGroup != null) settingsGroup = null else settingsOpen = false
    }

    /**
     * Сводка с Lazy Music на компьютере — см. [Hub].
     *
     * Сопряжение — отдельным окном поверх настроек: там поиск в сети и ввод
     * кода, и смешивать это с остальными настройками незачем.
     */
    @Composable
    private fun HubSection() {
        var pairing by remember { mutableStateOf(false) }
        if (Hub.paired) {
            Text(
                tr("Сопряжён с «{имя компьютера}». Плейлисты, понравившееся и свои " +
                    "названия сводятся сами. Последняя сводка: {когда}.", "имя компьютера" to Settings.hubName, "когда" to ago(Hub.lastSync)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(onClick = { Hub.soon(0, Hub.MANUAL) }, enabled = !Hub.busy) {
                    Text(if (Hub.busy) tr("Свожу…") else tr("Свести сейчас"))
                }
                TextButton(onClick = { Hub.unpair() }) { Text(tr("Отвязать")) }
            }
        } else {
            Text(
                tr("Lazy Music на компьютере держит общую библиотеку: плейлисты и " +
                    "понравившееся становятся одинаковыми здесь, на ПК и в модуле Foundry."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { pairing = true }) { Text(tr("Сопрячь с компьютером")) }
        }
        Hub.note?.let { Notice(it) }
        if (pairing) PairDialog(onClose = { pairing = false })
    }

    /**
     * Поиск компьютера в сети и ввод кода с его экрана. Не нашёлся сам —
     * можно вписать адрес: гостевые сети и сети с разными подсетями бывают.
     */
    @Composable
    private fun PairDialog(onClose: () -> Unit) {
        val scope = rememberCoroutineScope()
        var searching by remember { mutableStateOf(true) }
        var found by remember { mutableStateOf(emptyList<Hub.Pc>()) }
        var chosen by remember { mutableStateOf<Hub.Pc?>(null) }
        var code by remember { mutableStateOf("") }
        var manual by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var working by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            found = Hub.find()
            chosen = found.firstOrNull { it.pairing } ?: found.firstOrNull()
            searching = false
        }

        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(tr("Сопряжение с компьютером")) },
            text = {
                Column {
                    Text(
                        tr("На компьютере: Lazy Music → Настройки → «Сопрячь телефон». " +
                            "Появится шестизначный код — введите его здесь."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        searching -> {
                            Text(tr("Ищу компьютер в сети…"), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        found.isEmpty() && chosen == null -> {
                            Text(
                                tr("Не нашёл. Lazy Music на компьютере запущена, и " +
                                    "телефон в той же сети Wi-Fi? Если да — впишите адрес " +
                                    "с экрана компьютера."),
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = manual,
                                onValueChange = { manual = it },
                                label = { Text(tr("Адрес компьютера")) },
                                singleLine = true
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    working = true
                                    chosen = Hub.probe(manual)
                                    error = if (chosen == null) tr("По этому адресу Lazy Music не отвечает") else null
                                    working = false
                                }
                            }, enabled = manual.isNotBlank() && !working) { Text(tr("Проверить")) }
                        }
                        else -> {
                            for (pc in found) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = chosen == pc, onClick = { chosen = pc })
                                    Text(pc.name + if (pc.pairing) tr(" — ждёт код") else "")
                                }
                            }
                        }
                    }
                    if (chosen != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                            label = { Text(tr("Код с экрана компьютера")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                    }
                    error?.let { Notice(it) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pc = chosen ?: return@TextButton
                        scope.launch {
                            working = true
                            error = Hub.pair(pc, code)
                            working = false
                            if (error == null) {
                                status = tr("Сопряжено с «{имя компьютера}» — свожу библиотеку", "имя компьютера" to Settings.hubName)
                                onClose()
                            }
                        }
                    },
                    enabled = chosen != null && code.length == 6 && !working
                ) { Text(if (working) tr("Сопрягаю…") else tr("Сопрячь")) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(tr("Отмена")) } }
        )
    }

    /**
     * Раздача библиотеки компьютеру — см. [Share].
     *
     * Адрес и код показываем только при включённой раздаче: выключенная
     * ничего не отдаёт, и цифры рядом с ней сбивали бы с толку.
     */
    @Composable
    private fun ShareSection() {
        var on by remember { mutableStateOf(Settings.shareLibrary) }
        var token by remember { mutableStateOf(Settings.shareToken) }
        // Адрес выдаёт роутер, и он меняется при переподключении к Wi-Fi.
        // Перечитываем при каждом включении раздачи
        val address = remember(on, Share.running) { Share.address() }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = on,
                onCheckedChange = { want ->
                    on = want
                    Settings.shareLibrary = want
                    if (want) {
                        if (!Share.start()) status = tr("Порт {порт} занят — раздача не включилась", "порт" to Share.PORT)
                    } else Share.stop()
                }
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(tr("Отдавать библиотеку компьютеру"))
                Text(
                    tr("Модуль Foundry заберёт плейлисты по локальной сети. Работает, " +
                        "пока приложение открыто или играет музыка: уснувший телефон " +
                        "Android замораживает целиком."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!on) return
        Spacer(Modifier.height(8.dp))
        if (address == null) {
            Text(
                tr("Телефон не в локальной сети — подключись к тому же Wi-Fi, что и компьютер."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            return
        }
        // Выделяемый текст: адрес удобнее скопировать, чем переписывать
        SelectionContainer {
            Column {
                Text(tr("Адрес: {адрес}", "адрес" to address), style = MaterialTheme.typography.bodyMedium)
                Text(tr("Код: {код}", "код" to token), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            tr("Эти две строки один раз вводятся в модуле — кнопка с телефоном над " +
                "списком плейлистов."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { token = Settings.newShareToken() }) {
            Text(tr("Сменить код"))
        }
    }
}

/** Идентификатор ролика YouTube — 11 знаков. */
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
