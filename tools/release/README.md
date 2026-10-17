# Lazy Music для Android

- что это: Lazy Music для Android — плеер музыки с YouTube: поиск и плейлисты по своему ключу YouTube Data API, радио, «Может понравиться», скачивание для игры без сети, эквалайзер, 👍 и 👎; играет в фоне с уведомлением;
- импорт плейлистов из Яндекс Музыки по ссылке «Поделиться» (плейлист, «Мне нравится», альбом): треки ищутся на YouTube, порядок как в Яндексе; Яндекс отдаёт списки только с адресов СНГ — с VPN его выключают на минуту;
- пара к Lazy Music для ПК (https://github.com/BurnSIx-hub/lazy-music-pc): плейлисты, понравившееся и свои названия сводятся по Wi-Fi; и к модулю Foundry;
- как поставить: скачать `LazyMusic-<версия>.apk` в разделе Releases и разрешить установку; дальше приложение обновляется само;
- ключ API каждый заводит свой (Google Cloud Console → YouTube Data API v3); без ключа работает вставленная ссылка на ролик;
- в Google Play приложения нет и не будет: в связи с правилами сервиса;
- код открыт. Лицензия GPL-3.0. Требование библиотеки youtubedl-android;
- на чём собрано: youtubedl-android (GPL-3.0), yt-dlp (Unlicense), Media3/ExoPlayer и Coil (Apache-2.0), шрифты Roboto и Inter Tight (SIL OFL);
- собрать самому: `./gradlew assembleRelease`; проверка сверки импорта — `./gradlew testDebugUnitTest`.
