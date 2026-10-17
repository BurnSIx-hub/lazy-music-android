package com.lazymusic.app

/**
 * Временная очередь для проверки плеера.
 *
 * Идентификаторы взяты из кэша модуля на ПК, чтобы те же файлы можно было
 * положить в папку приложения и проверить игру из кэша. Названия появятся
 * на следующем шаге, когда подключим ключ YouTube Data API — сейчас их
 * взять неоткуда, кроме как отдельным запросом на каждый трек.
 */
object Catalog {
    val seed = listOf(
        Track("GVQON-muEFc", "Salvatore", "Lana Del Rey"),
        Track("ZDStF3H4L-o", "ZDStF3H4L-o"),
        Track("gYANvQPPukM", "gYANvQPPukM"),
        Track("dZFr0Eji6gc", "dZFr0Eji6gc"),
        Track("ciW79ETj51E", "ciW79ETj51E")
    )
}
