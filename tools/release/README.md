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

---

## English

- what it is: Lazy Music for Android — a music player for YouTube: search and playlists with your own YouTube Data API key, radio, “You might like”, offline downloads, an equalizer, 👍 and 👎; plays in the background with a notification;
- importing playlists from Yandex Music via the “Share” link (playlist, “Liked”, album): tracks are found on YouTube in the same order as in Yandex; Yandex gives out lists only to addresses from the CIS — with a VPN, turn it off for a minute;
- a pair for Lazy Music for the PC (https://github.com/BurnSIx-hub/lazy-music-pc): playlists, liked songs and custom titles sync over Wi-Fi; and for the Foundry module;
- how to install: download `LazyMusic-<version>.apk` from Releases and allow installation; after that the app updates itself;
- everyone gets their own API key (Google Cloud Console → YouTube Data API v3); without a key, a pasted video link still works;
- the app is not on Google Play and won’t be: because of the store’s rules;
- the code is open. License GPL-3.0, required by the youtubedl-android library;
- built with: youtubedl-android (GPL-3.0), yt-dlp (Unlicense), Media3/ExoPlayer and Coil (Apache-2.0), Roboto and Inter Tight fonts (SIL OFL);
- language: follows the phone language — Russian on a Russian phone, English otherwise; you can change it in Settings → Language;
- build it yourself: `./gradlew assembleRelease`; import matching tests — `./gradlew testDebugUnitTest`.
