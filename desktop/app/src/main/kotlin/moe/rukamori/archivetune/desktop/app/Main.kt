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

private var activePlayTrackJob: kotlinx.coroutines.Job? = null
private var activeRadioJob: kotlinx.coroutines.Job? = null

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
    // Feedback visual inmediato (0ms, estilo Android mini-player): muestra el tema
    // en estado PLAYING optimista mientras se resuelve el stream en background.
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

    activeRadioJob?.cancel()
    activePlayTrackJob?.cancel()

    activePlayTrackJob = scope.launch {
        // Historial en background: no bloquea la resolucion del stream.
        launch(Dispatchers.IO) {
            runCatching {
                DesktopLibraryStore.recordHistory(
                    StoredSong(
                        id = songId,
                        title = title,
                        artist = artist,
                        durationMs = initialDurationMs,
                        thumbnailUrl = thumbnailUrl
                    )
                )
            }
        }
        val s = DesktopSettingsStore.settings.value
        // Tope duro de 15s (antes 10s): FLAC tiene 2.2s y YouTube ~3.8s, asi que
        // nunca deberia acercarse; el margen evita cancelar el fallback a medias.
        val resolveStartedAt = System.currentTimeMillis()
        val stream = withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                DesktopStreamResolver.resolveStreamUrl(
                    videoId = songId,
                    title = title,
                    artist = artist,
                    durationMs = initialDurationMs,
                    playbackSource = s.playbackSource,
                    flacQuality = s.flacQuality,
                    preferredQuality = s.audioQuality,
                    preferredClientName = s.streamingClient,
                    flacFallbackToYoutube = s.flacFallbackToYoutube
                )
            }
        }
        DesktopDiag.log(
            "play", System.currentTimeMillis() - resolveStartedAt,
            if (stream != null) "ok ${stream.clientName}" else "sin stream"
        )
        if (stream == null) {
            val fuente = if (s.playbackSource == "FLAC") "FLAC ni YouTube" else "YouTube Music"
            player.markError("No disponible en $fuente: \"$title\". Revisa Ajustes o la conexion.")
            return@launch
        }
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
        // y pre-resuelve los 3 siguientes para que "Siguiente" sea instantaneo.
        activeRadioJob = scope.launch(Dispatchers.IO) {
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
                        // Pre-resuelve los 3 siguientes en paralelo (cache hit al pulsar Next)
                        val st = DesktopSettingsStore.settings.value
                        recommended.take(3).forEach { item ->
                            launch {
                                runCatching {
                                    DesktopStreamResolver.prefetch(
                                        videoId = item.id, title = item.title, artist = item.artist,
                                        durationMs = item.durationMs, playbackSource = st.playbackSource,
                                        flacQuality = st.flacQuality, preferredQuality = st.audioQuality,
                                        preferredClientName = st.streamingClient,
                                        flacFallbackToYoutube = st.flacFallbackToYoutube
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private var activeSpotifyMatchJob: kotlinx.coroutines.Job? = null

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
    activeSpotifyMatchJob?.cancel()
    activeSpotifyMatchJob = scope.launch {
        // Match Spotify->YT con timeout corto: si tarda, no dejamos al usuario esperando.
        val matchStartedAt = System.currentTimeMillis()
        val ytSong = withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(6000L) {
                DesktopSpotifyStore.matchSpotifyTrackToYouTube(track)
            }
        }
        DesktopDiag.log("match", System.currentTimeMillis() - matchStartedAt, "spotify-track")
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
        } else {
            player.markError("No se encontro \"${track.name}\" en YouTube Music. Revisa Ajustes.")
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
    // Pre-resuelve los siguientes 3 temas de la cola para avance instantaneo.
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        val s = DesktopSettingsStore.settings.value
        items.drop(startIndex + 1).take(3).forEach { item ->
            launch {
                runCatching {
                    // Solo si el id parece videoId de YT (11 chars); si es Spotify id,
                    // el itemResolver lo matcheara al reproducir.
                    if (item.uri.isBlank() && item.id.length == 11) {
                        DesktopStreamResolver.prefetch(
                            videoId = item.id, title = item.title, artist = item.artist,
                            durationMs = item.durationMs, playbackSource = s.playbackSource,
                            flacQuality = s.flacQuality, preferredQuality = s.audioQuality,
                            preferredClientName = s.streamingClient,
                            flacFallbackToYoutube = s.flacFallbackToYoutube
                        )
                    }
                }
            }
        }
    }
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

    // Warm up de YouTube + motor de descifrado, TODO en segundo plano.
    // Antes el descifrado se descargaba (player JS) en medio de la primera cancion y se
    // cancelaba a los 1.5s, asi que el plan nunca se guardaba y se repetia en cada tema.
    CoroutineScope(Dispatchers.IO).launch {
        runCatching { YouTube.visitorData() }
        DesktopStreamResolver.warmUpCipher()
    }

    val player = DesktopPlayer()
    player.itemResolver = resolver@{ mediaItem ->
        val s = DesktopSettingsStore.settings.value
        var actualVideoId = mediaItem.id
        if (actualVideoId.startsWith("spt:") || actualVideoId.length == 22) {
            val matchStartedAt = System.currentTimeMillis()
            val matched = DesktopSpotifyStore.matchTitleArtistToYouTube(mediaItem.title, mediaItem.artist)
            DesktopDiag.log("match", System.currentTimeMillis() - matchStartedAt, "spotify->yt")
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
            preferredClientName = s.streamingClient,
            flacFallbackToYoutube = s.flacFallbackToYoutube
        ) ?: return@resolver null
        val dur = if (stream.durationMs > 0) stream.durationMs else mediaItem.durationMs
        mediaItem.copy(
            id = actualVideoId,
            uri = stream.url,
            durationMs = dur,
            audioFormat = stream.formatLabel
        )
    }
    // Si mpv reporta un fallo de carga (p.ej. URL googlevideo caducada => 403),
    // invalidamos la cache de ese tema para que el siguiente intento resuelva fresco.
    player.onStreamError = { item ->
        DesktopStreamResolver.invalidate(item.id)
    }
    // Metrica real de arranque: ms desde loadfile hasta el primer sample de audio.
    player.onPlaybackDiag = { ms ->
        DesktopDiag.log("primer-audio", ms, "loadfile -> primer sample en mpv")
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
    var isNowPlayingSidePanelOpen by remember { mutableStateOf(false) }
    var isFullPlayerOpen by remember { mutableStateOf(false) }

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
            Box(Modifier.fillMaxSize()) {
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

                    AnimatedVisibility(
                        visible = isNowPlayingSidePanelOpen,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        NowPlayingSidePanel(
                            player = player,
                            lyrics = currentLyrics,
                            plainLyrics = rawPlainLyrics,
                            isLoadingLyrics = isLyricsLoading,
                            onClose = { isNowPlayingSidePanelOpen = false }
                        )
                    }
                }

                PlayerBar(
                    player = player,
                    onToggleSidePanel = { isNowPlayingSidePanelOpen = !isNowPlayingSidePanelOpen },
                    onExpandFullPlayer = { isFullPlayerOpen = true },
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
            // Overlay estilo Android FullPlayer (cubre toda la ventana, como el bottom-sheet expandido)
            if (isFullPlayerOpen) {
                AndroidFullPlayerOverlay(
                    player = player,
                    onCollapse = { isFullPlayerOpen = false },
                    onOpenLyrics = {
                        isFullPlayerOpen = false
                        if (selectedSection != 4) {
                            previousSection = selectedSection
                            selectedSection = 4
                        }
                    },
                    onOpenQueue = {
                        isFullPlayerOpen = false
                        if (selectedSection != 5) {
                            previousSection = selectedSection
                            selectedSection = 5
                        }
                    }
                )
            }
            }
        }
    }
}

@Composable
fun Sidebar(selectedSection: Int, onSelectSection: (Int) -> Unit) {
    // Estilo Android NavigationRail (tablet): estrecho, iconos con pill indicador,
    // mismos 4 destinos que la app movil (Inicio/Buscar/Biblioteca/Ajustes).
    // No se elimina nada: solo se reordena visualmente para parecerse a Android.
    Surface(
        modifier = Modifier.width(88.dp).fillMaxHeight(),
        color = Color(0xFF141720)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AccentGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = "YumaPlayer", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Yuma",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "Mint",
                    fontSize = 9.sp,
                    color = Color(0xFF9CA3AF)
                )

                Spacer(Modifier.height(20.dp))

                // Identical to mobile tabs: Inicio, Buscar, Biblioteca, Ajustes
                val items = listOf(
                    Triple(0, "Inicio", Icons.Default.Home),
                    Triple(1, "Buscar", Icons.Default.Search),
                    Triple(2, "Biblio", Icons.Default.LibraryMusic),
                    Triple(3, "Ajustes", Icons.Default.Settings),
                )

                items.forEach { (index, title, icon) ->
                    val isSelected = selectedSection == index || (selectedSection >= 4 && index == 0)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectSection(index) }
                            .padding(vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .height(32.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Color(0xFF282542) else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = title,
                                tint = if (isSelected) Color(0xFFA78BFA) else Color(0xFF9CA3AF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            title,
                            color = if (isSelected) Color.White else Color(0xFF9CA3AF),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Text(
                "v1.0.0",
                fontSize = 9.sp,
                color = Color(0xFF6B7280)
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
            Column {
                Text("Karaoke y Letras", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Sincronizado en vivo • toca una línea para saltar", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
        }
        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (lyrics.isNotEmpty()) {
            // Vista karaoke: linea activa grande y resaltada, resto atenuado,
            // centrado y con auto-scroll (estilo Android).
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 40.dp)
            ) {
                itemsIndexed(lyrics) { idx, line ->
                    val isCurrent = idx == currentLineIndex
                    val itemFontSize = if (isCurrent) (settings.lyricsTextSize * 1.25f).sp else (settings.lyricsTextSize * 0.78f).sp
                    val itemColor = when {
                        isCurrent -> Color(0xFFA78BFA)
                        settings.lyricsLineBlur -> Color.White.copy(alpha = 0.35f)
                        else -> Color(0xFF9CA3AF)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = (8 * settings.lyricsLineSpacing).dp)
                            .then(
                                if (settings.lyricsClick) {
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { player.seekTo(line.timestampMs) }
                                } else Modifier
                            )
                            .padding(horizontal = 20.dp, vertical = 2.dp)
                    ) {
                        Text(
                            line.text,
                            fontSize = itemFontSize,
                            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Normal,
                            color = itemColor,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (isCurrent) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .width(46.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFA78BFA))
                            )
                        }
                    }
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
    // Prefetch especulativo estilo Android: al volverse visible, pre-resolvemos el
    // match YT en background para que el click posterior sea mas rapido.
    LaunchedEffect(track.id) {
        runCatching {
            // Solo calentamos el match (search cacheado); el stream se resuelve al click.
            // No bloquea la UI.
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                DesktopSpotifyStore.matchSpotifyTrackToYouTube(track)
            }
        }
    }

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
    // Prefetch del stream al mostrarse en lista (hover especulativo): click -> 0ms si cache hit.
    LaunchedEffect(song.id) {
        val s = DesktopSettingsStore.settings.value
        DesktopStreamResolver.prefetch(
            videoId = song.id, title = song.title,
            artist = song.artists.joinToString(", ") { it.name },
            durationMs = (song.duration ?: 0) * 1000L,
            playbackSource = s.playbackSource, flacQuality = s.flacQuality,
            preferredQuality = s.audioQuality, preferredClientName = s.streamingClient,
            flacFallbackToYoutube = s.flacFallbackToYoutube
        )
    }

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
    LaunchedEffect(stored.id) {
        // Solo prefetch si parece videoId YT; si no, el match se hara al click.
        if (stored.id.length == 11) {
            val s = DesktopSettingsStore.settings.value
            DesktopStreamResolver.prefetch(
                videoId = stored.id, title = stored.title, artist = stored.artist,
                durationMs = stored.durationMs,
                playbackSource = s.playbackSource, flacQuality = s.flacQuality,
                preferredQuality = s.audioQuality, preferredClientName = s.streamingClient,
                flacFallbackToYoutube = s.flacFallbackToYoutube
            )
        }
    }

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
    onToggleSidePanel: () -> Unit = {},
    onExpandFullPlayer: () -> Unit = {},
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit
) {
    // Mini-player estilo Android: tarjeta flotante con caratula, titulo, botones
    // circulares grandes (Letras/Karaoke, Favorito, Anterior, Play, Siguiente, Cola)
    // y barra de progreso. Todos los controles se conservan y se agrandan.
    val state by player.state.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    val queue by player.queue.collectAsState()
    val index by player.currentIndex.collectAsState()
    val favorites by DesktopLibraryStore.favorites.collectAsState()
    val currentFormat by player.currentFormat.collectAsState()
    val errorMessage by player.errorMessage.collectAsState()

    val current = queue.getOrNull(index)
    val isFav = current != null && favorites.any { it.id == current.id }
    val scope = rememberCoroutineScope()
    val thumbBitmap = rememberNetworkImage(current?.thumbnailUrl)
    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    Box(Modifier.fillMaxWidth().background(Color.Transparent).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1E29)),
            border = BorderStroke(1.dp, Color(0x22FFFFFF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Caratula + metadatos: click expande el FullPlayer (como Android)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { if (current != null) onExpandFullPlayer() }
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (thumbBitmap != null) {
                            Image(
                                bitmap = thumbBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF282B35)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                            }
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    current?.title ?: if (state == DesktopPlayerState.IDLE) "Listo para reproducir" else "Cargando...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                val badge = if (state == DesktopPlayerState.ERROR) "ERROR" else currentFormat ?: current?.audioFormat
                                if (!badge.isNullOrBlank() && current != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = if (state == DesktopPlayerState.ERROR) Color(0xFF3B1D1D) else Color(0xFF2D224C),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            badge, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                            color = if (state == DesktopPlayerState.ERROR) Color(0xFFEF4444) else Color(0xFFA78BFA),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                if (state == DesktopPlayerState.ERROR && errorMessage != null) errorMessage!!
                                else current?.artist ?: "YumaPlayer • Toca para expandir",
                                color = if (state == DesktopPlayerState.ERROR) Color(0xFFEF4444) else Color(0xFF9CA3AF),
                                fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // Boton Letras/Karaoke (grande, estilo Android)
                    AndroidCircleButton(
                        icon = Icons.Default.Mic,
                        contentDescription = "Letras y Karaoke",
                        size = 46.dp,
                        iconSize = 24.dp,
                        onClick = onOpenLyrics
                    )

                    // Boton Favorito
                    if (current != null) {
                        Spacer(Modifier.width(6.dp))
                        AndroidCircleButton(
                            icon = if (isFav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Me gusta",
                            size = 46.dp,
                            iconSize = 22.dp,
                            tint = if (isFav) Color(0xFFEF4444) else Color.White,
                            onClick = {
                                scope.launch {
                                    DesktopLibraryStore.toggleFavorite(
                                        StoredSong(
                                            id = current.id, title = current.title, artist = current.artist,
                                            durationMs = current.durationMs, thumbnailUrl = current.thumbnailUrl
                                        )
                                    )
                                }
                            }
                        )
                    }

                    // Boton Anterior
                    Spacer(Modifier.width(6.dp))
                    AndroidCircleButton(
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = "Anterior",
                        size = 48.dp,
                        iconSize = 28.dp,
                        onClick = { player.prev() }
                    )

                    // Play/Pausa: circulo principal grande (estilo Android)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { player.playPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state == DesktopPlayerState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pausa", tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    // Boton Siguiente
                    Spacer(Modifier.width(8.dp))
                    AndroidCircleButton(
                        icon = Icons.Default.SkipNext,
                        contentDescription = "Siguiente",
                        size = 48.dp,
                        iconSize = 28.dp,
                        onClick = { player.next() }
                    )

                    // Boton Cola
                    Spacer(Modifier.width(6.dp))
                    AndroidCircleButton(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Cola de reproducción",
                        size = 46.dp,
                        iconSize = 24.dp,
                        onClick = onOpenQueue
                    )

                    // Panel lateral (letras + info en vivo)
                    Spacer(Modifier.width(6.dp))
                    AndroidCircleButton(
                        icon = Icons.Default.ExpandLess,
                        contentDescription = "Panel lateral",
                        size = 40.dp,
                        iconSize = 20.dp,
                        tint = Color.Gray,
                        onClick = onToggleSidePanel
                    )
                }
                // Progreso estilo Android (mini-player)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xFF374151),
                )
            }
        }
    }
}

/**
 * Boton circular estilo Android (fondo translucido + borde sutil), reutilizable
 * en mini-player y FullPlayer. Touch target amplio para uso comodo.
 */
@Composable
fun AndroidCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun AndroidFullPlayerOverlay(
    player: DesktopPlayer,
    onCollapse: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    // FullPlayer estilo Android: caratula grande centrada, titulo/artista, slider con
    // tiempos, fila principal (shuffle/prev/play/next/repeat), fila secundaria
    // (rewind/letras/cola/ff) y volumen. Reune TODOS los controles del PlayerBar
    // anterior sin eliminar ninguno.
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
    val errorMessage by player.errorMessage.collectAsState()

    val current = queue.getOrNull(index)
    val isFav = current != null && favorites.any { it.id == current.id }
    val scope = rememberCoroutineScope()
    val thumbBitmap = rememberNetworkImage(current?.thumbnailUrl)

    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    var lastNonZeroVolume by remember { mutableStateOf(80) }

    Box(
        Modifier.fillMaxSize().background(Color(0xE60F1117))
            .clickable { onCollapse() }
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 24.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Toolbar superior estilo Android (collapse + badge + cola)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Default.ExpandMore, contentDescription = "Minimizar", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("REPRODUCIENDO AHORA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9CA3AF))
                        val badge = currentFormat ?: current?.audioFormat
                        if (!badge.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Surface(color = Color(0xFF2D224C), shape = RoundedCornerShape(4.dp)) {
                                Text(badge, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA78BFA),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Cola", tint = Color.Gray)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Caratula grande 1:1 estilo Android FullPlayer
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF232634)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbBitmap != null) {
                        Image(bitmap = thumbBitmap, contentDescription = current?.title,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Default.MusicNote, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(88.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                // Titulo + artista + fav (como Android)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(current?.title ?: "Sin reproducción", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current?.artist ?: "YumaPlayer", fontSize = 14.sp,
                            color = if (state == DesktopPlayerState.ERROR) Color(0xFFEF4444) else Color(0xFF9CA3AF),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (current != null) {
                        IconButton(onClick = {
                            scope.launch {
                                DesktopLibraryStore.toggleFavorite(
                                    StoredSong(id = current.id, title = current.title, artist = current.artist,
                                        durationMs = current.durationMs, thumbnailUrl = current.thumbnailUrl)
                                )
                            }
                        }) {
                            Icon(if (isFav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Me gusta",
                                tint = if (isFav) Color(0xFFEF4444) else Color.Gray, modifier = Modifier.size(26.dp))
                        }
                    }
                }
                if (state == DesktopPlayerState.ERROR && errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1D1D)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            errorMessage!!,
                            fontSize = 12.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Slider con tiempos
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    val displayPos = if (isDraggingSlider && duration > 0) (dragFrac * duration).toLong() else position
                    Text(formatTime(displayPos), fontSize = 12.sp, color = Color.Gray, modifier = Modifier.width(44.dp))
                    val sliderValue = when {
                        isDraggingSlider -> dragFrac
                        duration > 0 -> (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = { frac -> isDraggingSlider = true; dragFrac = frac },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            if (duration > 0) player.seekTo((dragFrac * duration).toLong())
                        },
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color(0xFF374151)
                        )
                    )
                    Text(formatTime(duration), fontSize = 12.sp, color = Color.Gray, modifier = Modifier.width(44.dp))
                }
                // Controles principales estilo Android: circulos grandes con fondo sutil
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    AndroidCircleButton(
                        icon = Icons.Default.Shuffle, contentDescription = "Aleatorio",
                        size = 52.dp, iconSize = 26.dp,
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else Color.White,
                        onClick = { player.toggleShuffle() }
                    )
                    Spacer(Modifier.width(10.dp))
                    AndroidCircleButton(
                        icon = Icons.Default.SkipPrevious, contentDescription = "Anterior",
                        size = 56.dp, iconSize = 34.dp,
                        onClick = { player.prev() }
                    )
                    Spacer(Modifier.width(14.dp))
                    // Play/Pausa: circulo principal (72dp, como Android FullPlayer)
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { player.playPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state == DesktopPlayerState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pausa", tint = Color.White, modifier = Modifier.size(42.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    AndroidCircleButton(
                        icon = Icons.Default.SkipNext, contentDescription = "Siguiente",
                        size = 56.dp, iconSize = 34.dp,
                        onClick = { player.next() }
                    )
                    Spacer(Modifier.width(10.dp))
                    AndroidCircleButton(
                        icon = when (repeatMode) { 2 -> Icons.Default.RepeatOne; 1 -> Icons.Default.Repeat; else -> Icons.Default.Repeat },
                        contentDescription = "Repetir",
                        size = 52.dp, iconSize = 26.dp,
                        tint = if (repeatMode > 0) MaterialTheme.colorScheme.primary else Color.White,
                        onClick = { player.cycleRepeatMode() }
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Fila secundaria: rewind, KARAOKE (letras), ff + volumen/mute (todo preservado)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AndroidCircleButton(
                        icon = Icons.Default.FastRewind, contentDescription = "-${settings.seekDurationSeconds}s",
                        size = 48.dp, iconSize = 26.dp, tint = Color.LightGray,
                        onClick = { player.seekRelative(-settings.seekDurationSeconds.toDouble()) }
                    )
                    // Boton de Karaoke/Letras destacado (con acento)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF2D224C))
                            .border(1.dp, Color(0xFFA78BFA).copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                            .clickable { onOpenLyrics() }
                            .padding(horizontal = 18.dp, vertical = 11.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = "Karaoke y Letras", tint = Color(0xFFA78BFA), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Karaoke", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFA78BFA))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    AndroidCircleButton(
                        icon = Icons.Default.FastForward, contentDescription = "+${settings.seekDurationSeconds}s",
                        size = 48.dp, iconSize = 26.dp, tint = Color.LightGray,
                        onClick = { player.seekRelative(settings.seekDurationSeconds.toDouble()) }
                    )
                    AndroidCircleButton(
                        icon = when {
                            volume == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                            volume <= 50 -> Icons.AutoMirrored.Filled.VolumeDown
                            else -> Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = "Volumen",
                        size = 48.dp, iconSize = 24.dp,
                        tint = if (volume == 0) Color.Red else Color.White,
                        onClick = {
                            if (volume > 0) { lastNonZeroVolume = volume; player.setVolume(0) }
                            else player.setVolume(if (lastNonZeroVolume > 0) lastNonZeroVolume else 80)
                        }
                    )
                    Slider(
                        value = volume / 100f,
                        onValueChange = { player.setVolume((it * 100).toInt()) },
                        modifier = Modifier.width(110.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White, activeTrackColor = Color(0xFFA78BFA),
                            inactiveTrackColor = Color(0xFF374151))
                    )
                }
            }
        }
    }
}

@Composable
fun NowPlayingSidePanel(
    player: DesktopPlayer,
    lyrics: List<LyricLine>,
    plainLyrics: String?,
    isLoadingLyrics: Boolean,
    onClose: () -> Unit,
) {
    val queue by player.queue.collectAsState()
    val index by player.currentIndex.collectAsState()
    val current = queue.getOrNull(index)
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    val currentFormat by player.currentFormat.collectAsState()

    val thumbBitmap = rememberNetworkImage(current?.thumbnailUrl)
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val currentLineIndex = remember(lyrics, position) {
        LyricsParser.getCurrentLineIndex(lyrics, position)
    }

    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0 && !listState.isScrollInProgress) {
            coroutineScope.launch {
                listState.animateScrollToItem((currentLineIndex - 2).coerceAtLeast(0))
            }
        }
    }

    Surface(
        modifier = Modifier
            .width(340.dp)
            .fillMaxHeight(),
        color = Color(0xFF13151D),
        border = BorderStroke(1.dp, Color(0x22FFFFFF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header: Title & Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Reproduciendo ahora",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.LightGray)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Big Album Cover Art (280x280 aspect ratio 1:1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF232634)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap,
                        contentDescription = current?.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Track metadata & Audio Quality badge
            Text(
                current?.title ?: "Sin reproducción",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    current?.artist ?: "",
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                val badge = currentFormat ?: current?.audioFormat
                if (!badge.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFF2D224C),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA78BFA),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color(0x22FFFFFF))
            Spacer(Modifier.height(10.dp))

            // Synced Lyrics Subheader
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFA78BFA), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Letras sincronizadas", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            Spacer(Modifier.height(8.dp))

            // Lyrics Scrollable Content
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (isLoadingLyrics) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                } else if (lyrics.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        itemsIndexed(lyrics) { idx, line ->
                            val isCurrent = idx == currentLineIndex
                            val itemFontSize = if (isCurrent) 16.sp else 13.sp
                            val itemColor = when {
                                isCurrent -> Color.White
                                else -> Color.White.copy(alpha = 0.35f)
                            }
                            val itemWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal

                            Text(
                                line.text,
                                fontSize = itemFontSize,
                                fontWeight = itemWeight,
                                color = itemColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { player.seekTo(line.timestampMs) }
                                    .padding(vertical = 5.dp, horizontal = 4.dp)
                            )
                        }
                    }
                } else if (!plainLyrics.isNullOrBlank()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        item {
                            Text(
                                plainLyrics,
                                fontSize = 13.sp,
                                color = Color(0xFFD1D5DB),
                                lineHeight = 19.sp
                            )
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Letras no disponibles",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

