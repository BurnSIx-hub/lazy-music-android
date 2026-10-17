# Надписи телефонного Lazy Music: разбор строк Kotlin, выгрузка на переписывание
# и возврат в код. Тот же порядок, что у ПК (lazy-music-pc/tools/texts):
#
#   python kt_texts.py scan                       что нашлось — для проверки
#   python kt_texts.py pc ПК-было.json ПК-док.md ПК-сейчас.json
#                                                 перенести переписанное на ПК:
#                                                 то, что совпало с прежним
#                                                 текстом ПК, получает новый
#   python kt_texts.py export --out ФАЙЛ.md --approved ПК-сейчас.json
#                                                 выгрузить то, чего на ПК нет
#   python kt_texts.py import ФАЙЛ.md             вернуть переписанное в код
#
# texts.json (рядом) хранит для каждого номера места в коде и исходные
# литералы: строки сдвигаются с каждой правкой, номера строк — только для
# отчёта, литерал ищется по точному виду.
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'java', 'com', 'lazymusic', 'app'))
CYR = re.compile('[А-Яа-яЁё]')
PLACE = re.compile(r'\{[^{}]*\}')

# ── Разбор Kotlin ────────────────────────────────────────────────────────────


def skip_template(src, i):
    """i — сразу за «${». Возвращает позицию за закрывающей «}»."""
    depth = 1
    n = len(src)
    while i < n and depth:
        c = src[i]
        if c == '"':
            i = skip_string(src, i)[0]
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        i += 1
    return i


def skip_string(src, i):
    """i — на открывающей кавычке. → (конец, тело, сырая ли)."""
    if src.startswith('"""', i):
        j = src.find('"""', i + 3)
        return j + 3, src[i + 3:j], True
    j = i + 1
    n = len(src)
    while j < n and src[j] != '"':
        if src[j] == '\\':
            j += 2
            continue
        if src.startswith('${', j):
            j = skip_template(src, j + 2)
            continue
        j += 1
    return j + 1, src[i + 1:j], False


def literals(src):
    """Строки вне комментариев: (начало, конец, тело). Склейки «"a" + "b"» — одной записью."""
    out = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        c = src[i]
        if c == "'":
            m = re.match(r"'(\\.|[^\\'\n]|\\u[0-9a-fA-F]{4})'", src[i:])
            i += len(m.group(0)) if m else 1
            continue
        if c == '"':
            start = i
            parts = []
            raw = False
            while True:
                end, body, raw1 = skip_string(src, i)
                raw = raw or raw1
                parts.append(body)
                m = re.match(r'\s*\+\s*"', src[end:])
                if m and not raw1:
                    i = end + len(m.group(0)) - 1
                    continue
                break
            out.append((start, end, parts, raw))
            i = end
            continue
        i += 1
    return out


def template_names(body):
    """`${expr}` и `$name` в теле строки → [(выражение, как было)]."""
    subs = []
    i = 0
    while i < len(body):
        if body[i] == '\\':
            i += 2
            continue
        if body.startswith('${', i):
            j = skip_template(body, i + 2)
            subs.append((body[i + 2:j - 1], body[i:j]))
            i = j
            continue
        if body[i] == '$':
            m = re.match(r'\$([A-Za-z_][A-Za-z0-9_]*)', body[i:])
            if m:
                subs.append((m.group(1), m.group(0)))
                i += len(m.group(0))
                continue
        i += 1
    return subs


# Понятные имена подстановок
FRIENDLY = {
    'track.title': 'название трека', 'last.title': 'название трека', 'r.title': 'название трека',
    'track.artist': 'исполнитель', 'artistOf(track.artist)': 'исполнитель', 'artistOf(r.artist)': 'исполнитель',
    'f.version': 'версия', 'current': 'ваша версия', 'Update.current': 'версия',
    'q': 'запрос', 'name': 'название', 'name.trim()': 'название', 'address': 'адрес', 'token': 'код',
    'Settings.hubName': 'имя компьютера', 'Engine.version ?: "встроенный"': 'версия yt-dlp',
    'failure': 'причина', 'p.error': 'причина', 'why': 'причина', 'error': 'причина',
    'thinned': 'число', 'n': 'число', 'Import.arrived': 'число', 'count': 'число',
    'cachedIds.size': 'число', 'found.tracks.size': 'число', 'loaded.size': 'число',
    'fresh.size': 'число', 'queue.size': 'число', 'extra.size': 'число',
    'freed / (1024 * 1024)': 'МБ', 'Cache.sizeBytes(this@MainActivity) / (1024 * 1024)': 'МБ',
    'mb(got)': 'скачано', 'mb(total)': 'всего', 'mb(i.got)': 'скачано', 'mb(i.total)': 'всего',
    'mb(minus.size)': 'размер', 'ago(Update.checkedAt)': 'когда', 'ago(r.at)': 'когда',
    'Hub.ago(Hub.lastSync)': 'когда', 'trackCount(own.size)': 'N треков', 'fans(it)': 'N подписчиков',
    'fansShort(n)': 'число', 'trackCount(cachedTracks.size)': 'N треков', 'trackCount(count)': 'N треков',
    'muted.joinToString(", ")': 'исполнители', '(running * 100).toInt()': 'проценты',
    '(downloading * 100).toInt()': 'проценты', 'Share.PORT': 'порт', 'stage': 'этап',
    't.message ?: t.javaClass.simpleName': 'причина', 'e.message': 'причина', 'it.message': 'причина',
    'tracks.size': 'число', 'added': 'число', 'gone': 'число', 'seen': 'число', 'status': 'код ответа',
    'from ?: "встроенный"': 'было', 'to': 'стало', 'version ?: "встроенный"': 'версия',
    'tag': 'выпуск', 'ASSET': 'файл',
}
# Файлы, где строки — только для журнала
SKIP_FILES = {'Recovery.kt', 'StreamUrls.kt', 'Ipv4First.kt', 'Prefetch.kt', 'Diag.kt', 'YtDataSource.kt'}


def friendly(expr):
    return FRIENDLY.get(expr.strip(), expr.strip())


def unescape(s):
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), s)


def text_of(parts):
    """Тело в человеческом виде: шаблоны — {имена}, экранирование снято."""
    body = ''.join(parts)
    subs = template_names(body)
    out = body
    for expr, whole in subs:
        out = out.replace(whole, '\x00' + friendly(expr) + '\x01', 1)
    out = unescape(out).replace('\x00', '{').replace('\x01', '}')
    return out, subs


SKIP_TEXT = {
    'Понравившаяся музыка',  # имя списка — по нему его узнают ПК и модуль
    'трек', 'трека', 'треков', 'подписчик', 'подписчика', 'подписчиков', 'трек.m4a',
    'ни разу', 'только что',  # части «когда» — как на ПК
    # причины для журнала: кто позвал сводку или обновление yt-dlp
    'по кнопке', 'по часам', 'после правки', 'сопряжение', 'открытие', 'по расписанию',
    'после отказа', 'вручную',
    # имя сохранённого файла
    '{artist} — {название трека}', ' (эквалайзер)', 'трек',
}
NOISE = ('Log.', 'Diag.', 'throw ', 'IOException(', 'TrackUnavailable(', 'require(', 'check(',
         'Regex(', 'Diag.chain', 'diag', 'SimpleDateFormat', 'String.format', 'Locale(')


def skip(f, src, start, text):
    if text in SKIP_TEXT or '|' in text or '://' in text:
        return True
    line_start = src.rfind('\n', 0, start) + 1
    prev_start = src.rfind('\n', 0, max(0, line_start - 1)) + 1
    ctx = src[prev_start:start]
    line = src[line_start:start]
    if any(k in line for k in NOISE):
        return True
    # Вызов, открытый на прошлой строке: Log.w(TAG,\n "…")
    if any(k in ctx for k in ('Log.', 'Diag.')) and line.strip() == '':
        return True
    if re.search(r'\b(const val TAG|TAG =)', line):
        return True
    # Единицы и мелочи: «12 МБ», «1к»
    if re.fullmatch(r'\{[^}]*\} ?(МБ|тыс\.|млн)', text):
        return True
    return False


def describe(src, start):
    """Где стоит, по тому, что вокруг литерала: подсказка у значка, кнопка, строка состояния…"""
    line_start = src.rfind('\n', 0, start) + 1
    before = src[max(0, line_start - 400):start]
    line = src[line_start:start]
    near = src[start:start + 400]
    if 'contentDescription' in line:
        return 'подсказка у значка (её читает озвучка экрана)'
    if re.search(r'label\s*=\s*\{\s*Text\($', line) or 'label = { Text(' in line:
        return 'подпись на поле ввода или кнопке'
    if 'placeholder' in line:
        return 'серый текст в пустом поле'
    if 'Plaques.toast' in line or 'Plaques.notice' in line:
        return 'плашка внизу экрана'
    if re.search(r'\b(text|sub)\s*=\s*$', line.rstrip()) or re.search(r'\bp\.(text|sub)\s*=', line):
        return 'плашка внизу экрана'
    if 'Plaque.Action(' in line:
        return 'кнопка на плашке'
    if 'error(' in line:
        return 'сообщение, если не вышло'
    if re.search(r'\b(status|note)\s*=', line) or 'finish(' in line or 'work(' in line:
        return 'строка состояния под кнопками'
    if 'EmptyHint(' in before[-80:]:
        return 'надпись на пустом экране'
    if 'Notice(' in line:
        return 'строка с пояснением'
    if 'bodySmall' in near[:200] or 'SmallHint(' in line:
        return 'мелкая серая строка — пояснение'
    if 'Section(' in line:
        return 'заголовок раздела настроек'
    if 'SwitchRow(' in line or 'SwitchRow(' in before[-60:]:
        return 'переключатель в настройках'
    if 'Text(' in line and ('Button' in before[-200:]):
        return 'кнопка'
    if 'Item(' in line:
        return 'пункт меню'
    if 'title' in line:
        return 'заголовок'
    return 'надпись'


def scan():
    items = []
    for f in sorted(os.listdir(SRC)):
        if not f.endswith('.kt') or f in SKIP_FILES:
            continue
        src = io.open(os.path.join(SRC, f), encoding='utf-8').read()
        for start, end, parts, raw in literals(src):
            text, subs = text_of(parts)
            # Русское только в именах подстановок — не надпись («{число} {form}»)
            if not CYR.search(PLACE.sub('', text)) or skip(f, src, start, text):
                continue
            items.append({
                'file': f,
                'line': src.count('\n', 0, start) + 1,
                'start': start,
                'raw': src[start:end],
                'text': text,
                'subs': [[friendly(e), w] for e, w in subs],
                'multiline': '\n' in src[start:end],
                'where': describe(src, start),
            })
    return items


def norm(text):
    """Для сверки с ПК: подстановки — любые, кавычки-ёлочки и пробелы — как есть."""
    return PLACE.sub('{}', text).strip()

# ── Запись литерала ──────────────────────────────────────────────────────────


def kt_literal(text, subs, indent, width=96):
    """Текст с {именами} → литерал Kotlin; длинный — склейкой по словам."""
    by_name = {}
    for name, whole in subs:
        by_name.setdefault(name, whole)
    pieces = re.split(r'(\{[^{}]*\})', text)
    body = ''
    for p in pieces:
        if PLACE.fullmatch(p) and p[1:-1] in by_name:
            body += by_name[p[1:-1]]
        else:
            if PLACE.fullmatch(p):
                raise ValueError(f'неизвестная подстановка {p} в «{text}»')
            body += p.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('$', '\\$')
    if len(body) + indent < width:
        return '"' + body + '"'
    # Склейка: режем по пробелам, не разрывая ${…}
    chunks, cur = [], ''
    tokens = re.findall(r'\$\{(?:[^{}]|\{[^{}]*\})*\}|\S+\s*', body)
    for tok in tokens:
        if cur and len(cur) + len(tok) + indent + 6 > width:
            chunks.append(cur)
            cur = ''
        cur += tok
    if cur:
        chunks.append(cur)
    pad = ' ' * indent
    return (' +\n' + pad).join('"' + c + '"' for c in chunks)


def continuation_indent(src, start, raw):
    """Отступ продолжения склейки: как во второй строке прежней, иначе +4."""
    m = re.search(r'\n([ \t]*)"', raw)
    if m:
        return len(m.group(1))
    line_start = src.rfind('\n', 0, start) + 1
    base = len(src[line_start:start]) - len(src[line_start:start].lstrip())
    return base + 4


def replace_texts(changes):
    """changes: [(item, новый текст)] → правка файлов по точному литералу."""
    by_file = {}
    for item, new in changes:
        by_file.setdefault(item['file'], []).append((item, new))
    problems = []
    for f, lst in by_file.items():
        p = os.path.join(SRC, f)
        src = io.open(p, encoding='utf-8').read()
        for item, new in lst:
            raw = item['raw']
            count = src.count(raw)
            if count == 0:
                problems.append(f'{f}:{item["line"]}: литерал не найден: {raw[:60]!r}')
                continue
            pos = src.find(raw)
            lit = kt_literal(new, item['subs'], continuation_indent(src, pos, raw))
            src = src.replace(raw, lit)
        io.open(p, 'w', encoding='utf-8', newline='\n').write(src)
    return problems

# ── Команды ──────────────────────────────────────────────────────────────────


def cmd_scan():
    items = scan()
    for it in items:
        print(f"{it['file']}:{it['line']}: {it['text']!r}")
    print('всего:', len(items), 'разных:', len({it['text'] for it in items}))


def pc_maps(before_json, doc_md, now_json):
    sys.path.insert(0, 'D:/Projects/lazy-music-pc/tools/texts')
    import texts_doc
    before = json.load(io.open(before_json, encoding='utf-8'))['entries']
    texts_doc.DOC = doc_md
    doc = texts_doc.read()
    rewrites = {}
    for e in before:
        new = doc.get(e['id'])
        if new and new != e['text'] and new.strip().lower() != 'убрать':
            rewrites[norm(e['text'])] = (e['text'], new)
    now = json.load(io.open(now_json, encoding='utf-8'))['entries']
    approved = {norm(e['text']) for e in now}
    return rewrites, approved


def placeholders(text):
    return PLACE.findall(text)


def cmd_pc(before_json, doc_md, now_json):
    rewrites, approved = pc_maps(before_json, doc_md, now_json)
    changes, manual = [], []
    for it in scan():
        key = norm(it['text'])
        if key not in rewrites:
            continue
        old, new = rewrites[key]
        # Подстановки ПК → свои, по порядку: имена у ПК и телефона разные
        mine = placeholders(it['text'])
        theirs_old = placeholders(old)
        theirs_new = placeholders(new)
        if len(mine) != len(theirs_old) or sorted(theirs_new) != sorted(theirs_old):
            manual.append((it, new))
            continue
        mapping = dict(zip(theirs_old, mine))
        changes.append((it, PLACE.sub(lambda m: mapping[m.group(0)], new)))
    problems = replace_texts(changes)
    for it, new in changes:
        print(f"{it['file']}:{it['line']}: {it['text']!r}\n    → {new!r}")
    for it, new in manual:
        print(f"РУКАМИ {it['file']}:{it['line']}: {it['text']!r}\n    → {new!r}")
    for p in problems:
        print('ПРОБЛЕМА', p)
    print('перенесено:', len(changes), 'руками:', len(manual))


GROUPS = {
    'MainActivity.kt': '01 Оболочка, поиск и настройки', 'SettingsParts.kt': '01 Оболочка, поиск и настройки',
    'HomeScreen.kt': '02 Главная', 'NowPlayingScreen.kt': '03 Сейчас', 'BrowseScreens.kt': '04 Плейлисты и поиск',
    'TrackMenu.kt': '05 Меню трека', 'Ui.kt': '06 Общие кнопки и плеер', 'Plaques.kt': '06 Общие кнопки и плеер',
    'Dislike.kt': '07 Дизлайк', 'ArtistScreen.kt': '08 Страница исполнителя', 'EqSheet.kt': '09 Эквалайзер',
    'Export.kt': '10 Сохранение трека', 'Update.kt': '11 Обновления', 'WhatsNew.kt': '12 Что нового',
    'YtMusicActivity.kt': '13 Забор из YouTube Music', 'Hub.kt': '14 Сводка с компьютером',
    'Share.kt': '15 Раздача библиотеки', 'PlaybackService.kt': '16 Уведомление',
}


def cmd_export(out, approved_json, where_json, before_json=None, doc_md=None):
    import difflib
    now = json.load(io.open(approved_json, encoding='utf-8'))['entries']
    approved = {norm(e['text']) for e in now}
    # Похожее на ПК — подсказкой в заголовке: «на ПК у вас …»
    yours = [e['text'] for e in now]
    pairs = list(pc_maps(before_json, doc_md, approved_json)[0].values()) if before_json and doc_md else []

    def hint(text):
        best, score = None, 0.0
        for old, new in pairs:
            r = difflib.SequenceMatcher(None, text, old).ratio()
            if r > score:
                best, score = new, r
        for t in yours:
            r = difflib.SequenceMatcher(None, text, t).ratio()
            if r > score:
                best, score = t, r
        need = 0.72 if len(text) >= 14 else 0.88
        return best if score >= need and best != text else None

    where = json.load(io.open(where_json, encoding='utf-8')) if where_json and os.path.isfile(where_json) else {}
    uniq = {}
    for it in scan():
        if norm(it['text']) in approved:
            continue
        u = uniq.setdefault(it['text'], {'text': it['text'], 'places': [], 'group': GROUPS.get(it['file'], '17 Сообщения')})
        u['places'].append(it)
    entries = sorted(uniq.values(), key=lambda u: (u['group'], u['places'][0]['file'], u['places'][0]['line']))
    for k, u in enumerate(entries, 1):
        u['id'] = f'A{k:02d}'
    md = ['# Lazy Music для телефона — тексты на переписывание\n',
          f'Здесь надписи телефона, которых нет на ПК: {len(entries)}. То, что совпало с вашими текстами '
          'на ПК, уже стоит в приложении по-вашему и сюда не вошло. Одинаковые собраны в один номер.\n',
          '**Как править.** Меняйте текст под заголовком с номером прямо на месте. Строку с номером не трогайте.\n',
          '**В фигурных скобках** — то, что программа подставит сама. Их можно переставлять и убирать, '
          'но слова внутри скобок не менять.\n',
          '**Если надпись не нужна** — напишите вместо неё «убрать».\n']
    cur = None
    for u in entries:
        if u['group'] != cur:
            cur = u['group']
            md.append(f'\n## {cur[3:]}\n')
        h = hint(u['text'])
        tail = ' · на ПК у вас: «' + h.replace('\n', ' ') + '»' if h else ''
        md.append(f"### {u['id']} · {where.get(u['text'], u['places'][0]['where'])}{tail}\n")
        md.append(u['text'] + '\n')
    io.open(out, 'w', encoding='utf-8', newline='\n').write('\n'.join(md))
    json.dump({'entries': entries}, io.open(os.path.join(HERE, 'texts.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('в файле:', len(entries))


def cmd_import(doc_md):
    sys.path.insert(0, 'D:/Projects/lazy-music-pc/tools/texts')
    import texts_doc
    texts_doc.DOC = doc_md
    doc = texts_doc.read()
    entries = json.load(io.open(os.path.join(HERE, 'texts.json'), encoding='utf-8'))['entries']
    changes = []
    for e in entries:
        new = doc.get(e['id'])
        # Файл текстов срезает пробелы по краям, а в коде они бывают нужны:
        # « и » — разделитель исполнителей, « — ждёт код» — продолжение строки.
        # Сравниваем без них и возвращаем их на место (2026-10-17 без этого
        # « и » стало «и» и резало имена исполнителей по любой букве «и»)
        if new is None or new.strip() == e['text'].strip():
            continue
        if new.strip().lower() == 'убрать':
            print('убрать руками:', e['id'], [(p['file'], p['line']) for p in e['places']])
            continue
        text = e['text']
        new = text[:len(text) - len(text.lstrip())] + new.strip() + text[len(text.rstrip()):]
        for p in e['places']:
            changes.append((p, new))
    for p in replace_texts(changes):
        print('ПРОБЛЕМА', p)
    print('изменено мест:', len(changes))


if __name__ == '__main__':
    a = sys.argv[1:]
    if a[0] == 'scan':
        cmd_scan()
    elif a[0] == 'pc':
        cmd_pc(*a[1:4])
    elif a[0] == 'export':
        out = a[a.index('--out') + 1]
        approved = a[a.index('--approved') + 1]
        where = a[a.index('--where') + 1] if '--where' in a else None
        before = a[a.index('--pc-before') + 1] if '--pc-before' in a else None
        doc = a[a.index('--pc-doc') + 1] if '--pc-doc' in a else None
        cmd_export(out, approved, where, before, doc)
    elif a[0] == 'import':
        cmd_import(a[1])
