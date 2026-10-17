package com.lazymusic.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Забирает «Понравившуюся музыку» из YouTube Music — без компьютера
 * и без чьей-либо помощи.
 *
 * ## Почему так, а не иначе
 *
 * У этого списка нет ссылки «поделиться», и по ключу API он недоступен:
 * список личный, ему нужен вход. Первый перенос (2026-10-07) делался
 * с компьютера через браузер, где человек уже залогинен. Здесь то же
 * самое, только браузер свой, внутри приложения:
 *
 * 1. открывается страница списка на music.youtube.com;
 * 2. не залогинен — страница Google сама попросит войти, и человек
 *    входит **на ней**, как в обычном браузере;
 * 3. дальше приложение читает открытую страницу: прокручивает её до
 *    конца (список ленивый, по сотне строк) и берёт из строк
 *    идентификатор, название и исполнителя.
 *
 * ## Чего приложение при этом не делает
 *
 * - Пароль не читает и не хранит: он вводится на странице Google, а код
 *   здесь заглядывает только на страницу списка, и только за строками.
 * - Cookies наружу не передаёт. Вход живёт в хранилище этого встроенного
 *   браузера, чтобы в следующий раз не входить заново; кнопка «Выйти»
 *   стирает его целиком. С 2026-10-18 его видит ещё yt-dlp на этом же
 *   телефоне — только когда YouTube требует подтвердить, что не бот
 *   ([YtLogin]); по сети вход по-прежнему никуда не уходит.
 * - Мостов из страницы в приложение (`addJavascriptInterface`) нет: всё
 *   чтение идёт вызовами отсюда туда, страница приложению ничего прислать
 *   не может.
 * - За пределы Google и YouTube браузер не ходит.
 * - Браузером не притворяется: если Google откажется пускать встроенный
 *   браузер, обходить это здесь не будут — останется ввоз файлом ([Import]).
 *
 * Забранное **добавляется** к понравившемуся ([Likes.addAll]): чего в
 * списке не было, ложится в начало; что было — не трогается. Снятые
 * в YouTube Music лайки отсюда не пропадают.
 */
@OptIn(ExperimentalMaterial3Api::class)
class YtMusicActivity : ComponentActivity() {

    private companion object {
        const val LIKED = "LM"

        /** Обычный вход Google с возвратом на список — на случай, когда страница сама не позвала. */
        const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube" +
            "&continue=https%3A%2F%2Fmusic.youtube.com%2Fplaylist%3Flist%3DLM"

        /** Строка списка на странице YouTube Music. */
        const val ROW = "ytmusic-responsive-list-item-renderer"

        /** Как часто смотрим, что открыто в браузере. */
        const val WATCH_MS = 1_500L

        /** Шаг прокрутки при сборе. */
        const val STEP_MS = 700L

        /**
         * Столько шагов подряд без новых строк — и считаем, что список
         * кончился. С запасом: очередная сотня на слабой сети приезжает
         * не сразу, и остановиться раньше значит молча потерять хвост.
         */
        const val QUIET_STEPS = 14

        /** Предохранитель от вечной прокрутки: около десяти минут. */
        const val MAX_STEPS = 900

        /** По стольку строк читаем за один вызов. */
        const val CHUNK = 200

        /** Сколько ждём пустую страницу, прежде чем подсказать про вход. */
        const val PATIENCE_MS = 8_000L
    }

    private enum class Stage { OPENING, ON_THE_WAY, SIGNED_OUT, ELSEWHERE, READY, WORKING, DONE, FAILED }

    private var stage by mutableStateOf(Stage.OPENING)
    private var seen by mutableStateOf(0)
    private var note by mutableStateOf("")

    /** Вошёл ли человек в аккаунт в этом окне — от этого зависит, есть ли что стирать. */
    private var signedIn by mutableStateOf(false)

    private var web: WebView? = null
    private var watch: Job? = null
    private var work: Job? = null
    private var openedAt = 0L

    private val listUrl get() = "https://music.youtube.com/playlist?list=$LIKED"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LazyMusicTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("YouTube Music") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Закрыть")
                                }
                            },
                            actions = {
                                // «Выйти» без уточнения читалось как «закрыть экран»;
                                // а пока входа нет, и стирать нечего
                                if (signedIn) {
                                    TextButton(onClick = ::signOut) { Text("Выйти из аккаунта") }
                                }
                            }
                        )
                    }
                ) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        Panel()
                        AndroidView(
                            factory = { makeBrowser() },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    }
                }
                // «Назад» сначала листает страницы браузера и только потом закрывает экран
                BackHandler {
                    val view = web
                    if (view != null && view.canGoBack() && stage != Stage.DONE) view.goBack() else finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        watch = lifecycleScope.launch {
            while (isActive) {
                if (stage != Stage.WORKING && stage != Stage.DONE) look()
                delay(WATCH_MS)
            }
        }
    }

    override fun onPause() {
        watch?.cancel()
        web?.onPause()
        // Вход — на диск сразу: им пользуется и yt-dlp (YtLogin)
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        work?.cancel()
        web?.destroy()
        web = null
        super.onDestroy()
    }

    // ── Браузер ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeBrowser(): WebView = WebView(this).also { view ->
        // Без этой строки страница YouTube Music схлопывается в полоску:
        // Compose создаёт вид с размерами «по содержимому», а встроенный
        // браузер в таком режиме считает все единицы высоты окна (100vh,
        // 100dvh) равными нулю — сайт же задаёт ими высоту области со
        // списком. Замерено отладчиком: 100dvh = 0 при innerHeight = 499
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.settings.apply {
            // Без скриптов не откроется ни вход Google, ни сам YouTube Music
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                // Вложенные рамки страницы не трогаем — следим только за тем,
                // куда уходит она сама
                if (!request.isForMainFrame) return false
                val uri = request.url
                val allowed = uri.scheme == "https" && ours(uri.host.orEmpty())
                if (!allowed) Diag.i("YouTube Music: не пускаю на ${uri.scheme}://${uri.host}")
                return !allowed
            }
        }
        web = view
        openedAt = System.currentTimeMillis()
        view.loadUrl(listUrl)
    }

    /**
     * Свои ли это края: Google и YouTube, включая страны (`google.es`).
     * Всё прочее — и ссылки вида `intent://`, которыми страница зовёт
     * открыть приложение YouTube Music, — остаётся за дверью.
     */
    private fun ours(host: String): Boolean {
        val h = host.lowercase()
        if (h == "youtube.com" || h.endsWith(".youtube.com")) return true
        val labels = h.split('.')
        val at = labels.indexOf("google")
        if (at < 0) return false
        // Смотрим, что стоит ПОСЛЕ «google»: одного слова в имени мало,
        // иначе прошёл бы и чужой google.example.com
        val tail = labels.drop(at + 1)
        val country = tail.lastOrNull()?.length == 2
        return when (tail.size) {
            1 -> tail[0] == "com" || country                      // google.com, google.es
            2 -> country && (tail[0] == "co" || tail[0] == "com") // google.co.uk, google.com.br
            else -> false
        }
    }

    private fun signOut() {
        work?.cancel()
        CookieManager.getInstance().removeAllCookies(null)
        // yt-dlp тоже забывает вход — см. YtLogin
        YtLogin.forget(this)
        WebStorage.getInstance().deleteAllData()
        web?.apply {
            clearHistory()
            clearCache(true)
            loadUrl(listUrl)
        }
        openedAt = System.currentTimeMillis()
        stage = Stage.OPENING
        note = ""
        signedIn = false
        Diag.i("YouTube Music: вход стёрт")
    }

    private fun open(url: String) {
        openedAt = System.currentTimeMillis()
        stage = Stage.OPENING
        web?.loadUrl(url)
    }

    // ── Что сейчас открыто ───────────────────────────────────────────────────

    /** Смотрит на страницу и решает, что сказать человеку. */
    private suspend fun look() {
        val view = web ?: return
        val state = runCatching { JSONObject(text(view.eval(stateJs)) ?: return) }.getOrNull() ?: return
        val host = state.optString("host")
        val rows = state.optInt("rows")
        val signed = state.optString("signedIn")
        // Страницы Google по дороге ничего о входе не говорят — прежний ответ не трогаем
        if (signed.isNotEmpty()) signedIn = signed == "yes"
        val onList = host == "music.youtube.com" &&
            state.optString("path") == "/playlist" && state.optString("list") == LIKED
        seen = rows
        stage = when {
            // Вход, согласие на cookies и прочее, что Google ставит по дороге
            host != "music.youtube.com" -> Stage.ON_THE_WAY
            !onList -> Stage.ELSEWHERE
            rows > 0 -> Stage.READY
            // Страница списка есть, строк нет: либо ещё грузится, либо это
            // гость, и списка ему не покажут, либо список и правда пуст
            signed == "no" -> Stage.SIGNED_OUT
            System.currentTimeMillis() - openedAt < PATIENCE_MS -> Stage.OPENING
            signed == "yes" -> Stage.READY
            else -> Stage.SIGNED_OUT
        }
    }

    // ── Сбор ─────────────────────────────────────────────────────────────────

    private fun collect() {
        val view = web ?: return
        work?.cancel()
        work = lifecycleScope.launch {
            stage = Stage.WORKING
            // Погасший экран останавливает страницу, а с ней и подгрузку списка
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                // 1. Прокручиваем до конца: список приезжает по сотне строк
                var last = -1
                var quiet = 0
                var steps = 0
                while (quiet < QUIET_STEPS && steps < MAX_STEPS) {
                    val count = view.eval(scrollJs)?.toIntOrNull() ?: 0
                    if (count > last) {
                        last = count
                        quiet = 0
                    } else quiet++
                    seen = count
                    steps++
                    delay(STEP_MS)
                }

                // 2. Читаем строки пачками
                val tracks = ArrayList<Track>(last.coerceAtLeast(0))
                var gone = 0
                var from = 0
                while (from < last) {
                    val rows = JSONArray(text(view.eval(extractJs(from, from + CHUNK))) ?: "[]")
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONArray(i) ?: continue
                        val id = if (row.isNull(0)) "" else row.optString(0).trim()
                        // Без идентификатора — ролик удалён или закрыт, играть нечего
                        if (id.isEmpty()) {
                            gone++
                            continue
                        }
                        // Русский YouTube Music склеивает исполнителей союзом
                        // «и» — это его оформление, а не часть имён
                        val artist = row.optString(2).trim().replace(" и ", " & ")
                        val title = row.optString(1).trim()
                            .takeIf { name -> name.any { it.isLetterOrDigit() } }
                            ?: artist.ifBlank { id }
                        tracks += Track(id, title, artist, Radio.thumbnailUrl(id))
                    }
                    from += CHUNK
                }

                // 3. Кладём в понравившееся
                val added = Likes.addAll(tracks)
                note = when {
                    tracks.isEmpty() -> "В списке не нашлось ни одного трека."
                    added == 0 -> "Новых нет: все ${tracks.size} уже были в приложении."
                    else -> "Добавлено новых: $added. Всего в списке на странице ${tracks.size}."
                } + if (gone > 0) " Недоступных роликов пропущено: $gone." else ""
                Diag.i("YouTube Music: строк ${last.coerceAtLeast(0)}, годных ${tracks.size}, новых $added, пропущено $gone")
                stage = Stage.DONE
            } catch (cancel: CancellationException) {
                stage = Stage.READY
                throw cancel
            } catch (t: Throwable) {
                note = "Не вышло: ${t.message ?: t.javaClass.simpleName}"
                Diag.w("YouTube Music: сбор сорвался — ${Diag.chain(t, 200)}")
                stage = Stage.FAILED
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ── Разговор со страницей ────────────────────────────────────────────────

    /** Выполнить скрипт на странице и дождаться ответа. Ответ приходит в виде JSON. */
    private suspend fun WebView.eval(script: String): String? =
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
        }

    /** Строку скрипт возвращает в кавычках и с экранированием — снимаем их. */
    private fun text(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    /** Где мы: адрес без лишнего, сколько строк видно и вошёл ли человек. */
    private val stateJs = """
        (function () {
          var signed = '';
          try {
            if (window.ytcfg && ytcfg.get) {
              var v = ytcfg.get('LOGGED_IN');
              if (v === true) signed = 'yes';
              if (v === false) signed = 'no';
            }
          } catch (e) {}
          return JSON.stringify({
            host: location.hostname,
            path: location.pathname,
            list: new URLSearchParams(location.search).get('list') || '',
            rows: document.querySelectorAll('$ROW').length,
            signedIn: signed
          });
        })()
    """.trimIndent()

    /**
     * Прокрутить список до самого низа и сказать, сколько в нём строк.
     *
     * Крутим всё, что крутится над последней строкой, а не только окно.
     * На компьютере прокручивается окно, на телефоне — внутренний блок
     * страницы, и «продолжение списка» сидит в его конце отдельным
     * элементом. Первая версия ставила последнюю строку вровень с нижним
     * краем: продолжение оставалось на шестьдесят точек ниже, в кадр не
     * попадало, и список молча обрывался на первой сотне.
     */
    private val scrollJs = """
        (function () {
          var rows = document.querySelectorAll('$ROW');
          if (!rows.length) return 0;
          for (var p = rows[rows.length - 1].parentElement; p; p = p.parentElement) {
            if (p.scrollHeight > p.clientHeight + 1) p.scrollTop = p.scrollHeight;
          }
          window.scrollTo(0, document.documentElement.scrollHeight);
          return rows.length;
        })()
    """.trimIndent()

    /**
     * Строки с [from] по [to]: идентификатор, название, исполнитель.
     *
     * Сначала спрашиваем у строки её данные — они не зависят от того,
     * как страница нарисована на узком экране. Разметка — запасной путь.
     */
    private fun extractJs(from: Int, to: Int) = """
        (function (a, b) {
          function runs(col) {
            try {
              return col.musicResponsiveListItemFlexColumnRenderer.text.runs
                .map(function (r) { return r.text; }).join('');
            } catch (e) { return ''; }
          }
          var rows = Array.prototype.slice.call(document.querySelectorAll('$ROW'), a, b);
          return JSON.stringify(rows.map(function (row) {
            var id = null, title = '', artist = '';
            var d = row.data || null;
            if (d) {
              id = (d.playlistItemData && d.playlistItemData.videoId) || null;
              var cols = d.flexColumns || [];
              if (cols.length > 0) title = runs(cols[0]);
              if (cols.length > 1) artist = runs(cols[1]);
            }
            if (!id) {
              var link = row.querySelector('a[href*="watch?v="]');
              if (link) {
                try { id = new URL(link.href, location.origin).searchParams.get('v'); } catch (e) {}
              }
            }
            if (!title) {
              var t = row.querySelector('.title-column yt-formatted-string.title, yt-formatted-string.title, .title');
              if (t) title = (t.textContent || '').trim();
            }
            if (!artist) {
              var s = row.querySelector('.secondary-flex-columns yt-formatted-string');
              if (s) artist = (s.textContent || '').trim();
            }
            return [id, title, artist];
          }));
        })($from, $to)
    """.trimIndent()

    // ── Полоса над браузером ─────────────────────────────────────────────────

    @Composable
    private fun Panel() {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = when (stage) {
                        Stage.OPENING -> "Открываю список понравившегося…"
                        Stage.ON_THE_WAY -> "Ответь на то, что спрашивает страница ниже — вход в аккаунт, " +
                            "согласие. Пароль вводится на странице Google: приложение его не читает " +
                            "и не хранит. После входа список откроется сам."
                        Stage.SIGNED_OUT -> "Список не показывается — похоже, ты не вошёл в аккаунт. " +
                            "Нажми «Войти»: откроется обычная страница входа Google."
                        Stage.ELSEWHERE -> "Это не список понравившегося."
                        Stage.READY -> "Список открыт. Нажми «Забрать» — приложение само прокрутит " +
                            "его до конца и добавит то, чего у него ещё нет."
                        Stage.WORKING -> "Собираю… строк уже: $seen. Экран не гаси и окно не сворачивай."
                        Stage.DONE, Stage.FAILED -> note
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (stage == Stage.DONE && signedIn) {
                    // Вход остаётся в этом окне — об этом надо сказать прямо,
                    // а не оставлять молча
                    Text(
                        text = "Вход в аккаунт сохранён в этом окне: в следующий раз входить " +
                            "не придётся. Не нужен — «Выйти из аккаунта» сверху сотрёт его.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (stage) {
                        Stage.OPENING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Stage.ON_THE_WAY -> Unit
                        Stage.SIGNED_OUT -> Button(onClick = { open(SIGN_IN_URL) }) { Text("Войти") }
                        Stage.ELSEWHERE -> Button(onClick = { open(listUrl) }) { Text("Открыть понравившееся") }
                        Stage.READY -> Button(onClick = ::collect) { Text("Забрать") }
                        Stage.WORKING -> {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = { work?.cancel() }) { Text("Остановить") }
                        }
                        Stage.DONE -> Button(onClick = ::finish) { Text("Готово") }
                        Stage.FAILED -> Button(onClick = ::collect) { Text("Ещё раз") }
                    }
                }
            }
        }
    }
}
