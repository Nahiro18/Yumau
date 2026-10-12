package moe.rukamori.archivetune.desktop.app

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.runtime.DesktopMediaItem
import moe.rukamori.archivetune.desktop.runtime.DesktopPlayer
import moe.rukamori.archivetune.desktop.runtime.DesktopPlayerState
import moe.rukamori.archivetune.innertube.SearchFilter
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.lrclib.LrcLib
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTrack
import moe.rukamori.archivetune.spotify.models.SpotifySavedTrack
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import java.io.File

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun playTrack(
    scope: CoroutineScope,
    player: DesktopPlayer,
    songId: String,
    title: String,
    artist: String,
    durationSeconds: Int?,
    thumbnailUrl: String?,
) {
    val initialDurationMs = (durationSeconds ?: 0) * 1000L
    // Immediate visual feedback (0ms): show track with "Cargando audio..." state
    player.setPreparing(
        DesktopMediaItem(
            id = songId,
            title = title,
            artist = artist,
            uri = "",
            durationMs = initialDurationMs,
            thumbnailUrl = thumbnailUrl,
            audioFormat = "Cargando audio..."
        )
    )

    scope.launch {
        DesktopLibraryStore.recordHistory(
            StoredSong(
                id = songId,
                title = title,
                artist = artist,
                durationMs = initialDurationMs,
                thumbnailUrl = thumbnailUrl
            )
        )
        val s = DesktopSettingsStore.settings.value
        val stream = DesktopStreamResolver.resolveStreamUrl(
            videoId = songId,
            title = title,
            artist = artist,
            durationMs = initialDurationMs,
            playbackSource = s.playbackSource,
            flacQuality = s.flacQuality,
            preferredQuality = s.audioQuality,
            preferredClientName = s.streamingClient
        )
        val finalDurationMs = if (stream.durationMs > 0) stream.durationMs else initialDurationMs
        val media = DesktopMediaItem(
            id = songId,
            title = title,
            artist = artist,
            uri = stream.url,
            durationMs = finalDurationMs,
            thumbnailUrl = thumbnailUrl,
            audioFormat = stream.formatLabel
        )
        player.setQueue(listOf(media), startIndex = 0)

        // Auto-cola inteligente (Radio algorithm) - Llena la cola con ~25 canciones recomendadas
        scope.launch(Dispatchers.IO) {
            runCatching {
                val nextResult = YouTube.next(WatchEndpoint(videoId = songId)).getOrNull()
                val recommended = nextResult?.items
                    ?.filter { it.id != songId }
                    ?.distinctBy { it.id }
                    ?.take(25)
                    ?.map { s ->
                        DesktopMediaItem(
                            id = s.id,
                            title = s.title,
                            artist = s.artists.joinToString(", ") { it.name },
                            uri = "",
                            durationMs = (s.duration ?: 0) * 1000L,
                            thumbnailUrl = s.thumbnail,
                            audioFormat = null
                        )
                    }.orEmpty()
                if (recommended.isNotEmpty()) {
                    val currentTrack = player.queue.value.getOrNull(player.currentIndex.value)
                    if (currentTrack?.id == songId) {
                        player.addToQueue(recommended)
                    }
                }
            }
        }
    }
}

fun playSpotifyTrack(
    scope: CoroutineScope,
    player: DesktopPlayer,
    track: SpotifyTrack,
) {
    player.setPreparing(
        DesktopMediaItem(
            id = track.id,
            title = track.name,
            artist = track.artists.joinToString(", ") { it.name },
            uri = "",
            durationMs = track.durationMs.toLong(),
            thumbnailUrl = track.album?.images?.firstOrNull()?.url,
            audioFormat = "Buscando en YouTube..."
        )
    )
    scope.launch {
        val ytSong = DesktopSpotifyStore.matchSpotifyTrackToYouTube(track)
        if (ytSong != null) {
            playTrack(
                scope = scope,
                player = player,
                songId = ytSong.id,
                title = track.name,
                artist = track.artists.joinToString(", ") { it.name },
                durationSeconds = ytSong.duration ?: (track.durationMs / 1000),
                thumbnailUrl = track.album?.images?.firstOrNull()?.url ?: ytSong.thumbnail
            )
        }
    }
}

fun playQueue(
    player: DesktopPlayer,
    items: List<DesktopMediaItem>,
    startIndex: Int = 0,
) {
    if (items.isEmpty()) return
    player.setQueue(items, startIndex = startIndex)
}

fun main() {
    val cipherCache = File(System.getProperty("user.home"), ".cache/YumaPlayer/mori_cipher").apply { mkdirs() }
    runCatching {
        moe.rukamori.archivetune.obfuscator.MoriCipherRuntime.initialize(
            moe.rukamori.archivetune.obfuscator.MoriCipherConfig(
                cacheDirectory = cipherCache,
                proxyProvider = { moe.rukamori.archivetune.innertube.YouTube.streamProxy }
            )
        )
    }

    // Warm up YouTube session credentials in background for instant stream resolution
    CoroutineScope(Dispatchers.IO).launch {
        runCatching { YouTube.visitorData() }
    }

    val player = DesktopPlayer()
    player.itemResolver = { mediaItem ->
        val s = DesktopSettingsStore.settings.value
        var actualVideoId = mediaItem.id
        if (actualVideoId.startsWith("spt:") || actualVideoId.length == 22) {
            val matched = DesktopSpotifyStore.matchTitleArtistToYouTube(mediaItem.title, mediaItem.artist)
            if (matched != null) {
                actualVideoId = matched.id
            }
        }
        val stream = DesktopStreamResolver.resolveStreamUrl(
            videoId = actualVideoId,
            title = mediaItem.title,
            artist = mediaItem.artist,
            durationMs = mediaItem.durationMs,
            playbackSource = s.playbackSource,
            flacQuality = s.flacQuality,
            preferredQuality = s.audioQuality,
            preferredClientName = s.streamingClient
        )
        val dur = if (stream.durationMs > 0) stream.durationMs else mediaItem.durationMs
        mediaItem.copy(
            id = actualVideoId,
            uri = stream.url,
            durationMs = dur,
            audioFormat = stream.formatLabel
        )
    }
    try {
        player.init()
        val s = DesktopSettingsStore.settings.value
        player.autoSkipNextOnError = s.autoSkipNextOnError
        player.updateAudioFilters(
            volumeNormalization = s.volumeNormalization,
            skipSilence = s.skipSilence
        )
        if (s.contentCountry != "system") {
            moe.rukamori.archivetune.innertube.YouTube.locale = moe.rukamori.archivetune.innertube.YouTube.locale.copy(gl = s.contentCountry)
        }
        if (s.contentLanguage != "system") {
            moe.rukamori.archivetune.innertube.YouTube.locale = moe.rukamori.archivetune.innertube.YouTube.locale.copy(hl = s.contentLanguage)
        }
    } catch (e: Exception) {
        System.err.println("Warning: Player engine init failed: ${e.message}")
    }

    application {
        Window(
            onCloseRequest = {
                player.release()
                exitApplication()
            },
            state = rememberWindowState(width = 1240.dp, height = 820.dp),
            title = "YumaPlayer - Linux Mint",
        ) {
            YumaApp(player)
        }
    }
}

private val GlassCardBackground = Color(0x302A2E3D)
private val AccentGradient = Brush.horizontalGradient(listOf(Color(0xFF7C3AED), Color(0xFFDB2777)))
private val SpotifyBrandGreen = Color(0xFF1DB954)

@Composable
fun YumaApp(player: DesktopPlayer) {
    val settings by DesktopSettingsStore.settings.collectAsState()
    val scope = rememberCoroutineScope()
    // 0: Inicio, 1: Buscar, 2: Biblioteca, 3: Ajustes, 4: Letras, 5: Cola
    var selectedSection by remember { mutableStateOf(settings.defaultTab) }
    var previousSection by remember { mutableStateOf(settings.defaultTab) }
    var currentLyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var rawPlainLyrics by remember { mutableStateOf<String?>(null) }
    var isLyricsLoading by remember { mutableStateOf(false) }

    val queue by player.queue.collectAsState()
    val currentIndex by player.currentIndex.collectAsState()
    val currentSong = queue.getOrNull(currentIndex)

    LaunchedEffect(currentSong?.id) {
        val song = currentSong
        if (song != null) {
            isLyricsLoading = true
            currentLyrics = emptyList()
            rawPlainLyrics = null
            withContext(Dispatchers.IO) {
                try {
                    val result = DesktopLyricsManager.getLyrics(
                        videoId = song.id,
                        title = song.title,
                        artist = song.artist,
                        durationSeconds = if (song.durationMs > 0) (song.durationMs / 1000).toInt() else -1
                    )
                    if (result != null) {
                        val parsed = LyricsParser.parse(result)
                        if (parsed.isNotEmpty()) {
                            currentLyrics = parsed
                        } else {
                            rawPlainLyrics = result
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    isLyricsLoading = false
                }
            }
        } else {
            currentLyrics = emptyList()
            rawPlainLyrics = null
        }
    }

    val darkScheme = darkColorScheme(
        primary = Color(0xFF8B5CF6),
        secondary = Color(0xFFEC4899),
        background = if (settings.pureBlack) Color(0xFF000000) else Color(0xFF0F1117),
        surface = if (settings.pureBlack) Color(0xFF050505) else Color(0xFF161922),
        onBackground = Color(0xFFF3F4F6),
        onSurface = Color(0xFFF3F4F6),
    )

    MaterialTheme(colorScheme = darkScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(
                        selectedSection = selectedSection,
                        onSelectSection = {
                            if (selectedSection < 4) previousSection = selectedSection
                            selectedSection = it
                        }
                    )

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        when (selectedSection) {
                            0 -> DesktopHomeScreen(
                                onPlayTrack = { songId, title, artist, duration, thumb ->
                                    playTrack(scope, player, songId, title, artist, duration, thumb)
                                },
                                onPlaySpotifyTrack = { track ->
                                    playSpotifyTrack(scope, player, track)
                                },
                                onNavigateToSettings = { selectedSection = 3 }
                            )
                            1 -> SearchScreen(
                                player = player,
                                onPlayTrack = { songId, title, artist, duration, thumb ->
                                    playTrack(scope, player, songId, title, artist, duration, thumb)
                                },
                                onPlaySpotifyTrack = { track ->
                                    playSpotifyTrack(scope, player, track)
                                }
                            )
                            2 -> DesktopLibraryScreen(
                                onPlayTrack = { songId, title, artist, duration, thumb ->
                                    playTrack(scope, player, songId, title, artist, duration, thumb)
                                },
                                onPlaySpotifyTrack = { track ->
                                    playSpotifyTrack(scope, player, track)
                                },
                                onPlayQueue = { items, startIndex ->
                                    playQueue(player, items, startIndex)
                                },
                                onNavigateToSettings = { selectedSection = 3 }
                            )
                            3 -> SettingsScreen(player = player)
                            4 -> LyricsScreen(
                                lyrics = currentLyrics,
                                plainLyrics = rawPlainLyrics,
                                isLoading = isLyricsLoading,
                                player = player,
                                onBack = { selectedSection = previousSection }
                            )
                            5 -> QueueScreen(
                                player = player,
                                onBack = { selectedSection = previousSection }
                            )
                        }
                    }
                }

                PlayerBar(
                    player = player,
                    onOpenLyrics = {
                        if (selectedSection != 4) {
                            previousSection = selectedSection
                            selectedSection = 4
                        } else {
                            selectedSection = previousSection
                        }
                    },
                    onOpenQueue = {
                        if (selectedSection != 5) {
                            previousSection = selectedSection
                            selectedSection = 5
                        } else {
                            selectedSection = previousSection
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun Sidebar(selectedSection: Int, onSelectSection: (Int) -> Unit) {
    Surface(
        modifier = Modifier.width(230.dp).fillMaxHeight(),
        color = Color(0xFF141720)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AccentGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "YumaPlayer",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "Linux Mint Edition",
                            fontSize = 11.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }

                // Identical to mobile tabs: Inicio, Buscar, Biblioteca, Ajustes
                val items = listOf(
                    Triple(0, "Inicio", Icons.Default.Home),
                    Triple(1, "Buscar", Icons.Default.Search),
                    Triple(2, "Biblioteca", Icons.Default.LibraryMusic),
                    Triple(3, "Ajustes", Icons.Default.Settings),
                )

                items.forEach { (index, title, icon) ->
                    val isSelected = selectedSection == index
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectSection(index) },
                        color = if (isSelected) Color(0xFF282542) else Color.Transparent
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                icon,
                                contentDescription = title,
                                tint = if (isSelected) Color(0xFFA78BFA) else Color(0xFF9CA3AF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                title,
                                color = if (isSelected) Color.White else Color(0xFFD1D5DB),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Text(
                "YumaPlayer v1.0.0",
                fontSize = 10.sp,
                color = Color(0xFF6B7280),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

sealed class LibraryMatchItem(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnail: String?,
    val sourceLabel: String
) {
    class Spotify(val track: SpotifyTrack) : LibraryMatchItem(
        title = track.name,
        artist = track.artists.joinToString(", ") { it.name },
        durationMs = track.durationMs.toLong(),
        thumbnail = track.album?.images?.firstOrNull()?.url,
        sourceLabel = "Spotify Me Gusta"
    )
    class Local(val song: StoredSong, val isFav: Boolean) : LibraryMatchItem(
        title = song.title,
        artist = song.artist,
        durationMs = song.durationMs,
        thumbnail = song.thumbnailUrl,
        sourceLabel = if (isFav) "Favorito" else "Historial"
    )
}

@Composable
fun SearchScreen(
    player: DesktopPlayer,
    onPlayTrack: (songId: String, title: String, artist: String, durationSeconds: Int?, thumbnailUrl: String?) -> Unit,
    onPlaySpotifyTrack: (track: SpotifyTrack) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val recentSearches by DesktopLibraryStore.recentSearches.collectAsState()
    val spotifyLiked by DesktopSpotifyStore.likedSongs.collectAsState()
    val favorites by DesktopLibraryStore.favorites.collectAsState()
    val history by DesktopLibraryStore.history.collectAsState()

    // 0ms instant filter prioritizing user's known songs
    val instantMatches = remember(query, spotifyLiked, favorites, history) {
        val q = query.trim()
        if (q.length < 2) emptyList()
        else {
            val list = mutableListOf<LibraryMatchItem>()
            for (item in spotifyLiked) {
                val t = item.track
                if (t.name.contains(q, ignoreCase = true) || t.artists.any { it.name.contains(q, ignoreCase = true) }) {
                    list.add(LibraryMatchItem.Spotify(t))
                }
            }
            for (fav in favorites) {
                if (fav.title.contains(q, ignoreCase = true) || fav.artist.contains(q, ignoreCase = true)) {
                    if (list.none { it.title.equals(fav.title, ignoreCase = true) }) {
                        list.add(LibraryMatchItem.Local(fav, isFav = true))
                    }
                }
            }
            for (h in history) {
                if (h.title.contains(q, ignoreCase = true) || h.artist.contains(q, ignoreCase = true)) {
                    if (list.none { it.title.equals(h.title, ignoreCase = true) }) {
                        list.add(LibraryMatchItem.Local(h, isFav = false))
                    }
                }
            }
            list.sortedByDescending {
                if (it.title.startsWith(q, ignoreCase = true)) 2
                else if (it.artist.startsWith(q, ignoreCase = true)) 1
                else 0
            }.take(5)
        }
    }

    var debounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun doSearch(targetQuery: String = query) {
        val q = targetQuery.trim()
        if (q.isBlank()) return
        debounceJob?.cancel()
        isSearching = true
        scope.launch(Dispatchers.IO) {
            DesktopLibraryStore.addSearchQuery(q)
            try {
                val filter = when (filterType) {
                    0 -> SearchFilter.FILTER_SONG
                    1 -> SearchFilter.FILTER_ALBUM
                    2 -> SearchFilter.FILTER_ARTIST
                    3 -> SearchFilter.FILTER_FEATURED_PLAYLIST
                    else -> SearchFilter.FILTER_SONG
                }
                val res = YouTube.search(q, filter = filter).getOrNull()
                results = res?.items?.filterIsInstance<SongItem>() ?: emptyList()
            } catch (_: Exception) {
                results = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    // Debounced search while typing (350ms)
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length >= 3) {
            debounceJob?.cancel()
            debounceJob = launch {
                kotlinx.coroutines.delay(350)
                isSearching = true
                withContext(Dispatchers.IO) {
                    try {
                        val filter = when (filterType) {
                            0 -> SearchFilter.FILTER_SONG
                            1 -> SearchFilter.FILTER_ALBUM
                            2 -> SearchFilter.FILTER_ARTIST
                            3 -> SearchFilter.FILTER_FEATURED_PLAYLIST
                            else -> SearchFilter.FILTER_SONG
                        }
                        val res = YouTube.search(q, filter = filter).getOrNull()
                        results = res?.items?.filterIsInstance<SongItem>() ?: emptyList()
                    } catch (_: Exception) {
                    } finally {
                        isSearching = false
                    }
                }
            }
        } else if (q.isEmpty()) {
            results = emptyList()
            isSearching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar canciones, artistas, géneros... (Presiona Enter)") },
                modifier = Modifier
                    .weight(1f)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyUp) {
                            if (query.isNotBlank()) {
                                doSearch(query)
                            }
                            true
                        } else false
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank()) doSearch(query)
                }),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            results = emptyList()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpiar", tint = Color.Gray)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF374151)
                )
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { doSearch(query) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Buscar")
            }
        }

        Row(Modifier.padding(vertical = 10.dp)) {
            val filters = listOf("Canciones", "Álbumes", "Artistas", "Playlists")
            filters.forEachIndexed { idx, label ->
                FilterChip(
                    selected = filterType == idx,
                    onClick = {
                        filterType = idx
                        if (query.isNotBlank()) doSearch(query)
                    },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        if (query.isBlank()) {
            if (recentSearches.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Búsquedas recientes", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    TextButton(onClick = { scope.launch { DesktopLibraryStore.clearSearchQueries() } }) {
                        Text("Borrar historial", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(recentSearches) { term ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    query = term
                                    doSearch(term)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(term, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { scope.launch { DesktopLibraryStore.removeSearchQuery(term) } },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Eliminar", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Escribe el nombre de una canción o artista y presiona Enter", color = Color.Gray)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                if (instantMatches.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("En tu biblioteca y reproducciones", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0x331DB954))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Recomendado para ti", color = Color(0xFF1DB954), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    items(instantMatches) { match ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x252A2E3D))
                                .clickable {
                                    when (match) {
                                        is LibraryMatchItem.Spotify -> onPlaySpotifyTrack(match.track)
                                        is LibraryMatchItem.Local -> onPlayTrack(
                                            match.song.id,
                                            match.song.title,
                                            match.song.artist,
                                            (match.song.durationMs / 1000).toInt(),
                                            match.song.thumbnailUrl
                                        )
                                    }
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val img = rememberNetworkImage(match.thumbnail)
                            if (img != null) {
                                Image(
                                    bitmap = img,
                                    contentDescription = match.title,
                                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2E3D)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(match.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White, maxLines = 1)
                                Text("${match.artist} • ${match.sourceLabel}", fontSize = 12.sp, color = Color(0xFF9CA3AF), maxLines = 1)
                            }
                            IconButton(onClick = {
                                when (match) {
                                    is LibraryMatchItem.Spotify -> onPlaySpotifyTrack(match.track)
                                    is LibraryMatchItem.Local -> onPlayTrack(
                                        match.song.id,
                                        match.song.title,
                                        match.song.artist,
                                        (match.song.durationMs / 1000).toInt(),
                                        match.song.thumbnailUrl
                                    )
                                }
                            }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = Color(0xFF1DB954))
                            }
                        }
                    }
                }

                item {
                    Text(
                        if (instantMatches.isNotEmpty()) "Resultados en línea de YouTube Music" else "Resultados de búsqueda",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (isSearching && results.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (results.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            Text("No se encontraron resultados para '$query'", color = Color.Gray)
                        }
                    }
                } else {
                    items(results) { song ->
                        SongRowItem(
                            song = song,
                            onPlay = {
                                onPlayTrack(song.id, song.title, song.artists.joinToString(", ") { it.name }, song.duration, song.thumbnail)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(player: DesktopPlayer? = null) {
    val spotifySession by DesktopSpotifyStore.session.collectAsState()
    val scope = rememberCoroutineScope()
    var isBrowserAuthWaiting by remember { mutableStateOf(false) }

    val spotifyStatusText = when {
        isBrowserAuthWaiting -> "Esperando autorización en el navegador..."
        spotifySession != null -> "Conectado como ${spotifySession?.displayName ?: "Usuario de Spotify"}"
        else -> "No conectado"
    }

    DesktopSettingsRoot(
        player = player,
        onOpenSpotifyBrowserLogin = {
            isBrowserAuthWaiting = true
            DesktopSpotifyBrowserAuth.startBrowserLogin(
                scope = scope,
                onSuccess = {
                    scope.launch {
                        isBrowserAuthWaiting = false
                        DesktopSpotifyStore.refreshLibrary()
                    }
                },
                onError = {
                    isBrowserAuthWaiting = false
                }
            )
        },
        spotifyStatusText = spotifyStatusText,
        onSyncSpotify = {
            scope.launch { DesktopSpotifyStore.refreshLibrary() }
        },
        onDisconnectSpotify = {
            scope.launch { DesktopSpotifyStore.logout() }
        }
    )
}

@Composable
fun LyricsScreen(
    lyrics: List<LyricLine>,
    plainLyrics: String?,
    isLoading: Boolean,
    player: DesktopPlayer,
    onBack: () -> Unit
) {
    val settings by DesktopSettingsStore.settings.collectAsState()
    val position by player.positionMs.collectAsState()
    val currentLineIndex = remember(lyrics, position) {
        LyricsParser.getCurrentLineIndex(lyrics, position)
    }
    val listState = rememberLazyListState()

    LaunchedEffect(currentLineIndex, settings.autoScrollLyrics) {
        if (settings.autoScrollLyrics && currentLineIndex >= 0) {
            val target = (currentLineIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text("Letras de la Canción", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (lyrics.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(lyrics) { idx, line ->
                    val isCurrent = idx == currentLineIndex
                    val itemFontSize = if (isCurrent) settings.lyricsTextSize.sp else (settings.lyricsTextSize * 0.78f).sp
                    val itemColor = when {
                        isCurrent -> Color(0xFFA78BFA)
                        settings.lyricsLineBlur -> Color.White.copy(alpha = 0.35f)
                        else -> Color(0xFF9CA3AF)
                    }

                    Text(
                        line.text,
                        fontSize = itemFontSize,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = itemColor,
                        modifier = Modifier
                            .padding(vertical = (8 * settings.lyricsLineSpacing).dp)
                            .then(
                                if (settings.lyricsClick) {
                                    Modifier.clickable { player.seekTo(line.timestampMs) }
                                } else Modifier
                            )
                    )
                }
            }
        } else if (!plainLyrics.isNullOrBlank()) {
            LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Text(
                        plainLyrics,
                        fontSize = settings.lyricsTextSize.sp,
                        color = Color(0xFFD1D5DB),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay letras disponibles para la pista actual.", color = Color.Gray)
            }
        }
    }
}

@Composable
fun QueueScreen(player: DesktopPlayer, onBack: () -> Unit) {
    val queue by player.queue.collectAsState()
    val currentIndex by player.currentIndex.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text("Cola de Reproducción (${queue.size})", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(16.dp))

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("La cola está vacía", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(queue) { idx, item ->
                    val isPlaying = idx == currentIndex
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { player.playIndex(idx) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isPlaying) Color(0xFF2E274A) else GlassCardBackground
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${idx + 1}",
                                color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.width(30.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isPlaying) Color(0xFFA78BFA) else Color.White
                                )
                                Text(item.artist, color = Color.Gray, fontSize = 12.sp)
                            }
                            if (isPlaying) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpotifyTrackRow(track: SpotifyTrack, onPlay: () -> Unit) {
    val thumbBitmap = rememberNetworkImage(track.album?.images?.firstOrNull()?.url)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onPlay() },
        colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2D3139)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(track.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track.artists.joinToString(", ") { it.name },
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                formatTime(track.durationMs.toLong()),
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun SongRowItem(song: SongItem, onPlay: () -> Unit) {
    val favorites by DesktopLibraryStore.favorites.collectAsState()
    val isFav = favorites.any { it.id == song.id }
    val scope = rememberCoroutineScope()
    val thumbBitmap = rememberNetworkImage(song.thumbnail)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onPlay() },
        colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2D3139)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(song.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    song.artists.joinToString(", ") { it.name },
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = {
                scope.launch {
                    DesktopLibraryStore.toggleFavorite(
                        StoredSong(
                            id = song.id,
                            title = song.title,
                            artist = song.artists.joinToString(", ") { it.name },
                            durationMs = (song.duration ?: 0) * 1000L,
                            thumbnailUrl = song.thumbnail
                        )
                    )
                }
            }) {
                Icon(
                    if (isFav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (isFav) Color(0xFFEF4444) else Color.Gray
                )
            }

            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun StoredSongRow(stored: StoredSong, onPlay: () -> Unit) {
    val favorites by DesktopLibraryStore.favorites.collectAsState()
    val isFav = favorites.any { it.id == stored.id }
    val scope = rememberCoroutineScope()
    val thumbBitmap = rememberNetworkImage(stored.thumbnailUrl)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onPlay() },
        colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2D3139)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(stored.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stored.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            IconButton(onClick = {
                scope.launch { DesktopLibraryStore.toggleFavorite(stored) }
            }) {
                Icon(
                    if (isFav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (isFav) Color(0xFFEF4444) else Color.Gray
                )
            }

            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun PlayerBar(
    player: DesktopPlayer,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit
) {
    val state by player.state.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    val volume by player.volume.collectAsState()
    val queue by player.queue.collectAsState()
    val index by player.currentIndex.collectAsState()
    val repeatMode by player.repeatModeFlow.collectAsState()
    val shuffleEnabled by player.shuffleEnabledFlow.collectAsState()
    val favorites by DesktopLibraryStore.favorites.collectAsState()
    val currentFormat by player.currentFormat.collectAsState()
    val settings by DesktopSettingsStore.settings.collectAsState()

    val current = queue.getOrNull(index)
    val isFav = current != null && favorites.any { it.id == current.id }
    val scope = rememberCoroutineScope()
    val thumbBitmap = rememberNetworkImage(current?.thumbnailUrl)

    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }

    Surface(
        color = Color(0xFF13151D),
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth().border(BorderStroke(1.dp, Color(0x22FFFFFF)))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (thumbBitmap != null) {
                        Image(
                            bitmap = thumbBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF282B35)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray)
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                current?.title ?: if (state == DesktopPlayerState.IDLE) "Listo para reproducir" else "",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White
                            )
                            val badge = currentFormat ?: current?.audioFormat
                            if (!badge.isNullOrBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = Color(0xFF2D224C),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(
                                        badge,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFA78BFA),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            current?.artist ?: "YumaPlayer",
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (current != null) {
                        IconButton(onClick = {
                            scope.launch {
                                DesktopLibraryStore.toggleFavorite(
                                    StoredSong(
                                        id = current.id,
                                        title = current.title,
                                        artist = current.artist,
                                        durationMs = current.durationMs,
                                        thumbnailUrl = current.thumbnailUrl
                                    )
                                )
                            }
                        }) {
                            Icon(
                                if (isFav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Me gusta",
                                tint = if (isFav) Color(0xFFEF4444) else Color.Gray
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1.5f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = { player.toggleShuffle() }) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = "Aleatorio",
                                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        IconButton(onClick = {
                            player.seekRelative(-settings.seekDurationSeconds.toDouble())
                        }) {
                            Icon(Icons.Default.FastRewind, contentDescription = "-${settings.seekDurationSeconds}s", tint = Color.LightGray)
                        }

                        IconButton(onClick = { player.prev() }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior", tint = Color.White)
                        }

                        IconButton(
                            onClick = { player.playPause() },
                            modifier = Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                if (state == DesktopPlayerState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pausa",
                                tint = Color.White
                            )
                        }

                        IconButton(onClick = { player.next() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Siguiente", tint = Color.White)
                        }

                        IconButton(onClick = {
                            player.seekRelative(settings.seekDurationSeconds.toDouble())
                        }) {
                            Icon(Icons.Default.FastForward, contentDescription = "+${settings.seekDurationSeconds}s", tint = Color.LightGray)
                        }

                        IconButton(onClick = { player.cycleRepeatMode() }) {
                            Icon(
                                when (repeatMode) {
                                    2 -> Icons.Default.RepeatOne
                                    1 -> Icons.Default.Repeat
                                    else -> Icons.Default.Repeat
                                },
                                contentDescription = "Repetir",
                                tint = if (repeatMode > 0) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        val displayPos = if (isDraggingSlider && duration > 0) (dragFrac * duration).toLong() else position
                        Text(formatTime(displayPos), fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(42.dp))

                        val sliderValue = when {
                            isDraggingSlider -> dragFrac
                            duration > 0 -> (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            else -> 0f
                        }

                        Slider(
                            value = sliderValue,
                            onValueChange = { frac ->
                                isDraggingSlider = true
                                dragFrac = frac
                            },
                            onValueChangeFinished = {
                                isDraggingSlider = false
                                if (duration > 0) {
                                    player.seekTo((dragFrac * duration).toLong())
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color(0xFF374151)
                            )
                        )
                        Text(formatTime(duration), fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(42.dp))
                    }
                }

                Row(
                    modifier = Modifier.weight(0.8f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onOpenLyrics) {
                        Icon(Icons.Default.Mic, contentDescription = "Letras", tint = Color.Gray)
                    }

                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Cola", tint = Color.Gray)
                    }

                    var lastNonZeroVolume by remember { mutableStateOf(80) }

                    IconButton(onClick = {
                        if (volume > 0) {
                            lastNonZeroVolume = volume
                            player.setVolume(0)
                        } else {
                            player.setVolume(if (lastNonZeroVolume > 0) lastNonZeroVolume else 80)
                        }
                    }) {
                        Icon(
                            imageVector = when {
                                volume == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                                volume <= 50 -> Icons.AutoMirrored.Filled.VolumeDown
                                else -> Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = if (volume == 0) "Activar sonido" else "Silenciar",
                            tint = if (volume == 0) Color.Red else Color.LightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Slider(
                        value = volume / 100f,
                        onValueChange = { player.setVolume((it * 100).toInt()) },
                        modifier = Modifier.width(90.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFFA78BFA),
                            inactiveTrackColor = Color(0xFF374151)
                        )
                    )
                }
            }
        }
    }
}
