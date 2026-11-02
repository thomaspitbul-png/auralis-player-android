package com.vibemusic.app

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val url: String = "",
    val licenseUrl: String = "",
    val tags: List<String> = emptyList(),
    val local: Boolean = false,
    val source: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String = "",
    val sourcePageUrl: String = "",
    val licenseName: String = ""
) {
    val sourceLabel: String
        get() = when {
            local -> "На устройстве"
            source == "jamendo" || id.startsWith("jamendo_") -> "Jamendo · Creative Commons"
            source == "archive" || id.startsWith("archive_") -> "Internet Archive"
            id.startsWith("itunes_") -> "Старое превью Apple · недоступно"
            else -> "Открытый аудиокаталог"
        }
}

data class MusicPlaylist(
    val id: String,
    val name: String,
    val songIds: List<String> = emptyList()
)

enum class AppTab(val label: String) {
    HOME("Главная"), SEARCH("Поиск"), LIBRARY("Моя музыка"), FOR_YOU("Для тебя")
}
