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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import moe.rukamori.archivetune.lrclib.LrcLib
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTrack
import moe.rukamori.archivetune.spotify.models.SpotifySavedTrack
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

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
    scope.launch {
        val initialDurationMs = (durationSeconds ?: 0) * 1000L
        DesktopLibraryStore.recordHistory(
            StoredSong(
                id = songId,
                title = title,
                artist = artist,
                durationMs = initialDurationMs,
                thumbnailUrl = thumbnailUrl
            )
        )
        val quality = DesktopSettingsStore.settings.value.audioQuality
        val stream = DesktopStreamResolver.resolveStreamUrl(songId, quality)
        val finalDurationMs = if (stream.durationMs > 0) stream.durationMs else initialDurationMs
        val media = DesktopMediaItem(
            id = songId,
            title = title,
            artist = artist,
            uri = stream.url,
            durationMs = finalDurationMs,
            thumbnailUrl = thumbnailUrl
        )
        player.setQueue(listOf(media), startIndex = 0)
    }
}

fun main() {
    val player = DesktopPlayer()
    try {
        player.init()
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
    var selectedSection by remember { mutableStateOf(0) }
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
                    val result = LrcLib.getLyrics(
                        title = song.title,
                        artist = song.artist,
                        duration = if (song.durationMs > 0) (song.durationMs / 1000).toInt() else -1
                    ).getOrNull()
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
        background = Color(0xFF0F1117),
        surface = Color(0xFF161922),
        onBackground = Color(0xFFF3F4F6),
        onSurface = Color(0xFFF3F4F6),
    )

    MaterialTheme(colorScheme = darkScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(
                        selectedSection = selectedSection,
                        onSelectSection = { selectedSection = it }
                    )

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        when (selectedSection) {
                            0 -> HomeScreen(player = player)
                            1 -> SearchScreen(player = player)
                            2 -> LibraryScreen(player = player)
                            3 -> SpotifyScreen(player = player)
                            4 -> LyricsScreen(
                                lyrics = currentLyrics,
                                plainLyrics = rawPlainLyrics,
                                isLoading = isLyricsLoading,
                                player = player
                            )
                            5 -> QueueScreen(player = player)
                            6 -> SettingsScreen(onNavigateToSpotify = { selectedSection = 3 })
                        }
                    }
                }

                PlayerBar(
                    player = player,
                    onOpenLyrics = { selectedSection = 4 }
                )
            }
        }
    }
}

@Composable
fun Sidebar(selectedSection: Int, onSelectSection: (Int) -> Unit) {
    val spotifySession by DesktopSpotifyStore.session.collectAsState()

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

                val items = listOf(
                    Triple(0, "Inicio", Icons.Default.Home),
                    Triple(1, "Buscar", Icons.Default.Search),
                    Triple(2, "Biblioteca", Icons.Default.LibraryMusic),
                    Triple(3, if (spotifySession.isLoggedIn) "Spotify" else "Spotify", Icons.Default.Radio),
                    Triple(4, "Letras", Icons.Default.Mic),
                    Triple(5, "Cola", Icons.AutoMirrored.Filled.QueueMusic),
                    Triple(6, "Ajustes", Icons.Default.Settings),
                )

                items.forEach { (index, title, icon) ->
                    val isSelected = selectedSection == index
                    val isSpotify = index == 3
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
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
                                tint = when {
                                    isSpotify && spotifySession.isLoggedIn -> SpotifyBrandGreen
                                    isSelected -> Color(0xFFA78BFA)
                                    else -> Color(0xFF9CA3AF)
                                },
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
                            if (isSpotify && spotifySession.isLoggedIn) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(SpotifyBrandGreen)
                                )
                            }
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

@Composable
fun HomeScreen(player: DesktopPlayer) {
    var exploreSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val chartResult = YouTube.getChartsPage().getOrNull()
                val songs = chartResult?.sections?.flatMap { it.items }?.filterIsInstance<SongItem>() ?: emptyList()
                if (songs.isNotEmpty()) {
                    exploreSongs = songs
                } else {
                    val fallback = YouTube.search("Top Hits 2026", filter = SearchFilter.FILTER_SONG).getOrNull()
                    exploreSongs = fallback?.items?.filterIsInstance<SongItem>() ?: emptyList()
                }
            } catch (_: Exception) {
                val fallback = YouTube.search("Top Music", filter = SearchFilter.FILTER_SONG).getOrNull()
                exploreSongs = fallback?.items?.filterIsInstance<SongItem>() ?: emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Explorar Música",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            "Éxitos recomendados y tendencias (Audio Opus de Alta Fidelidad)",
            fontSize = 13.sp,
            color = Color(0xFF9CA3AF),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(exploreSongs) { song ->
                    SongRowItem(
                        song = song,
                        onPlay = {
                            playTrack(
                                scope = scope,
                                player = player,
                                songId = song.id,
                                title = song.title,
                                artist = song.artists.joinToString(", ") { it.name },
                                durationSeconds = song.duration,
                                thumbnailUrl = song.thumbnail
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SearchScreen(player: DesktopPlayer) {
    var query by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doSearch() {
        if (query.isBlank()) return
        isSearching = true
        scope.launch(Dispatchers.IO) {
            try {
                val filter = when (filterType) {
                    0 -> SearchFilter.FILTER_SONG
                    1 -> SearchFilter.FILTER_ALBUM
                    2 -> SearchFilter.FILTER_ARTIST
                    3 -> SearchFilter.FILTER_FEATURED_PLAYLIST
                    else -> SearchFilter.FILTER_SONG
                }
                val res = YouTube.search(query, filter = filter).getOrNull()
                results = res?.items?.filterIsInstance<SongItem>() ?: emptyList()
            } catch (_: Exception) {
                results = emptyList()
            } finally {
                isSearching = false
            }
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
                placeholder = { Text("Buscar canciones, artistas, géneros...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF374151)
                )
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { doSearch() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Buscar")
            }
        }

        Row(Modifier.padding(vertical = 12.dp)) {
            val filters = listOf("Canciones", "Álbumes", "Artistas", "Playlists")
            filters.forEachIndexed { idx, label ->
                FilterChip(
                    selected = filterType == idx,
                    onClick = {
                        filterType = idx
                        if (query.isNotBlank()) doSearch()
                    },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        if (isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isEmpty()) "Ingresa un término para buscar en YouTube Music" else "No se encontraron resultados",
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(results) { song ->
                    SongRowItem(
                        song = song,
                        onPlay = {
                            playTrack(
                                scope = scope,
                                player = player,
                                songId = song.id,
                                title = song.title,
                                artist = song.artists.joinToString(", ") { it.name },
                                durationSeconds = song.duration,
                                thumbnailUrl = song.thumbnail
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(player: DesktopPlayer) {
    var tabIndex by remember { mutableStateOf(0) }
    val favorites by DesktopLibraryStore.favorites.collectAsState()
    val history by DesktopLibraryStore.history.collectAsState()
    val playlists by DesktopLibraryStore.playlists.collectAsState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Favoritos (${favorites.size})") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Historial (${history.size})") })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("Playlists (${playlists.size})") })
        }

        Spacer(Modifier.height(16.dp))

        when (tabIndex) {
            0 -> {
                if (favorites.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aún no tienes canciones favoritas guardadas.", color = Color.Gray)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(favorites) { stored ->
                            StoredSongRow(
                                stored = stored,
                                onPlay = {
                                    playTrack(
                                        scope = scope,
                                        player = player,
                                        songId = stored.id,
                                        title = stored.title,
                                        artist = stored.artist,
                                        durationSeconds = (stored.durationMs / 1000).toInt(),
                                        thumbnailUrl = stored.thumbnailUrl
                                    )
                                }
                            )
                        }
                    }
                }
            }
            1 -> {
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No hay historial de reproducción aún.", color = Color.Gray)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(history) { stored ->
                            StoredSongRow(
                                stored = stored,
                                onPlay = {
                                    playTrack(
                                        scope = scope,
                                        player = player,
                                        songId = stored.id,
                                        title = stored.title,
                                        artist = stored.artist,
                                        durationSeconds = (stored.durationMs / 1000).toInt(),
                                        thumbnailUrl = stored.thumbnailUrl
                                    )
                                }
                            )
                        }
                    }
                }
            }
            2 -> {
                var newPlName by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newPlName,
                        onValueChange = { newPlName = it },
                        placeholder = { Text("Nombre de nueva playlist...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        if (newPlName.isNotBlank()) {
                            scope.launch {
                                DesktopLibraryStore.createPlaylist(newPlName)
                                newPlName = ""
                            }
                        }
                    }) {
                        Text("Crear")
                    }
                }
                Spacer(Modifier.height(16.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(playlists) { pl ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = GlassCardBackground)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(pl.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${pl.songs.size} canciones", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpotifyScreen(player: DesktopPlayer) {
    val session by DesktopSpotifyStore.session.collectAsState()
    val likedSongs by DesktopSpotifyStore.likedSongs.collectAsState()
    val playlists by DesktopSpotifyStore.playlists.collectAsState()
    val scope = rememberCoroutineScope()

    var spDcInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var spotifyTabIndex by remember { mutableStateOf(0) }
    var selectedPlaylistTracks by remember { mutableStateOf<List<SpotifyPlaylistTrack>?>(null) }
    var selectedPlaylistName by remember { mutableStateOf("") }
    var isLoadingPlaylistTracks by remember { mutableStateOf(false) }

    LaunchedEffect(session.isLoggedIn) {
        if (session.isLoggedIn) {
            DesktopSpotifyStore.refreshLibrary()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Spotify", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    if (session.isLoggedIn) "Conectado como ${session.displayName}" else "Conecta tu cuenta para acceder a tus canciones y playlists",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
            if (session.isLoggedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { scope.launch { DesktopSpotifyStore.refreshLibrary() } },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282542))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Actualizar")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { scope.launch { DesktopSpotifyStore.logout() } },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                    ) {
                        Text("Cerrar Sesión")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!session.isLoggedIn) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(SpotifyBrandGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Radio, contentDescription = null, tint = Color.White)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Iniciar Sesión en Spotify", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                            Text("Usa tu cookie sp_dc o Access Token para sincronizar tus canciones", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Text("Opción 1: Cookie sp_dc (Recomendado)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                    Text(
                        "Inicia sesión en open.spotify.com, presiona F12 > Aplicación/Almacenamiento > Cookies > copia el valor de 'sp_dc'.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = spDcInput,
                        onValueChange = { spDcInput = it },
                        placeholder = { Text("Pega tu cookie sp_dc aquí...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (spDcInput.isNotBlank()) {
                                isLoggingIn = true
                                loginError = null
                                scope.launch {
                                    val res = DesktopSpotifyStore.loginWithSpDc(spDcInput)
                                    isLoggingIn = false
                                    if (res.isFailure) {
                                        loginError = res.exceptionOrNull()?.message ?: "Error al iniciar sesión"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyBrandGreen),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isLoggingIn && spDcInput.isNotBlank()
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Conectando...")
                        } else {
                            Text("Conectar con sp_dc")
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = Color(0x22FFFFFF))
                    Spacer(Modifier.height(16.dp))

                    Text("Opción 2: Access Token directo", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        placeholder = { Text("Pega tu token de acceso (Bearer) aquí...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (tokenInput.isNotBlank()) {
                                isLoggingIn = true
                                loginError = null
                                scope.launch {
                                    val res = DesktopSpotifyStore.loginWithToken(tokenInput)
                                    isLoggingIn = false
                                    if (res.isFailure) {
                                        loginError = res.exceptionOrNull()?.message ?: "Token inválido"
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isLoggingIn && tokenInput.isNotBlank()
                    ) {
                        Text("Conectar con Token")
                    }

                    if (loginError != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(loginError!!, color = Color(0xFFEF4444), fontSize = 13.sp)
                    }
                }
            }
        } else {
            if (selectedPlaylistTracks != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    IconButton(onClick = { selectedPlaylistTracks = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(selectedPlaylistName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }

                if (isLoadingPlaylistTracks) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(selectedPlaylistTracks.orEmpty()) { item ->
                            val track = item.track
                            if (track != null) {
                                SpotifyTrackRow(
                                    track = track,
                                    onPlay = {
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
                                )
                            }
                        }
                    }
                }
            } else {
                TabRow(
                    selectedTabIndex = spotifyTabIndex,
                    containerColor = Color.Transparent,
                    contentColor = SpotifyBrandGreen
                ) {
                    Tab(
                        selected = spotifyTabIndex == 0,
                        onClick = { spotifyTabIndex = 0 },
                        text = { Text("Canciones que me gustan (${likedSongs.size})") }
                    )
                    Tab(
                        selected = spotifyTabIndex == 1,
                        onClick = { spotifyTabIndex = 1 },
                        text = { Text("Mis Playlists (${playlists.size})") }
                    )
                }

                Spacer(Modifier.height(14.dp))

                when (spotifyTabIndex) {
                    0 -> {
                        if (likedSongs.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No se encontraron canciones guardadas o la biblioteca está cargando...", color = Color.Gray)
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(likedSongs) { saved ->
                                    SpotifyTrackRow(
                                        track = saved.track,
                                        onPlay = {
                                            scope.launch {
                                                val ytSong = DesktopSpotifyStore.matchSpotifyTrackToYouTube(saved.track)
                                                if (ytSong != null) {
                                                    playTrack(
                                                        scope = scope,
                                                        player = player,
                                                        songId = ytSong.id,
                                                        title = saved.track.name,
                                                        artist = saved.track.artists.joinToString(", ") { it.name },
                                                        durationSeconds = ytSong.duration ?: (saved.track.durationMs / 1000),
                                                        thumbnailUrl = saved.track.album?.images?.firstOrNull()?.url ?: ytSong.thumbnail
                                                    )
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        if (playlists.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No hay playlists de Spotify encontradas.", color = Color.Gray)
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(playlists) { pl ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                selectedPlaylistName = pl.name
                                                isLoadingPlaylistTracks = true
                                                selectedPlaylistTracks = emptyList()
                                                scope.launch {
                                                    selectedPlaylistTracks = DesktopSpotifyStore.fetchPlaylistTracks(pl.id)
                                                    isLoadingPlaylistTracks = false
                                                }
                                            },
                                        colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val plThumb = rememberNetworkImage(pl.images.firstOrNull()?.url)
                                            if (plThumb != null) {
                                                Image(
                                                    bitmap = plThumb,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF232730)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = SpotifyBrandGreen)
                                                }
                                            }
                                            Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(pl.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
                                                Text(
                                                    "${pl.tracks?.total ?: 0} canciones • Creada por ${pl.owner?.displayName ?: "Spotify"}",
                                                    color = Color.Gray,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                                        }
                                    }
                                }
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
fun LyricsScreen(
    lyrics: List<LyricLine>,
    plainLyrics: String?,
    isLoading: Boolean,
    player: DesktopPlayer
) {
    val position by player.positionMs.collectAsState()
    val currentLineIndex = remember(lyrics, position) {
        LyricsParser.getCurrentLineIndex(lyrics, position)
    }
    val listState = rememberLazyListState()

    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0) {
            val target = (currentLineIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Letras de la Canción", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                    Text(
                        line.text,
                        fontSize = if (isCurrent) 22.sp else 16.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) Color(0xFFA78BFA) else Color(0xFF6B7280),
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .clickable { player.seekTo(line.timestampMs) }
                    )
                }
            }
        } else if (!plainLyrics.isNullOrBlank()) {
            LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Text(
                        plainLyrics,
                        fontSize = 16.sp,
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
fun QueueScreen(player: DesktopPlayer) {
    val queue by player.queue.collectAsState()
    val currentIndex by player.currentIndex.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text("Cola de Reproducción (${queue.size})", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onNavigateToSpotify: () -> Unit) {
    val settings by DesktopSettingsStore.settings.collectAsState()
    val spotifySession by DesktopSpotifyStore.session.collectAsState()
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf(DesktopSettingsStore.getCacheSizeBytes()) }
    var cacheClearedMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Ajustes de la Aplicación", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))

        // Calidad de Audio y Streaming
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Calidad de Audio y Streaming", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Controla el formato y la resolución de audio entregado por la app", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))

                Text("Formato de Audio:", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Row(Modifier.padding(vertical = 8.dp)) {
                    listOf(
                        "HIGH" to "Alta (Opus 160 kbps)",
                        "MEDIUM" to "Media (128 kbps)",
                        "LOW" to "Baja (50-70 kbps)"
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = settings.audioQuality == code,
                            onClick = {
                                scope.launch {
                                    DesktopSettingsStore.updateSettings { it.copy(audioQuality = code) }
                                }
                            },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Cliente de Extracción YouTube:", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Row(Modifier.padding(vertical = 8.dp)) {
                    listOf(
                        "ANDROID_VR" to "Android VR (Óptimo)",
                        "IOS" to "iOS",
                        "WEB_REMIX" to "Web Remix"
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = settings.streamingClient == code,
                            onClick = {
                                scope.launch {
                                    DesktopSettingsStore.updateSettings { it.copy(streamingClient = code) }
                                }
                            },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Normalización de Volumen", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Ajusta automáticamente el volumen para evitar saltos bruscos", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.volumeNormalization,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(volumeNormalization = checked) }
                            }
                        }
                    )
                }
            }
        }

        // Cuenta de Spotify
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Cuenta de Spotify", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))

                if (spotifySession.isLoggedIn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Conectado como ${spotifySession.displayName}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(if (spotifySession.email.isNotBlank()) spotifySession.email else "Sesión activa", fontSize = 12.sp, color = SpotifyBrandGreen)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { DesktopSpotifyStore.logout() } },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Text("Desconectar")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("No has conectado tu cuenta de Spotify", fontSize = 14.sp, color = Color.Gray)
                            Text("Accede a tus playlists y canciones favoritas", fontSize = 12.sp, color = Color.Gray)
                        }
                        Button(
                            onClick = onNavigateToSpotify,
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyBrandGreen)
                        ) {
                            Text("Conectar")
                        }
                    }
                }
            }
        }

        // Almacenamiento y Caché
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Almacenamiento y Caché", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Espacio utilizado en caché de carátulas y datos: ${cacheBytes / 1024} KB", color = Color(0xFFD1D5DB), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            scope.launch {
                                DesktopSettingsStore.clearCache()
                                cacheBytes = DesktopSettingsStore.getCacheSizeBytes()
                                cacheClearedMessage = "Caché limpiada con éxito"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Limpiar Caché")
                    }
                    if (cacheClearedMessage != null) {
                        Spacer(Modifier.width(12.dp))
                        Text(cacheClearedMessage!!, color = Color(0xFF10B981), fontSize = 13.sp)
                    }
                }
            }
        }

        // Información del Sistema
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardBackground),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Acerca de YumaPlayer", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Motor de Audio: MPV IPC Nativo (Hi-Res Lossless & Opus direct stream)", color = Color(0xFFD1D5DB), fontSize = 13.sp)
                Text("Sistema Operativo: Linux Mint / Linux (x86_64)", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                Text("Distribución: Paquete nativo .deb y portable .AppImage", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                Text("Líricas Sincronizadas: LRCLIB API", color = Color(0xFF9CA3AF), fontSize = 13.sp)
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
fun PlayerBar(player: DesktopPlayer, onOpenLyrics: () -> Unit) {
    val state by player.state.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    val volume by player.volume.collectAsState()
    val queue by player.queue.collectAsState()
    val index by player.currentIndex.collectAsState()
    val repeatMode by player.repeatModeFlow.collectAsState()
    val shuffleEnabled by player.shuffleEnabledFlow.collectAsState()
    val favorites by DesktopLibraryStore.favorites.collectAsState()

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
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = Color(0xFF2D224C),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    "OPUS",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFA78BFA),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
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

                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Slider(
                        value = volume / 100f,
                        onValueChange = { player.setVolume((it * 100).toInt()) },
                        modifier = Modifier.width(100.dp),
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
