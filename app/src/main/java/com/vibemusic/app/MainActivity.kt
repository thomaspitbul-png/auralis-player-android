package com.vibemusic.app

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Ink = Color(0xFF0B0B10)
private val Panel = Color(0xFF171720)
private val PanelRaised = Color(0xFF22212D)
private val Purple = Color(0xFFB39AFF)
private val Soft = Color(0xFFB7B4C6)
private val White = Color(0xFFF8F6FF)

class MainActivity : ComponentActivity() {
    private val store by lazy { MusicStore(this) }
    private val archive = ArchiveRepository()
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var controller by mutableStateOf<MediaController?>(null)
    private var playing by mutableStateOf(false)
    private var current by mutableStateOf<Song?>(null)
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf("Найди музыку, которая подходит твоему настроению")
    private val results = mutableStateListOf<Song>()
    private val favorites = mutableStateListOf<Song>()
    private var query by mutableStateOf("")
    private var tab by mutableIntStateOf(0)
    private var libraryFilter by mutableIntStateOf(0)
    private var showPlayer by mutableStateOf(false)
    private var showCreatePlaylist by mutableStateOf(false)
    private var playlistName by mutableStateOf("")
    private var selectedPlaylistId by mutableStateOf<String?>(null)
    private var addSongToPlaylist by mutableStateOf<Song?>(null)
    private var volume by mutableFloatStateOf(0.7f)
    private var future: ListenableFuture<MediaController>? = null

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Аудиофайл"
            val song = Song("local_${uri.hashCode()}", name.substringBeforeLast('.', name), "На устройстве", url=uri.toString(), local=true)
            store.saveSong(song)
            results.add(0, song)
            message = "Трек добавлен в медиатеку"
            tab = 3
            libraryFilter = 2
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favorites.addAll(store.favorites())
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        future = MediaController.Builder(this, token).buildAsync()
        future?.addListener({
            runCatching { future?.get() }.onSuccess { c ->
                if (c != null) {
                    controller = c
                    c.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            current = item?.mediaId?.let { store.getSong(it) }
                            current?.let { store.addHistory(it); refreshFavorites() }
                        }
                    })
                }
            }
        }, mainExecutor)
        setContent { AuralisScreen() }
    }

    @Composable
    private fun AuralisScreen() {
        MaterialTheme(colorScheme = darkColorScheme(
            primary = Purple, onPrimary = Ink, background = Ink, onBackground = White,
            surface = Panel, onSurface = White, secondary = Soft
        )) {
            Surface(Modifier.fillMaxSize(), color = Ink) {
                if (showPlayer) {
                    FullPlayer(
                        song = current,
                        isPlaying = playing,
                        volume = volume,
                        onBack = { showPlayer = false },
                        onPlayPause = { togglePlayback() },
                        onVolume = { setVolume(it) },
                        onFavorite = { current?.let { toggleFavorite(it) } },
                        isFavorite = current?.let { store.isFavorite(it.id) } == true,
                        onAddToPlaylist = { current?.let { addSongToPlaylist = it } }
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            Header()
                            when (tab) {
                                0 -> HomeScreen()
                                1 -> SearchScreen()
                                2 -> PlaylistsScreen()
                                3 -> LibraryScreen()
                                else -> SettingsScreen()
                            }
                        }
                        current?.let { song -> MiniPlayer(song, onOpen={showPlayer=true}, onToggle={togglePlayback()}) }
                        NavigationBar(containerColor = Panel, contentColor = Soft, tonalElevation = 0.dp) {
                            val labels = listOf("Главная", "Поиск", "Плейлисты", "Медиатека", "Настройки")
                            val icons = listOf(Icons.Default.Home, Icons.Default.Search, Icons.Default.QueueMusic, Icons.Default.LibraryMusic, Icons.Default.Settings)
                            labels.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { tab = index; if (index != 2) selectedPlaylistId = null },
                                    icon = { Icon(icons[index], contentDescription = label) },
                                    label = { Text(label, maxLines=1, style=MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor=Purple, selectedTextColor=Purple, indicatorColor=Color(0xFF332A49), unselectedIconColor=Soft, unselectedTextColor=Soft)
                                )
                            }
                        }
                    }
                }
                if (showCreatePlaylist) {
                    AlertDialog(
                        onDismissRequest = { showCreatePlaylist = false },
                        title = { Text("Новый плейлист") },
                        text = {
                            OutlinedTextField(value=playlistName, onValueChange={playlistName=it}, label={Text("Название")}, singleLine=true)
                        },
                        confirmButton = {
                            TextButton(onClick={
                                if (playlistName.isNotBlank()) {
                                    store.createPlaylist(playlistName)
                                    playlistName = ""
                                    showCreatePlaylist = false
                                    message = "Плейлист создан"
                                }
                            }) { Text("Создать") }
                        },
                        dismissButton = { TextButton(onClick={showCreatePlaylist=false}) { Text("Отмена") } },
                        containerColor = Panel
                    )
                }
                addSongToPlaylist?.let { song ->
                    val playlists = store.playlists()
                    AlertDialog(
                        onDismissRequest={addSongToPlaylist=null},
                        title={Text("Добавить в плейлист")},
                        text={
                            if (playlists.isEmpty()) Text("Сначала создай плейлист во вкладке «Плейлисты».")
                            else Column(verticalScroll(rememberScrollState())) {
                                playlists.forEach { playlist ->
                                    TextButton(onClick={
                                        store.addToPlaylist(song, playlist.id)
                                        addSongToPlaylist=null
                                        message="Добавлено в «${playlist.name}»"
                                    }, modifier=Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.QueueMusic, null, tint=Purple)
                                        Spacer(Modifier.width(10.dp))
                                        Text(playlist.name, modifier=Modifier.weight(1f))
                                        Icon(Icons.Default.Add, null)
                                    }
                                }
                            }
                        },
                        confirmButton={ TextButton(onClick={addSongToPlaylist=null}) { Text("Закрыть") } },
                        containerColor=Panel
                    )
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp, vertical=16.dp), verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF302444)), contentAlignment=Alignment.Center) {
                Icon(Icons.Default.GraphicEq, null, tint=Purple, modifier=Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("AURALIS", color=Purple, style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.ExtraBold)
                Text(when(tab) { 0 -> "Твоя музыка"; 1 -> "Найти трек"; 2 -> "Твои плейлисты"; 3 -> "Твоя коллекция"; else -> "Настройки" },
                    color=White, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
            }
            if (tab == 1) IconButton(onClick={openAudio.launch(arrayOf("audio/*"))}) { Icon(Icons.Default.FileOpen, "Открыть аудио", tint=Purple) }
        }
    }

    @Composable
    private fun HomeScreen() {
        Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3C2A60), Color(0xFF201D31)))
            ).clickable { tab=1 }.padding(20.dp)) {
                Column {
                    Text("ТВОЙ ЗВУК. ТВОЁ НАСТРОЕНИЕ.", color=Purple, style=MaterialTheme.typography.labelMedium, fontWeight=FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Что хочешь
послушать?", color=White, style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.ExtraBold)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint=Ink)
                        Spacer(Modifier.width(8.dp))
                        Text("Искать музыку", color=Ink, fontWeight=FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Недавно слушал", style=MaterialTheme.typography.titleLarge, color=White, fontWeight=FontWeight.Bold, modifier=Modifier.weight(1f))
                TextButton(onClick={tab=3; libraryFilter=1}) { Text("Вся история", color=Purple) }
            }
            val recent = store.history().take(8)
            if (recent.isEmpty()) EmptyState("История пока пуста", "Запусти трек — он появится здесь", Icons.Default.History)
            else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp), contentPadding=PaddingValues(bottom=12.dp)) {
                items(recent, key={it.id}) { song -> SongRow(song, onPlay={playSong(song)}, onFavorite={toggleFavorite(song)}, onAdd={addSongToPlaylist=song}) }
            }
        }
    }

    @Composable
    private fun SearchScreen() {
        val suggestions = remember(query, results.size) { buildSuggestions(query) }
        Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
            Text("Поиск понимает варианты написания", color=Soft, style=MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value=query, onValueChange={query=it},
                modifier=Modifier.fillMaxWidth(),
                placeholder={Text("Например, Майкл Джексон")},
                leadingIcon={Icon(Icons.Default.Search, null, tint=Purple)},
                trailingIcon={
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        if (query.isNotEmpty()) IconButton(onClick={query=""}) { Icon(Icons.Default.Close, "Очистить") }
                        IconButton(onClick={searchMusic()}, enabled=!loading) {
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth=2.dp)
                            else Icon(Icons.Default.ArrowForward, "Искать", tint=Purple)
                        }
                    }
                },
                singleLine=true, shape=RoundedCornerShape(18.dp)
            )
            if (query.isNotBlank() && suggestions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Возможно, ты ищешь", color=Soft, style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    suggestions.forEach { suggestion ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { query=suggestion; searchMusic() }.padding(horizontal=10.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint=Purple, modifier=Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(suggestion, color=White, modifier=Modifier.weight(1f))
                            Icon(Icons.Default.NorthWest, null, tint=Soft, modifier=Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (query.isBlank()) {
                Text("Популярные запросы", color=Soft, style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                val popular = listOf("Michael Jackson", "Billie Jean", "The Weeknd", "Linkin Park", "Классическая музыка")
                popular.chunked(2).forEach { pair ->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        pair.forEach { term ->
                            SuggestionChip(onClick={query=term; searchMusic()}, label={Text(term)}, modifier=Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            Text(message, color=Soft, style=MaterialTheme.typography.bodySmall, modifier=Modifier.padding(bottom=8.dp))
            if (results.isEmpty()) {
                if (query.isBlank()) EmptyState("Найди свой следующий трек", "Можно искать по названию, исполнителю или части слов", Icons.Default.MusicNote)
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(8.dp), contentPadding=PaddingValues(bottom=16.dp)) {
                    items(results.toList(), key={it.id}) { song -> SongRow(song, onPlay={playSong(song)}, onFavorite={toggleFavorite(song)}, onAdd={addSongToPlaylist=song}) }
                }
            }
        }
    }

    @Composable
    private fun PlaylistsScreen() {
        val playlists = store.playlists()
        val selected = playlists.firstOrNull { it.id == selectedPlaylistId }
        Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
            if (selected != null) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={selectedPlaylistId=null}) { Icon(Icons.Default.ArrowBack, "Назад", tint=Purple) }
                    Column(Modifier.weight(1f)) {
                        Text(selected.name, color=White, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                        Text("${selected.songIds.size} трек(ов)", color=Soft, style=MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick={store.deletePlaylist(selected.id); selectedPlaylistId=null; message="Плейлист удалён"}) { Icon(Icons.Default.DeleteOutline, "Удалить плейлист", tint=Soft) }
                }
                val songs = store.songsInPlaylist(selected)
                if (songs.isEmpty()) EmptyState("Плейлист пока пуст", "Добавляй треки через кнопку + у любой песни", Icons.Default.QueueMusic)
                else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(songs, key={it.id}) { song -> SongRow(song, onPlay={playSong(song)}, onFavorite={toggleFavorite(song)}, onAdd={store.removeFromPlaylist(song.id, selected.id); message="Трек удалён из плейлиста"}) }
                }
            } else {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Собери свою атмосферу", color=White, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                        Text("Плейлисты для любого настроения", color=Soft, style=MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick={showCreatePlaylist=true}, colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color(0xFF332A49), contentColor=Purple)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Создать")
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (playlists.isEmpty()) EmptyState("Плейлистов ещё нет", "Создай первый и добавляй в него любимые треки", Icons.Default.QueueMusic)
                else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    items(playlists, key={it.id}) { playlist ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable { selectedPlaylistId=playlist.id }.padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF332A49)), contentAlignment=Alignment.Center) { Icon(Icons.Default.QueueMusic, null, tint=Purple, modifier=Modifier.size(27.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, color=White, fontWeight=FontWeight.SemiBold, maxLines=1)
                                Text("${playlist.songIds.size} трек(ов)", color=Soft, style=MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint=Soft)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LibraryScreen() {
        Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Избранное", "История", "Файлы").forEachIndexed { index, label ->
                    FilterChip(selected=libraryFilter==index, onClick={libraryFilter=index}, label={Text(label)})
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick={openAudio.launch(arrayOf("audio/*"))}) { Icon(Icons.Default.FileOpen, "Импорт аудио", tint=Purple) }
            }
            Spacer(Modifier.height(8.dp))
            val songs = when(libraryFilter) { 0 -> store.favorites(); 1 -> store.history(); else -> store.localSongs() }
            Text(when(libraryFilter) { 0 -> "Избранное"; 1 -> "Недавно прослушанные"; else -> "Файлы на устройстве" }, color=White, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
            Text("${songs.size} трек(ов)", color=Soft, style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            if (songs.isEmpty()) EmptyState(
                when(libraryFilter) { 0 -> "Пока нет избранного"; 1 -> "История пока пуста"; else -> "Добавь музыку с устройства" },
                when(libraryFilter) { 0 -> "Нажимай сердечко у понравившихся треков"; 1 -> "Запущенные треки появятся здесь"; else -> "Импортируй аудио через кнопку справа" },
                when(libraryFilter) { 0 -> Icons.Default.FavoriteBorder; 1 -> Icons.Default.History; else -> Icons.Default.FolderOpen }
            ) else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(songs, key={it.id}) { song -> SongRow(song, onPlay={playSong(song)}, onFavorite={toggleFavorite(song)}, onAdd={addSongToPlaylist=song}) }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
            Text("Звук и воспроизведение", color=White, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeUp, null, tint=Purple)
                    Spacer(Modifier.width(10.dp))
                    Text("Громкость медиа", color=White, fontWeight=FontWeight.SemiBold, modifier=Modifier.weight(1f))
                    Text("${(volume*100).roundToInt()}%", color=Purple, fontWeight=FontWeight.Bold)
                }
                Slider(value=volume, onValueChange={setVolume(it)}, valueRange=0f..1f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Тише", color=Soft, style=MaterialTheme.typography.bodySmall)
                    Text("Громче", color=Soft, style=MaterialTheme.typography.bodySmall)
                }
                Text("Регулирует системную громкость музыки на устройстве.", color=Soft, style=MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Headphones, null, tint=Purple)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Воспроизведение в фоне", color=White, fontWeight=FontWeight.SemiBold)
                        Text("Управляй музыкой через системные уведомления и экран блокировки.", color=Soft, style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(18.dp)) {
                Text("О приложении", color=White, fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Auralis · 0.2.0", color=Purple)
                Text("Музыкальный плеер с поиском открытых аудиозаписей и локальных файлов.", color=Soft, style=MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    @Composable
    private fun SongRow(song: Song, onPlay: () -> Unit, onFavorite: () -> Unit, onAdd: () -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Panel).clickable(onClick=onPlay).padding(horizontal=12.dp, vertical=10.dp), verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF302541)), contentAlignment=Alignment.Center) {
                Icon(if(current?.id==song.id && playing) Icons.Default.GraphicEq else Icons.Default.MusicNote, null, tint=Purple, modifier=Modifier.size(25.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color=White, fontWeight=FontWeight.SemiBold, maxLines=1, overflow=TextOverflow.Ellipsis)
                Text(song.artist, color=Soft, style=MaterialTheme.typography.bodySmall, maxLines=1, overflow=TextOverflow.Ellipsis)
                if (song.local) Text("На устройстве", color=Purple, style=MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick=onAdd) { Icon(Icons.Default.Add, "В плейлист", tint=Soft) }
            IconButton(onClick=onFavorite) {
                Icon(if(store.isFavorite(song.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Избранное", tint=if(store.isFavorite(song.id)) Purple else Soft)
            }
        }
    }

    @Composable
    private fun MiniPlayer(song: Song, onOpen: () -> Unit, onToggle: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp, vertical=7.dp).clip(RoundedCornerShape(18.dp)).background(PanelRaised).clickable(onClick=onOpen).padding(horizontal=12.dp, vertical=9.dp), verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A2D50)), contentAlignment=Alignment.Center) { Icon(Icons.Default.Album, null, tint=Purple) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color=White, fontWeight=FontWeight.SemiBold, maxLines=1, overflow=TextOverflow.Ellipsis)
                Text(song.artist, color=Soft, style=MaterialTheme.typography.bodySmall, maxLines=1)
            }
            IconButton(onClick=onToggle) { Icon(if(playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизведение", tint=Purple, modifier=Modifier.size(28.dp)) }
            IconButton(onClick=onOpen) { Icon(Icons.Default.OpenInFull, "Открыть плеер", tint=Soft) }
        }
    }

    @Composable
    private fun FullPlayer(song: Song?, isPlaying: Boolean, volume: Float, onBack: () -> Unit, onPlayPause: () -> Unit, onVolume: (Float) -> Unit, onFavorite: () -> Unit, isFavorite: Boolean, onAddToPlaylist: () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp), horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top=12.dp), verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack) { Icon(Icons.Default.KeyboardArrowDown, "Свернуть плеер", tint=White, modifier=Modifier.size(32.dp)) }
                Spacer(Modifier.weight(1f))
                Text("СЕЙЧАС ИГРАЕТ", color=Soft, style=MaterialTheme.typography.labelMedium, fontWeight=FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick=onAddToPlaylist) { Icon(Icons.Default.PlaylistAdd, "В плейлист", tint=Purple) }
            }
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(30.dp)).background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF4B3572), Color(0xFF171421)))
            ), contentAlignment=Alignment.Center) {
                Icon(Icons.Default.GraphicEq, null, tint=Purple, modifier=Modifier.size(120.dp))
            }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "Ничего не играет", color=White, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.ExtraBold, maxLines=2, overflow=TextOverflow.Ellipsis)
                    Text(song?.artist ?: "Выбери трек в поиске", color=Soft, style=MaterialTheme.typography.titleMedium, maxLines=1, overflow=TextOverflow.Ellipsis)
                }
                IconButton(onClick=onFavorite) { Icon(if(isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Избранное", tint=if(isFavorite) Purple else Soft, modifier=Modifier.size(28.dp)) }
            }
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.Center) {
                IconButton(onClick={}) { Icon(Icons.Default.Shuffle, "Перемешать", tint=Soft) }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick={}) { Icon(Icons.Default.SkipPrevious, "Предыдущий трек", tint=White, modifier=Modifier.size(34.dp)) }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(onClick=onPlayPause, modifier=Modifier.size(72.dp), shape=CircleShape, colors=IconButtonDefaults.filledIconButtonColors(containerColor=Purple, contentColor=Ink), enabled=song!=null) {
                    Icon(if(isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизведение", modifier=Modifier.size(38.dp))
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick={}) { Icon(Icons.Default.SkipNext, "Следующий трек", tint=White, modifier=Modifier.size(34.dp)) }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick={}) { Icon(Icons.Default.Repeat, "Повтор", tint=Soft) }
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeDown, null, tint=Soft)
                Slider(value=volume, onValueChange=onVolume, modifier=Modifier.weight(1f))
                Icon(Icons.Default.VolumeUp, null, tint=Soft)
            }
            Spacer(Modifier.height(24.dp))
            Text("Источник: ${song?.sourceLabel ?: "Auralis"}", color=Soft, style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(28.dp))
        }
    }

    @Composable
    private fun EmptyState(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        Column(Modifier.fillMaxWidth().padding(vertical=30.dp), horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.size(74.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF292337)), contentAlignment=Alignment.Center) {
                Icon(icon, null, tint=Purple, modifier=Modifier.size(36.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, color=White, fontWeight=FontWeight.Bold, style=MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color=Soft, style=MaterialTheme.typography.bodySmall)
        }
    }

    private fun buildSuggestions(text: String): List<String> {
        val q = text.trim()
        if (q.length < 2) return emptyList()
        val normalized = q.lowercase()
        val fromLibrary = store.allSongs().flatMap { listOf(it.title, it.artist) }
            .distinct().filter { it.lowercase().contains(normalized) || normalized.split(Regex("\\s+")).any { part -> part.length > 2 && it.lowercase().contains(part) } }
            .take(3)
        val variants = ArchiveRepository.suggestionsFor(q)
        return (fromLibrary + variants.filterNot { it.equals(q, ignoreCase=true) }).distinct().take(4)
    }

    private fun searchMusic() {
        val text = query.trim()
        if (text.isBlank()) { message="Введи название трека или исполнителя"; return }
        lifecycleScope.launch {
            loading=true; message="Ищем варианты написания…"
            runCatching { archive.search(text) }
                .onSuccess { songs ->
                    results.clear(); results.addAll(songs)
                    songs.forEach(store::saveSong)
                    message=if(songs.isEmpty()) "Ничего не найдено. Попробуй другое написание." else "Найдено треков: ${songs.size}"
                    tab=1
                }
                .onFailure { message=it.message ?: "Не удалось выполнить поиск" }
            loading=false
        }
    }

    private fun playSong(song: Song) {
        lifecycleScope.launch {
            message="Подготавливаем трек…"
            runCatching {
                val ready = archive.resolveStream(song)
                store.saveSong(ready)
                val c = controller ?: error("Плеер ещё запускается")
                c.setMediaItem(MediaItem.Builder().setMediaId(ready.id).setUri(ready.url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(ready.title).setArtist(ready.artist).build()).build())
                c.prepare(); c.play()
                current=ready; playing=true; store.addHistory(ready); refreshFavorites(); message="Сейчас играет"; showPlayer=true
            }.onFailure { message=it.message ?: "Не удалось запустить трек" }
        }
    }

    private fun togglePlayback() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max).roundToInt().coerceIn(0, max), 0)
    }

    private fun toggleFavorite(song: Song) {
        store.toggleFavorite(song)
        refreshFavorites()
    }

    private fun refreshFavorites() {
        favorites.clear()
        favorites.addAll(store.favorites())
    }

    override fun onDestroy() {
        future?.let { MediaController.releaseFuture(it) }
        controller=null
        super.onDestroy()
    }
}
