package com.vibemusic.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val AuralisBg = Color(0xFF090A10)
private val AuralisPanel = Color(0xFF141722)
private val AuralisPanel2 = Color(0xFF1D2230)
private val AuralisAccent = Color(0xFFB69CFF)
private val AuralisText = Color(0xFFF5F3FC)
private val AuralisMuted = Color(0xFFA4A8BA)

class AuralisActivity : ComponentActivity() {
    private val store by lazy { MusicStore(this) }
    private val catalog = MusicCatalogRepository()

    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var currentSong by mutableStateOf<Song?>(null)
    private var isPlaying by mutableStateOf(false)
    private var isBuffering by mutableStateOf(false)
    private var message by mutableStateOf("Ищи музыку или добавляй свои файлы")
    private var activeTab by mutableIntStateOf(0)
    private var showPlayer by mutableStateOf(false)
    private var showQueue by mutableStateOf(false)
    private var showCreatePlaylist by mutableStateOf(false)
    private var showTimerDialog by mutableStateOf(false)
    private var addToPlaylistSong by mutableStateOf<Song?>(null)
    private var playlistName by mutableStateOf("")
    private var selectedPlaylistId by mutableStateOf<String?>(null)
    private var libraryFilter by mutableIntStateOf(0)
    private var query by mutableStateOf("")
    private var scope by mutableStateOf(CatalogScope.ALL)
    private var loading by mutableStateOf(false)
    private var resultMessage by mutableStateOf("")
    private var jamendoClientId by mutableStateOf("")
    private var jamendoIdDraft by mutableStateOf("")
    private var volume by mutableFloatStateOf(1f)
    private var positionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(0) // 0 off, 1 queue, 2 one
    private var sleepTimerMinutes by mutableIntStateOf(0)
    private var playlistRevision by mutableIntStateOf(0)
    private var queueIndex by mutableIntStateOf(0)
    private val searchResults = mutableStateListOf<Song>()
    private val queue = mutableStateListOf<Song>()
    private var sleepTimerJob: Job? = null
    private var lastAutoAdvanceId by mutableStateOf("")

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Аудиофайл"
            val song = Song(
                id = "local_${uri.hashCode()}",
                title = name.substringBeforeLast('.', name),
                artist = "На устройстве",
                url = uri.toString(),
                local = true,
                source = "local"
            )
            store.saveSong(song)
            message = "Файл добавлен в медиатеку"
            playSong(song, listOf(song))
            activeTab = 2
            libraryFilter = 2
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jamendoClientId = store.jamendoClientId()
        jamendoIdDraft = jamendoClientId
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { controllerFuture?.get() }.onSuccess { player ->
                if (player != null) {
                    controller = player
                    player.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = playbackState == Player.STATE_BUFFERING
                            if (playbackState == Player.STATE_ENDED && currentSong?.id != lastAutoAdvanceId) {
                                lastAutoAdvanceId = currentSong?.id.orEmpty()
                                handleTrackEnded()
                            }
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            val id = mediaItem?.mediaId.orEmpty()
                            currentSong = store.getSong(id) ?: queue.firstOrNull { it.id == id }
                            val index = queue.indexOfFirst { it.id == id }
                            if (index >= 0) queueIndex = index
                            currentSong?.let { store.addHistory(it) }
                            lastAutoAdvanceId = ""
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            isPlaying = false
                            isBuffering = false
                            message = "Не удалось воспроизвести: ${error.errorCodeName}"
                        }
                    })
                }
            }.onFailure {
                message = "Плеер не удалось запустить. Перезапусти приложение."
            }
        }, mainExecutor)

        setContent { AuralisRoot() }
    }

    @Composable
    private fun AuralisRoot() {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = AuralisAccent,
                onPrimary = AuralisBg,
                background = AuralisBg,
                onBackground = AuralisText,
                surface = AuralisPanel,
                onSurface = AuralisText,
                secondary = AuralisMuted
            )
        ) {
            LaunchedEffect(controller, isPlaying, showPlayer) {
                while (true) {
                    controller?.let { player ->
                        positionMs = player.currentPosition.coerceAtLeast(0L)
                        durationMs = player.duration.takeIf { it > 0L } ?: 0L
                    }
                    delay(if (isPlaying) 450L else 1000L)
                }
            }

            BackHandler(showPlayer) { showPlayer = false }

            Surface(Modifier.fillMaxSize(), color = AuralisBg) {
                if (showPlayer) {
                    FullPlayerScreen()
                } else {
                    Column(Modifier.fillMaxSize()) {
                        AppHeader()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (activeTab) {
                                0 -> HomeScreen()
                                1 -> SearchScreen()
                                2 -> LibraryScreen()
                                3 -> PlaylistsScreen()
                                else -> SettingsScreen()
                            }
                        }
                        currentSong?.let { song ->
                            MiniPlayer(
                                song = song,
                                onOpen = { showPlayer = true },
                                onPlayPause = { togglePlayback() },
                                onPrevious = { previousTrack() },
                                onNext = { nextTrack() }
                            )
                        }
                        NavigationBar(
                            containerColor = AuralisPanel,
                            contentColor = AuralisMuted,
                            tonalElevation = 0.dp
                        ) {
                            val labels = listOf("Главная", "Поиск", "Медиатека", "Плейлисты", "Настройки")
                            val icons = listOf(
                                Icons.Default.Home,
                                Icons.Default.Search,
                                Icons.Default.LibraryMusic,
                                Icons.Default.QueueMusic,
                                Icons.Default.Settings
                            )
                            labels.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = activeTab == index,
                                    onClick = { activeTab = index; if (index != 3) selectedPlaylistId = null },
                                    icon = { Icon(icons[index], contentDescription = label) },
                                    label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AuralisAccent,
                                        selectedTextColor = AuralisAccent,
                                        indicatorColor = Color(0xFF332B4A),
                                        unselectedIconColor = AuralisMuted,
                                        unselectedTextColor = AuralisMuted
                                    )
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
                            OutlinedTextField(
                                value = playlistName,
                                onValueChange = { playlistName = it },
                                singleLine = true,
                                label = { Text("Название") }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (playlistName.isNotBlank()) {
                                    store.createPlaylist(playlistName)
                                    playlistName = ""
                                    showCreatePlaylist = false
                                    playlistRevision++
                                    message = "Плейлист создан"
                                }
                            }) { Text("Создать") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCreatePlaylist = false }) { Text("Отмена") }
                        },
                        containerColor = AuralisPanel
                    )
                }

                addToPlaylistSong?.let { song ->
                    val playlists = store.playlists()
                    AlertDialog(
                        onDismissRequest = { addToPlaylistSong = null },
                        title = { Text("Добавить в плейлист") },
                        text = {
                            if (playlists.isEmpty()) {
                                Text("Сначала создай плейлист во вкладке «Плейлисты».")
                            } else {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    playlists.forEach { playlist ->
                                        TextButton(
                                            onClick = {
                                                store.addToPlaylist(song, playlist.id)
                                                addToPlaylistSong = null
                                                playlistRevision++
                                                message = "Добавлено в «${playlist.name}»"
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.QueueMusic, null, tint = AuralisAccent)
                                            Spacer(Modifier.width(10.dp))
                                            Text(playlist.name, modifier = Modifier.weight(1f))
                                            Icon(Icons.Default.Add, null)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { addToPlaylistSong = null }) { Text("Закрыть") }
                        },
                        containerColor = AuralisPanel
                    )
                }

                if (showQueue) {
                    AlertDialog(
                        onDismissRequest = { showQueue = false },
                        title = { Text("Очередь воспроизведения") },
                        text = {
                            if (queue.isEmpty()) {
                                Text("Очередь пуста")
                            } else {
                                Column(
                                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                                ) {
                                    queue.forEachIndexed { index, song ->
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (index == queueIndex) Color(0xFF302844) else Color.Transparent)
                                                .clickable { showQueue = false; playQueueIndex(index) }
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("${index + 1}", color = AuralisMuted, modifier = Modifier.width(28.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(song.title, color = AuralisText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(song.artist, color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (index == queueIndex) Icon(Icons.Default.GraphicEq, null, tint = AuralisAccent)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showQueue = false }) { Text("Готово") }
                        },
                        containerColor = AuralisPanel
                    )
                }

                if (showTimerDialog) {
                    AlertDialog(
                        onDismissRequest = { showTimerDialog = false },
                        title = { Text("Таймер сна") },
                        text = {
                            Column {
                                Text("Остановить воспроизведение через:")
                                listOf(15, 30, 45, 60).forEach { minutes ->
                                    TextButton(
                                        onClick = {
                                            startSleepTimer(minutes)
                                            showTimerDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("$minutes минут") }
                                }
                                if (sleepTimerMinutes > 0) {
                                    TextButton(
                                        onClick = {
                                            sleepTimerJob?.cancel()
                                            sleepTimerMinutes = 0
                                            showTimerDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Отключить таймер", color = Color(0xFFFFA7A7)) }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showTimerDialog = false }) { Text("Закрыть") }
                        },
                        containerColor = AuralisPanel
                    )
                }
            }
        }
    }

    @Composable
    private fun AppHeader() {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF7054B4), Color(0xFF282138)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = AuralisAccent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("AURALIS", color = AuralisAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    listOf("Твоя музыкальная среда", "Поиск полной версии", "Твоя коллекция", "Плейлисты", "Настройки")[activeTab],
                    color = AuralisText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (activeTab == 1 || activeTab == 2) {
                IconButton(onClick = { openAudio.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Default.FileOpen, "Добавить аудиофайл", tint = AuralisAccent)
                }
            }
        }
    }

    @Composable
    private fun HomeScreen() {
        val recent = store.history().take(12)
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 15.dp)) {
                Text("AURALIS LISTENING", color = AuralisAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                Text("Музыка без обрывков.", color = AuralisText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("Полные открытые записи и твоя личная медиатека.", color = AuralisMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Box(
                Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6547AA), Color(0xFF2B2540), Color(0xFF171B26))))
                    .clickable { activeTab = 1 }.padding(20.dp)
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = Color(0x447E6AC1), modifier = Modifier.size(138.dp).align(Alignment.CenterEnd))
                Column(Modifier.fillMaxWidth(0.8f).align(Alignment.CenterStart)) {
                    Text("ОТКРЫТЫЕ МУЗЫКАЛЬНЫЕ КАТАЛОГИ", color = Color(0xFFE4D9FF), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(9.dp))
                    Text("Найди трек\nи слушай целиком", color = AuralisText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(11.dp))
                    Text("Перейти к поиску  →", color = AuralisBg, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).background(AuralisAccent).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(17.dp))
            Text("Выбери настроение", color = AuralisText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            listOf("ambient", "rock", "electronic", "jazz", "classical", "lofi").chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { genre ->
                        SuggestionChip(
                            onClick = { query = genre; scope = CatalogScope.GENRE; activeTab = 1; searchMusic(genre) },
                            label = { Text(genre) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Недавно слушал", color = AuralisText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { activeTab = 2; libraryFilter = 1 }) { Text("Вся история", color = AuralisAccent) }
            }
            if (recent.isEmpty()) {
                EmptyState("История пока пуста", "Найди музыку или добавь файл с устройства", Icons.Default.History)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(recent, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            onPlay = { playSong(song, recent) },
                            onFavorite = { toggleFavorite(song) },
                            onAdd = { addToPlaylistSong = song },
                            onSource = { openExternal(song.sourcePageUrl.ifBlank { song.licenseUrl }) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchScreen() {
        val history = store.searchHistory()
        val scopes = listOf(CatalogScope.ALL, CatalogScope.TITLE, CatalogScope.ARTIST, CatalogScope.GENRE)
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Text("ПОИСК ПО КАТАЛОГАМ", color = AuralisAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
            Text("Ищи по названию, артисту или жанру", color = AuralisText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Например, artist:Daft Punk") },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = AuralisAccent) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; searchResults.clear(); resultMessage = "" }) {
                                Icon(Icons.Default.Close, "Очистить")
                            }
                        }
                        IconButton(onClick = { searchMusic() }, enabled = !loading) {
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.ArrowForward, "Искать", tint = AuralisAccent)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchMusic() })
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                scopes.forEach { filter ->
                    FilterChip(
                        selected = scope == filter,
                        onClick = { scope = filter; if (query.isNotBlank() && !loading) searchMusic() },
                        enabled = !loading,
                        label = { Text(filter.label, maxLines = 1) }
                    )
                }
            }
            if (jamendoClientId.isBlank()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF211D2D)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null, tint = AuralisAccent)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Подключи второй каталог", color = AuralisText, fontWeight = FontWeight.SemiBold)
                        Text("Добавь свой Jamendo Client ID в настройках. Сейчас доступен Internet Archive.", color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { activeTab = 4 }) { Text("Настроить") }
                }
            }
            if (query.isBlank() && searchResults.isEmpty() && history.isNotEmpty()) {
                Text("Недавние запросы", color = AuralisText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    history.take(3).forEach { item ->
                        SuggestionChip(onClick = { query = item; searchMusic(item) }, label = { Text(item, maxLines = 1) })
                    }
                }
            }
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = AuralisAccent, trackColor = AuralisPanel2)
            }
            if (resultMessage.isNotBlank()) {
                Text(resultMessage, color = AuralisMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 7.dp))
            }
            if (searchResults.isEmpty() && query.isNotBlank() && !loading) {
                EmptyState("Ничего не найдено", "Попробуй другое написание или выбери другой тип поиска", Icons.Default.ManageSearch)
            } else if (searchResults.isEmpty() && query.isBlank()) {
                EmptyState("Найди свою следующую музыку", "Поиск охватывает открытые каталоги и файлы на устройстве", Icons.Default.Headphones)
            } else if (searchResults.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 7.dp)) {
                    Text("Результаты", color = AuralisText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${searchResults.size} треков", color = AuralisAccent, style = MaterialTheme.typography.labelMedium)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(searchResults.toList(), key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            onPlay = { playSong(song, searchResults.toList()) },
                            onFavorite = { toggleFavorite(song) },
                            onAdd = { addToPlaylistSong = song },
                            onSource = { openExternal(song.sourcePageUrl.ifBlank { song.licenseUrl }) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LibraryScreen() {
        val filters = listOf("Избранное", "История", "Файлы")
        val songs = when (libraryFilter) {
            0 -> store.favorites()
            1 -> store.history()
            else -> store.localSongs()
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Моя музыка", color = AuralisText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { openAudio.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Default.FileOpen, "Импортировать музыку", tint = AuralisAccent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                filters.forEachIndexed { index, label ->
                    FilterChip(selected = libraryFilter == index, onClick = { libraryFilter = index }, label = { Text(label) })
                }
            }
            Text("${songs.size} трек(ов)", color = AuralisMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            if (songs.isEmpty()) {
                EmptyState(
                    when (libraryFilter) { 0 -> "Нет избранных треков"; 1 -> "История пока пуста"; else -> "На устройстве пока нет файлов" },
                    when (libraryFilter) { 0 -> "Отмечай понравившуюся музыку сердечком"; 1 -> "Здесь появятся прослушанные записи"; else -> "Добавь MP3, FLAC, M4A или другой аудиофайл" },
                    Icons.Default.LibraryMusic
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            onPlay = { playSong(song, songs) },
                            onFavorite = { toggleFavorite(song) },
                            onAdd = { addToPlaylistSong = song },
                            onSource = { openExternal(song.sourcePageUrl.ifBlank { song.licenseUrl }) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaylistsScreen() {
        val revision = playlistRevision
        val playlists = remember(revision) { store.playlists() }
        val selected = playlists.firstOrNull { it.id == selectedPlaylistId }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            if (selected != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedPlaylistId = null }) { Icon(Icons.Default.ArrowBack, "Назад", tint = AuralisAccent) }
                    Column(Modifier.weight(1f)) {
                        Text(selected.name, color = AuralisText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${selected.songIds.size} трек(ов)", color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { store.deletePlaylist(selected.id); selectedPlaylistId = null; playlistRevision++ }) {
                        Icon(Icons.Default.DeleteOutline, "Удалить плейлист", tint = AuralisMuted)
                    }
                }
                val songs = store.songsInPlaylist(selected)
                if (songs.isEmpty()) {
                    EmptyState("Плейлист пуст", "Добавляй песни кнопкой + в карточке трека", Icons.Default.QueueMusic)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(songs, key = { it.id }) { song ->
                            SongRow(
                                song = song,
                                onPlay = { playSong(song, songs) },
                                onFavorite = { toggleFavorite(song) },
                                onAdd = { store.removeFromPlaylist(song.id, selected.id); playlistRevision++ },
                                onSource = { openExternal(song.sourcePageUrl.ifBlank { song.licenseUrl }) }
                            )
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Твои плейлисты", color = AuralisText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Подборки, которые можно менять", color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(
                        onClick = { showCreatePlaylist = true },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF352C4B), contentColor = AuralisAccent)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Создать")
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (playlists.isEmpty()) {
                    EmptyState("Создай первый плейлист", "Сохраняй музыку по настроению, жанру или случаю", Icons.Default.QueueMusic)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(AuralisPanel)
                                    .clickable { selectedPlaylistId = playlist.id }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF332B49)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.QueueMusic, null, tint = AuralisAccent, modifier = Modifier.size(26.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, color = AuralisText, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("${playlist.songIds.size} трек(ов)", color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = AuralisMuted)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Text("Источники полной музыки", color = AuralisText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(
                "Auralis больше не использует короткие Apple Music previews. Доступны открытые аудиозаписи Internet Archive и, после настройки, полноценные стримы Jamendo.",
                color = AuralisMuted,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(15.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(AuralisPanel).padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LibraryMusic, null, tint = AuralisAccent)
                    Spacer(Modifier.width(9.dp))
                    Text("Jamendo API", color = AuralisText, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(if (jamendoClientId.isBlank()) "Не подключён" else "Настроен", color = if (jamendoClientId.isBlank()) AuralisMuted else Color(0xFF9BE3BA), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text("Jamendo отдаёт прямые stream URL полных треков и сведения об их Creative Commons лицензиях. Для подключения нужен собственный Client ID.", color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = jamendoIdDraft,
                    onValueChange = { jamendoIdDraft = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Jamendo Client ID") },
                    placeholder = { Text("Вставь свой Client ID") }
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openExternal("https://devportal.jamendo.com/signup") }, modifier = Modifier.weight(1f)) {
                        Text("Получить ID")
                    }
                    Button(
                        onClick = {
                            store.saveJamendoClientId(jamendoIdDraft)
                            jamendoClientId = jamendoIdDraft.trim()
                            message = "Настройки каталога сохранены"
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AuralisAccent, contentColor = AuralisBg)
                    ) { Text("Сохранить") }
                }
                Text("ID хранится только на этом устройстве. Не вставляй пароль от аккаунта — нужен именно Client ID.", color = AuralisMuted, style = MaterialTheme.typography.labelSmall)
                TextButton(
                    onClick = {
                        jamendoIdDraft = ""
                        jamendoClientId = ""
                        store.saveJamendoClientId("")
                    }
                ) { Text("Отключить Jamendo") }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(AuralisPanel).padding(15.dp)) {
                Text("Жесты плеера", color = AuralisText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                GestureHint("Свайп влево по обложке", "Следующий трек")
                GestureHint("Свайп вправо по обложке", "Предыдущий трек")
                GestureHint("Свайп вниз по обложке", "Свернуть большой плеер")
                GestureHint("Свайп вверх по мини-плееру", "Открыть большой плеер")
                GestureHint("Свайп по мини-плееру влево/вправо", "Переключить трек")
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(AuralisPanel).padding(15.dp)) {
                Text("Что уже умеет Auralis", color = AuralisText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                listOf(
                    "Воспроизведение локальных аудиофайлов",
                    "Поиск по нескольким открытым каталогам",
                    "Избранное, история и плейлисты",
                    "Очередь, перемешивание и повтор",
                    "Управление с экрана блокировки и в фоне",
                    "Таймер сна и перемотка трека",
                    "Сведения об источнике и лицензии"
                ).forEach { item ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF9BE3BA), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(item, color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Auralis · ${packageVersion()}", color = AuralisAccent, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(22.dp))
        }
    }

    @Composable
    private fun GestureHint(gesture: String, action: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(gesture, color = AuralisText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(action, color = AuralisAccent, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun SongRow(
        song: Song,
        onPlay: () -> Unit,
        onFavorite: () -> Unit,
        onAdd: () -> Unit,
        onSource: () -> Unit
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(AuralisPanel)
                .clickable(onClick = onPlay).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(47.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF514070), Color(0xFF252A39)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (currentSong?.id == song.id && isPlaying) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                    null,
                    tint = AuralisAccent,
                    modifier = Modifier.size(25.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = AuralisText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = AuralisMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val duration = if (song.durationMs > 0L) formatTime(song.durationMs) else "длительность уточняется"
                Text("${song.sourceLabel} · $duration", color = AuralisAccent, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song.licenseName.isNotBlank()) {
                    Text("Лицензия: ${song.licenseName}", color = AuralisMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                if (song.source == "jamendo") {
                    TextButton(onClick = onSource, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(26.dp)) {
                        Text("Автор и источник: Jamendo ↗", style = MaterialTheme.typography.labelSmall, color = AuralisAccent)
                    }
                }
            }
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Добавить в плейлист", tint = AuralisMuted) }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (store.isFavorite(song.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Избранное",
                    tint = if (store.isFavorite(song.id)) AuralisAccent else AuralisMuted
                )
            }
        }
    }

    @Composable
    private fun MiniPlayer(
        song: Song,
        onOpen: () -> Unit,
        onPlayPause: () -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
                .clip(RoundedCornerShape(18.dp)).background(AuralisPanel2)
                .pointerInput(song.id) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragEnd = {
                            when {
                                abs(dx) > abs(dy) && abs(dx) > 70f -> if (dx < 0f) onNext() else onPrevious()
                                dy < -70f -> onOpen()
                            }
                            dx = 0f
                            dy = 0f
                        },
                        onDrag = { change, amount ->
                            dx += amount.x
                            dy += amount.y
                            change.consume()
                        }
                    )
                }
                .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3150)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Album, null, tint = AuralisAccent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = AuralisText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = AuralisMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизведение", tint = AuralisAccent, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = onOpen) { Icon(Icons.Default.OpenInFull, "Открыть плеер", tint = AuralisMuted) }
        }
    }

    @Composable
    private fun FullPlayerScreen() {
        val song = currentSong
        val total = durationMs.coerceAtLeast(1L)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showPlayer = false }) { Icon(Icons.Default.KeyboardArrowDown, "Свернуть плеер", tint = AuralisText, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.weight(1f))
                Text("СЕЙЧАС ИГРАЕТ", color = AuralisMuted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Очередь", tint = AuralisAccent) }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6D53A6), Color(0xFF302A42), Color(0xFF161B28))))
                    .pointerInput(song?.id, queueIndex) {
                        var dx = 0f
                        var dy = 0f
                        detectDragGestures(
                            onDragEnd = {
                                when {
                                    abs(dx) > abs(dy) && abs(dx) > 75f -> if (dx < 0f) nextTrack() else previousTrack()
                                    dy > 95f -> showPlayer = false
                                }
                                dx = 0f
                                dy = 0f
                            },
                            onDrag = { change, amount ->
                                dx += amount.x
                                dy += amount.y
                                change.consume()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFD4C4FF), modifier = Modifier.size(130.dp))
                Text("AURALIS", color = Color(0x66FFFFFF), fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            }
            Text("Свайпни по обложке: влево/вправо — трек, вниз — свернуть", color = AuralisMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 9.dp))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "Ничего не играет", color = AuralisText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song?.artist ?: "Выбери трек в поиске", color = AuralisMuted, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { song?.let { toggleFavorite(it) } }, enabled = song != null) {
                    Icon(
                        if (song?.let { store.isFavorite(it.id) } == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Избранное",
                        tint = if (song?.let { store.isFavorite(it.id) } == true) AuralisAccent else AuralisMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = { song?.let { addToPlaylistSong = it } }, enabled = song != null) {
                    Icon(Icons.Default.PlaylistAdd, "В плейлист", tint = AuralisMuted)
                }
            }
            Spacer(Modifier.height(18.dp))
            Slider(
                value = positionMs.coerceIn(0L, total).toFloat(),
                onValueChange = { positionMs = it.toLong() },
                onValueChangeFinished = { controller?.seekTo(positionMs) },
                valueRange = 0f..total.toFloat(),
                enabled = song != null && durationMs > 0L,
                colors = SliderDefaults.colors(thumbColor = AuralisAccent, activeTrackColor = AuralisAccent)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(positionMs), color = AuralisMuted, style = MaterialTheme.typography.labelSmall)
                Text(if (durationMs > 0L) formatTime(durationMs) else "--:--", color = AuralisMuted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { shuffleEnabled = !shuffleEnabled }) {
                    Icon(Icons.Default.Shuffle, "Перемешать", tint = if (shuffleEnabled) AuralisAccent else AuralisMuted)
                }
                IconButton(onClick = { previousTrack() }, enabled = song != null) {
                    Icon(Icons.Default.SkipPrevious, "Предыдущий трек", tint = AuralisText, modifier = Modifier.size(34.dp))
                }
                FilledIconButton(
                    onClick = { togglePlayback() },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    enabled = song != null && !isBuffering,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = AuralisAccent, contentColor = AuralisBg)
                ) {
                    if (isBuffering) CircularProgressIndicator(Modifier.size(28.dp), color = AuralisBg)
                    else Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизведение", modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { nextTrack() }, enabled = song != null) {
                    Icon(Icons.Default.SkipNext, "Следующий трек", tint = AuralisText, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { repeatMode = (repeatMode + 1) % 3 }) {
                    Icon(
                        if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Режим повтора",
                        tint = if (repeatMode == 0) AuralisMuted else AuralisAccent
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeDown, null, tint = AuralisMuted)
                Slider(value = volume, onValueChange = {
                    volume = it
                    controller?.volume = it
                }, modifier = Modifier.weight(1f))
                Icon(Icons.Default.VolumeUp, null, tint = AuralisMuted)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Таймер сна", color = AuralisText, fontWeight = FontWeight.SemiBold)
                    Text(if (sleepTimerMinutes > 0) "Остановится через $sleepTimerMinutes мин." else "Не установлен", color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { showTimerDialog = true }) { Icon(Icons.Default.Timer, "Настроить таймер", tint = AuralisAccent) }
                TextButton(onClick = { showQueue = true }) { Text("Очередь", color = AuralisAccent) }
            }
            Text("Источник: ${song?.sourceLabel ?: "Auralis"}", color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
            if (song?.licenseUrl?.isNotBlank() == true) {
                TextButton(onClick = { openExternal(song.licenseUrl) }) { Text("Посмотреть лицензию", color = AuralisAccent) }
            }
            if (message.isNotBlank()) Text(message, color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun EmptyState(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFF29263A)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = AuralisAccent, modifier = Modifier.size(35.dp))
            }
            Spacer(Modifier.height(13.dp))
            Text(title, color = AuralisText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = AuralisMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    private fun searchMusic(term: String = query) {
        val clean = term.trim()
        if (clean.isBlank()) {
            resultMessage = "Введи название или исполнителя"
            return
        }
        query = clean
        val selectedScope = scope
        lifecycleScope.launch {
            loading = true
            resultMessage = "Ищем полные аудиозаписи…"
            searchResults.clear()
            store.addSearchQuery(clean)
            val localMatches = store.localSongs().filter { song ->
                val text = when (selectedScope) {
                    CatalogScope.TITLE -> song.title
                    CatalogScope.ARTIST -> song.artist
                    CatalogScope.GENRE -> song.tags.joinToString(" ")
                    CatalogScope.ALL -> "${song.title} ${song.artist} ${song.tags.joinToString(" ")}"
                }.lowercase()
                val q = clean.lowercase()
                text.contains(q) || q.split(Regex("\\s+")).filter { it.length > 1 }.any { text.contains(it) }
            }
            runCatching { catalog.search(clean, selectedScope, jamendoClientId) }
                .onSuccess { outcome ->
                    val merged = (localMatches + outcome.songs).distinctBy { it.id }
                    searchResults.addAll(merged)
                    outcome.songs.forEach(store::saveSong)
                    resultMessage = buildString {
                        append("Найдено: ${merged.size}")
                        if (jamendoClientId.isNotBlank() && outcome.jamendoError != null) {
                            append(" · Jamendo: ")
                            append(outcome.jamendoError.take(90))
                        }
                        if (outcome.archiveError != null && outcome.songs.isEmpty()) {
                            append(" · Internet Archive: ")
                            append(outcome.archiveError.take(90))
                        }
                    }
                    activeTab = 1
                }
                .onFailure { error ->
                    resultMessage = error.message ?: "Не удалось выполнить поиск"
                }
            loading = false
        }
    }

    private fun playSong(song: Song, sourceQueue: List<Song> = listOf(song)) {
        queue.clear()
        queue.addAll(sourceQueue.distinctBy { it.id })
        if (queue.none { it.id == song.id }) queue.add(0, song)
        queueIndex = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playSelectedSong(song)
    }

    private fun playQueueIndex(index: Int) {
        if (index !in queue.indices) return
        queueIndex = index
        playSelectedSong(queue[index])
    }

    private fun playSelectedSong(song: Song) {
        lifecycleScope.launch {
            message = "Подготавливаем полную запись…"
            runCatching {
                val ready = catalog.resolveStream(song)
                store.saveSong(ready)
                val player = controller ?: error("Плеер ещё подключается")
                val metadata = MediaMetadata.Builder()
                    .setTitle(ready.title)
                    .setArtist(ready.artist)
                    .setAlbumTitle(ready.album)
                    .apply {
                        if (ready.artworkUrl.isNotBlank()) setArtworkUri(Uri.parse(ready.artworkUrl))
                    }
                    .build()
                val item = MediaItem.Builder()
                    .setMediaId(ready.id)
                    .setUri(ready.url)
                    .setMediaMetadata(metadata)
                    .build()
                player.setMediaItem(item)
                player.prepare()
                player.play()
                currentSong = ready
                isPlaying = true
                message = "Воспроизведение запущено"
                showPlayer = true
            }.onFailure { error ->
                message = error.message ?: "Не удалось запустить трек"
            }
        }
    }

    private fun togglePlayback() {
        controller?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    private fun previousTrack() {
        val player = controller
        if (player != null && player.currentPosition > 3500L) {
            player.seekTo(0L)
            return
        }
        if (queue.isEmpty()) return
        val previous = if (queueIndex > 0) queueIndex - 1 else if (repeatMode == 1) queue.lastIndex else queueIndex
        playQueueIndex(previous)
    }

    private fun nextTrack() {
        if (queue.isEmpty()) return
        val next = if (shuffleEnabled && queue.size > 1) {
            (queue.indices.filter { it != queueIndex }).random()
        } else if (queueIndex < queue.lastIndex) {
            queueIndex + 1
        } else if (repeatMode == 1) {
            0
        } else {
            queueIndex
        }
        if (next == queueIndex && repeatMode != 1 && queueIndex == queue.lastIndex) {
            controller?.pause()
            controller?.seekTo(0L)
            return
        }
        playQueueIndex(next)
    }

    private fun handleTrackEnded() {
        when {
            repeatMode == 2 -> {
                controller?.seekTo(0L)
                controller?.play()
            }
            queue.isNotEmpty() && (queueIndex < queue.lastIndex || repeatMode == 1) -> nextTrack()
            else -> {
                isPlaying = false
                message = "Очередь закончилась"
            }
        }
    }

    private fun toggleFavorite(song: Song) {
        val saved = store.toggleFavorite(song)
        message = if (saved) "Добавлено в избранное" else "Удалено из избранного"
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerMinutes = minutes
        sleepTimerJob = lifecycleScope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            sleepTimerMinutes = 0
            message = "Таймер сна остановил воспроизведение"
        }
        message = "Таймер сна: $minutes мин."
    }

    private fun openExternal(address: String) {
        if (address.isBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
        }.onFailure {
            message = "Не удалось открыть ссылку"
        }
    }

    private fun packageVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.3" }.getOrDefault("0.3")

    private fun formatTime(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1000L)
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

    override fun onDestroy() {
        sleepTimerJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}
