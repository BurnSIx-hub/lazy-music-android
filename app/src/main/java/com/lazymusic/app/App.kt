package com.lazymusic.app

import android.app.Application

/**
 * Единственное место, где разворачивается движок и подхватываются настройки:
 * приложение поднимается и от активности, и от службы проигрывания, а
 * порядок между ними не гарантирован.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Settings.attach(this)
        Diag.attach(this)
        // Версию берём у системы: BuildConfig в AGP 9 по умолчанию не создаётся
        val release = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"
        Diag.i("запуск приложения $release")
        // Ключ мог приехать файлом с компьютера — забираем и стираем файл
        if (Settings.importKeyFromFile(this)) {
            android.util.Log.i("LazyMusic", "ключ API принят из файла")
        }
        // Списки, положенные файлом рядом с кэшем, — Takeout и прочее,
        // чего не добыть ссылкой
        Import.fromFiles(this)
        Likes.load()
        Taste.load()
        Eq.load()
        Recommend.load()
        // Импорт из Яндекса, прерванный закрытием, доделывается сам
        YandexImport.restore(this)
        // Плейлисты без сети докачивают недостающее
        Offline.attach(this)
        // Раздача библиотеки компьютеру, если её включали
        if (Settings.shareLibrary) Share.start()
        // Сводка с компьютером, если телефон с ним сопряжён
        Hub.start()
        Engine.start(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
