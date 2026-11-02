package com.vibemusic.app

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val url: String = "",
    val licenseUrl: String = "",
    val tags: List<String> = emptyList(),
    val local: Boolean = false
) {
    val sourceLabel: String
        get() = when { local -> "На устройстве"; id.startsWith("itunes_") -> "Apple Music · предпрослушивание"; else -> "Internet Archive" }
}

data class MusicPlaylist(
    val id: String,
    val name: String,
    val songIds: List<String> = emptyList()
)

enum class AppTab(val label: String) {
    HOME("Главная"), SEARCH("Поиск"), LIBRARY("Моя музыка"), FOR_YOU("Для тебя")
}
