package moe.rukamori.archivetune.desktop.app

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.runtime.DesktopPlayer
import java.io.File
import kotlin.math.roundToInt

enum class SettingsSubpage {
    HOME,
    ACCOUNTS,
    APPEARANCE,
    LYRICS,
    PLAYBACK,
    CONTENT,
    STORAGE,
    BACKUP,
    ABOUT
}

@Composable
fun DesktopSettingsRoot(
    player: DesktopPlayer?,
    onOpenSpotifyBrowserLogin: () -> Unit = {},
    spotifyStatusText: String = "No conectado",
    onSyncSpotify: () -> Unit = {},
    onDisconnectSpotify: () -> Unit = {},
) {
    var currentPage by remember { mutableStateOf(SettingsSubpage.HOME) }
    val settings by DesktopSettingsStore.settings.collectAsState()
    val scope = rememberCoroutineScope()

    AnimatedContent(
        targetState = currentPage,
        transitionSpec = {
            if (targetState == SettingsSubpage.HOME) {
                (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            } else {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
            }
        },
        label = "settingsSubpageTransition"
    ) { page ->
        when (page) {
            SettingsSubpage.HOME -> SettingsHomeScreen(
                onNavigate = { currentPage = it }
            )
            SettingsSubpage.ACCOUNTS -> AccountsSettingsScreen(
                onBack = { currentPage = SettingsSubpage.HOME },
                onOpenSpotifyBrowserLogin = onOpenSpotifyBrowserLogin,
                spotifyStatusText = spotifyStatusText,
                onSyncSpotify = onSyncSpotify,
                onDisconnectSpotify = onDisconnectSpotify,
                settings = settings
            )
            SettingsSubpage.APPEARANCE -> AppearanceSettingsScreen(
                onBack = { currentPage = SettingsSubpage.HOME },
                settings = settings
            )
            SettingsSubpage.LYRICS -> LyricsSettingsScreen(
                onBack = { currentPage = SettingsSubpage.HOME },
                settings = settings
            )
            SettingsSubpage.PLAYBACK -> PlaybackSettingsScreen(
                onBack = { currentPage = SettingsSubpage.HOME },
                settings = settings,
                player = player
            )
            SettingsSubpage.CONTENT -> ContentSettingsScreen(
                onBack = { currentPage = SettingsSubpage.HOME },
                settings = settings
            )
            SettingsSubpage.STORAGE -> StorageSettingsScreen(
                onBack = { currentPage = SettingsSubpage.HOME }
            )
            SettingsSubpage.BACKUP -> BackupSettingsScreen(
                onBack = { currentPage = SettingsSubpage.HOME }
            )
            SettingsSubpage.ABOUT -> AboutSettingsScreen(
                onBack = { currentPage = SettingsSubpage.HOME }
            )
        }
    }
}

@Composable
fun SettingsHomeScreen(
    onNavigate: (SettingsSubpage) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Ajustes", fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Color.White)
        Text("Configura la apariencia, reproducción, fuentes de letras y servicios", color = Color.Gray, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        // Grupo 1: Cuentas e Integraciones
        SettingsGroupHeader(title = "Cuentas y Servicios")
        SettingsNavigationCard(
            title = "Cuentas e Integraciones",
            subtitle = "Spotify, Discord Rich Presence, Last.fm",
            icon = Icons.Default.Person,
            iconTint = Color(0xFF1DB954),
            onClick = { onNavigate(SettingsSubpage.ACCOUNTS) }
        )

        Spacer(Modifier.height(16.dp))

        // Grupo 2: Interfaz
        SettingsGroupHeader(title = "Interfaz y Letras")
        SettingsNavigationCard(
            title = "Apariencia",
            subtitle = "Tema oscuro, AMOLED negro puro, tipografía y estilo",
            icon = Icons.Default.DarkMode,
            iconTint = Color(0xFFBB86FC),
            onClick = { onNavigate(SettingsSubpage.APPEARANCE) }
        )
        Spacer(Modifier.height(8.dp))
        SettingsNavigationCard(
            title = "Letras",
            subtitle = "Tamaño de fuente, proveedores (LRCLIB, Kugou, SimpMusic) y sincronización",
            icon = Icons.Default.MusicNote,
            iconTint = Color(0xFF03DAC5),
            onClick = { onNavigate(SettingsSubpage.LYRICS) }
        )

        Spacer(Modifier.height(16.dp))

        // Grupo 3: Reproducción y Media
        SettingsGroupHeader(title = "Reproducción y Contenido")
        SettingsNavigationCard(
            title = "Reproducción y Audio",
            subtitle = "Fuente YouTube Music/FLAC, calidades, ReplayGain, silencios",
            icon = Icons.Default.PlayArrow,
            iconTint = Color(0xFFFF5252),
            onClick = { onNavigate(SettingsSubpage.PLAYBACK) }
        )
        Spacer(Modifier.height(8.dp))
        SettingsNavigationCard(
            title = "Contenido y Región",
            subtitle = "Idioma y país de recomendaciones, filtro de explícito",
            icon = Icons.Default.Language,
            iconTint = Color(0xFF448AFF),
            onClick = { onNavigate(SettingsSubpage.CONTENT) }
        )

        Spacer(Modifier.height(16.dp))

        // Grupo 4: Sistema y Datos
        SettingsGroupHeader(title = "Almacenamiento y Sistema")
        SettingsNavigationCard(
            title = "Almacenamiento y Caché",
            subtitle = "Espacio ocupado en disco y limpieza de caché de canciones e imágenes",
            icon = Icons.Default.Storage,
            iconTint = Color(0xFFFFAB40),
            onClick = { onNavigate(SettingsSubpage.STORAGE) }
        )
        Spacer(Modifier.height(8.dp))
        SettingsNavigationCard(
            title = "Copia de Seguridad y Restauración",
            subtitle = "Exportar e importar ajustes y listas en formato JSON",
            icon = Icons.Default.CloudDownload,
            iconTint = Color(0xFF69F0AE),
            onClick = { onNavigate(SettingsSubpage.BACKUP) }
        )
        Spacer(Modifier.height(8.dp))
        SettingsNavigationCard(
            title = "Acerca de",
            subtitle = "YumaPlayer Desktop v1.0.0, licencia y créditos",
            icon = Icons.Default.Info,
            iconTint = Color(0xFF90CAF9),
            onClick = { onNavigate(SettingsSubpage.ABOUT) }
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFFBB86FC),
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsNavigationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}

@Composable
private fun SettingsTopBar(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White)
    }
}

// -------------------------------------------------------------
// SUB-SCREEN 1: ACCOUNTS & INTEGRATIONS
// -------------------------------------------------------------
@Composable
fun AccountsSettingsScreen(
    onBack: () -> Unit,
    onOpenSpotifyBrowserLogin: () -> Unit,
    spotifyStatusText: String,
    onSyncSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    settings: DesktopAppSettings
) {
    val scope = rememberCoroutineScope()
    var lastFmUser by remember { mutableStateOf(settings.lastFmUsername) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsTopBar("Cuentas e Integraciones", onBack)

        // Spotify Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF1DB954))
                    Spacer(Modifier.width(10.dp))
                    Text("Spotify Connect", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Estado: $spotifyStatusText",
                    color = if (spotifyStatusText.contains("Conectado")) Color(0xFF1DB954) else Color.Gray,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onOpenSpotifyBrowserLogin,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                    ) {
                        Text("Iniciar sesión en navegador", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    if (spotifyStatusText.contains("Conectado")) {
                        OutlinedButton(onClick = onSyncSpotify) {
                            Text("Sincronizar listas", color = Color.White)
                        }
                        TextButton(onClick = onDisconnectSpotify) {
                            Text("Cerrar sesión", color = Color(0xFFFF5252))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Discord RPC
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Discord Rich Presence (RPC)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                        Text("Muestra la canción actual, artista y tiempo restante en tu estado de Discord", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = settings.discordRpcEnabled,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(discordRpcEnabled = checked) }
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Last.fm
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Last.fm Scrobbling", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Registra tu historial de reproducciones directamente en tu perfil de Last.fm", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Habilitar Scrobbling", fontSize = 14.sp, color = Color.White)
                    Switch(
                        checked = settings.lastFmScrobblingEnabled,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(lastFmScrobblingEnabled = checked) }
                            }
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = lastFmUser,
                    onValueChange = { lastFmUser = it },
                    label = { Text("Usuario Last.fm") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            DesktopSettingsStore.updateSettings { it.copy(lastFmUsername = lastFmUser) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC))
                ) {
                    Text("Guardar usuario", color = Color.Black)
                }
            }
        }
    }
}

// -------------------------------------------------------------
// SUB-SCREEN 2: APPEARANCE
// -------------------------------------------------------------
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    settings: DesktopAppSettings
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsTopBar("Apariencia e Interfaz", onBack)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Tema y Color", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(14.dp))

                // AMOLED Pure Black
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Negro Puro (AMOLED)", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Fondos en negro absoluto (#000000) ideal para pantallas OLED", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.pureBlack,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(pureBlack = checked) }
                            }
                        }
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(14.dp))

                // Modo Oscuro
                Text("Modo Oscuro", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ON" to "Activado", "OFF" to "Desactivado", "AUTO" to "Seguir Sistema").forEach { (code, label) ->
                        FilterChip(
                            selected = settings.darkMode == code,
                            onClick = {
                                scope.launch { DesktopSettingsStore.updateSettings { it.copy(darkMode = code) } }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(14.dp))

                // Tipografía
                Text("Fuente de la Aplicación", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("DEFAULT" to "Predeterminada", "SYSTEM" to "Sistema").forEach { (code, label) ->
                        FilterChip(
                            selected = settings.appFont == code,
                            onClick = {
                                scope.launch { DesktopSettingsStore.updateSettings { it.copy(appFont = code) } }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(14.dp))

                // Pestaña inicial
                Text("Pestaña Predeterminada al Abrir", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Inicio", 1 to "Búsqueda", 2 to "Biblioteca").forEach { (idx, label) ->
                        FilterChip(
                            selected = settings.defaultTab == idx,
                            onClick = {
                                scope.launch { DesktopSettingsStore.updateSettings { it.copy(defaultTab = idx) } }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(14.dp))

                // Desactivar animaciones
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Desactivar Animaciones", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Reduce uso de CPU deshabilitando transiciones visuales", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.disableAnimations,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(disableAnimations = checked) }
                            }
                        }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// SUB-SCREEN 3: LYRICS
// -------------------------------------------------------------
@Composable
fun LyricsSettingsScreen(
    onBack: () -> Unit,
    settings: DesktopAppSettings
) {
    val scope = rememberCoroutineScope()
    var lyricsClearedMsg by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsTopBar("Letras y Visualización", onBack)

        // Visualización y tipografía de letras
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Visualización y Tipografía de Letras", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(14.dp))

                // Tamaño de letra slider
                Text("Tamaño del Texto de las Letras: ${settings.lyricsTextSize.roundToInt()} sp", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                Slider(
                    value = settings.lyricsTextSize,
                    onValueChange = { newVal ->
                        scope.launch {
                            DesktopSettingsStore.updateSettings { it.copy(lyricsTextSize = newVal) }
                        }
                    },
                    valueRange = 16f..36f,
                    steps = 19
                )

                Spacer(Modifier.height(10.dp))

                // Espaciado de líneas slider
                Text("Espaciado de Líneas: ${String.format("%.1f", settings.lyricsLineSpacing)}x", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                Slider(
                    value = settings.lyricsLineSpacing,
                    onValueChange = { newVal ->
                        scope.launch {
                            DesktopSettingsStore.updateSettings { it.copy(lyricsLineSpacing = newVal) }
                        }
                    },
                    valueRange = 1.0f..2.0f,
                    steps = 9
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(12.dp))

                // Auto-scroll
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Desplazamiento Automático (Auto-scroll)", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Sigue la posición del verso sincronizado automáticamente", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.autoScrollLyrics,
                        onCheckedChange = { checked ->
                            scope.launch { DesktopSettingsStore.updateSettings { it.copy(autoScrollLyrics = checked) } }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(12.dp))

                // Clic en verso para saltar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Clic en la Letra para Saltar", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Haz clic en cualquier línea para mover el reproductor a ese segundo", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.lyricsClick,
                        onCheckedChange = { checked ->
                            scope.launch { DesktopSettingsStore.updateSettings { it.copy(lyricsClick = checked) } }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(12.dp))

                // Desenfoque de líneas no activas
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Atenuar Líneas Inactivas", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Disminuye la opacidad de los versos anteriores y siguientes para resaltar la estrofa actual", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.lyricsLineBlur,
                        onCheckedChange = { checked ->
                            scope.launch { DesktopSettingsStore.updateSettings { it.copy(lyricsLineBlur = checked) } }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Proveedores de Letras
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Proveedores de Letras Sincronizadas", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Elige qué fuentes de letras consultar y su prioridad", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))

                // LRCLIB
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LRCLIB (Base de datos abierta y sincronizada)", fontSize = 14.sp, color = Color.White)
                    Switch(
                        checked = settings.enableLrclib,
                        onCheckedChange = { checked ->
                            scope.launch { DesktopSettingsStore.updateSettings { it.copy(enableLrclib = checked) } }
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))

                // KuGou
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("KuGou (Catálogo asiático e internacional)", fontSize = 14.sp, color = Color.White)
                    Switch(
                        checked = settings.enableKugou,
                        onCheckedChange = { checked ->
                            scope.launch { DesktopSettingsStore.updateSettings { it.copy(enableKugou = checked) } }
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))

                // SimpMusic
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SimpMusic Lyrics API", fontSize = 14.sp, color = Color.White)
                    Switch(
                        checked = settings.enableSimpMusic,
                        onCheckedChange = { checked ->
                            scope.launch { DesktopSettingsStore.updateSettings { it.copy(enableSimpMusic = checked) } }
                        }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Limpiar caché de letras
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                DesktopSettingsStore.clearLyricsCache()
                                lyricsClearedMsg = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Limpiar Caché de Letras")
                    }
                    if (lyricsClearedMsg) {
                        Spacer(Modifier.width(12.dp))
                        Text("¡Caché de letras limpiada!", color = Color(0xFF69F0AE), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// SUB-SCREEN 4: PLAYBACK & AUDIO
// -------------------------------------------------------------
@Composable
fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    settings: DesktopAppSettings,
    player: DesktopPlayer?
) {
    val scope = rememberCoroutineScope()
    var appId by remember { mutableStateOf(settings.qobuzAppId) }
    var appSecret by remember { mutableStateOf(settings.qobuzAppSecret) }
    var userToken by remember { mutableStateOf(settings.qobuzUserAuthToken) }
    var tokensSaved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsTopBar("Reproducción y Audio", onBack)

        // Fuente de Reproducción
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Fuente de Reproducción Principal", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Selecciona la fuente de streaming para reproducir pistas", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("YT_MUSIC" to "YouTube Music", "FLAC" to "FLAC Lossless").forEach { (src, label) ->
                        FilterChip(
                            selected = settings.playbackSource == src,
                            onClick = {
                                scope.launch {
                                    DesktopSettingsStore.updateSettings { it.copy(playbackSource = src) }
                                }
                            },
                            label = { Text(label) },
                            leadingIcon = {
                                if (settings.playbackSource == src) {
                                    Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                }

                if (settings.playbackSource == "FLAC") {
                    Spacer(Modifier.height(16.dp))
                    Text("Calidad de Streaming FLAC", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "CD" to "CD (16-bit / 44.1kHz)",
                            "HI_RES" to "Hi-Res (24-bit / 96kHz)",
                            "MAX" to "Studio Max (24-bit / 192kHz)"
                        ).forEach { (qual, label) ->
                            FilterChip(
                                selected = settings.flacQuality == qual,
                                onClick = {
                                    scope.launch {
                                        DesktopSettingsStore.updateSettings { it.copy(flacQuality = qual) }
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                    Text("Calidad YouTube Music", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "HIGH" to "Alta (Opus 160 kbps)",
                            "MEDIUM" to "Media (128 kbps)",
                            "LOW" to "Baja (70 kbps)"
                        ).forEach { (qual, label) ->
                            FilterChip(
                                selected = settings.audioQuality == qual,
                                onClick = {
                                    scope.launch {
                                        DesktopSettingsStore.updateSettings { it.copy(audioQuality = qual) }
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Tiempo de Salto Rápido (Seek)", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s").forEach { (secs, label) ->
                        FilterChip(
                            selected = settings.seekDurationSeconds == secs,
                            onClick = {
                                scope.launch {
                                    DesktopSettingsStore.updateSettings { it.copy(seekDurationSeconds = secs) }
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Credenciales Qobuz (si FLAC)
        if (settings.playbackSource == "FLAC") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Credenciales Qobuz Personalizadas", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Text("Opcional: configura tus propias claves de API de Qobuz para streaming directo", color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = appId,
                        onValueChange = { appId = it },
                        label = { Text("Qobuz App ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appSecret,
                        onValueChange = { appSecret = it },
                        label = { Text("Qobuz App Secret") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userToken,
                        onValueChange = { userToken = it },
                        label = { Text("Qobuz User Auth Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                scope.launch {
                                    DesktopSettingsStore.updateSettings {
                                        it.copy(
                                            qobuzAppId = appId,
                                            qobuzAppSecret = appSecret,
                                            qobuzUserAuthToken = userToken
                                        )
                                    }
                                    tokensSaved = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC))
                        ) {
                            Text("Guardar credenciales", color = Color.Black)
                        }
                        if (tokensSaved) {
                            Spacer(Modifier.width(12.dp))
                            Text("¡Credenciales guardadas!", color = Color(0xFF69F0AE), fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Filtros y DSP de Audio
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Filtros y Procesamiento de Audio", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(14.dp))

                // Normalización de volumen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Normalización de Volumen", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Ajusta la ganancia ReplayGain para nivelar el volumen entre distintas canciones", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.volumeNormalization,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(volumeNormalization = checked) }
                                player?.updateAudioFilters(checked, settings.skipSilence)
                            }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(12.dp))

                // Saltar silencios
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Saltar Silencios", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Filtro silenceremove para ignorar pausas y silencios iniciales o finales", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.skipSilence,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(skipSilence = checked) }
                                player?.updateAudioFilters(settings.volumeNormalization, checked)
                            }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(12.dp))

                // Auto-saltar en error
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Auto-saltar Canción si Hay Error", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Avanza de forma inmediata a la siguiente pista si un enlace expira o falla", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.autoSkipNextOnError,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(autoSkipNextOnError = checked) }
                                player?.autoSkipNextOnError = checked
                            }
                        }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// SUB-SCREEN 5: CONTENT & REGION
// -------------------------------------------------------------
@Composable
fun ContentSettingsScreen(
    onBack: () -> Unit,
    settings: DesktopAppSettings
) {
    val scope = rememberCoroutineScope()

    val countries = listOf(
        "system" to "Predeterminado del Sistema",
        "MX" to "México",
        "ES" to "España",
        "US" to "Estados Unidos",
        "AR" to "Argentina",
        "CO" to "Colombia",
        "CL" to "Chile",
        "JP" to "Japón",
        "KR" to "Corea del Sur"
    )

    val languages = listOf(
        "system" to "Predeterminado del Sistema",
        "es" to "Español",
        "en" to "English",
        "pt" to "Português",
        "ja" to "日本語",
        "ko" to "한국어"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsTopBar("Contenido y Región", onBack)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Región de Contenido (YouTube Music)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Determina las canciones en tendencia y listas de éxitos", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    countries.forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        DesktopSettingsStore.updateSettings { it.copy(contentCountry = code) }
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.contentCountry == code,
                                onClick = {
                                    scope.launch {
                                        DesktopSettingsStore.updateSettings { it.copy(contentCountry = code) }
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(14.dp))

                Text("Idioma del Contenido", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Idioma para los títulos y metadatos", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    languages.forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        DesktopSettingsStore.updateSettings { it.copy(contentLanguage = code) }
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.contentLanguage == code,
                                onClick = {
                                    scope.launch {
                                        DesktopSettingsStore.updateSettings { it.copy(contentLanguage = code) }
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Ocultar Contenido Explícito", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
                        Text("Filtra canciones marcadas como explícitas en búsquedas", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = settings.hideExplicit,
                        onCheckedChange = { checked ->
                            scope.launch {
                                DesktopSettingsStore.updateSettings { it.copy(hideExplicit = checked) }
                            }
                        }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// SUB-SCREEN 6: STORAGE
// -------------------------------------------------------------
@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var totalCacheBytes by remember { mutableStateOf(DesktopSettingsStore.getCacheSizeBytes()) }
    var audioCacheBytes by remember { mutableStateOf(DesktopSettingsStore.getAudioCacheSizeBytes()) }
    var imageCacheBytes by remember { mutableStateOf(DesktopSettingsStore.getImageCacheSizeBytes()) }
    var lyricsCacheBytes by remember { mutableStateOf(DesktopSettingsStore.getLyricsCacheSizeBytes()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsTopBar("Almacenamiento y Caché", onBack)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Uso de Almacenamiento en Disco", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Ubicación: ${DesktopSettingsStore.cacheDir.absolutePath}", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))

                // Métricas
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StorageStatBadge("Caché Total", formatBytes(totalCacheBytes), Color(0xFFBB86FC))
                    StorageStatBadge("Audio / MPV", formatBytes(audioCacheBytes), Color(0xFFFF5252))
                    StorageStatBadge("Imágenes", formatBytes(imageCacheBytes), Color(0xFF03DAC5))
                    StorageStatBadge("Letras", formatBytes(lyricsCacheBytes), Color(0xFFFFAB40))
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(16.dp))

                Text("Acciones de Limpieza", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                DesktopSettingsStore.clearAudioCache()
                                totalCacheBytes = DesktopSettingsStore.getCacheSizeBytes()
                                audioCacheBytes = DesktopSettingsStore.getAudioCacheSizeBytes()
                                statusMessage = "¡Caché de audio eliminada!"
                            }
                        }
                    ) {
                        Text("Limpiar audio")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                DesktopSettingsStore.clearImageCache()
                                totalCacheBytes = DesktopSettingsStore.getCacheSizeBytes()
                                imageCacheBytes = DesktopSettingsStore.getImageCacheSizeBytes()
                                statusMessage = "¡Caché de imágenes eliminada!"
                            }
                        }
                    ) {
                        Text("Limpiar imágenes")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                DesktopSettingsStore.clearCache()
                                totalCacheBytes = DesktopSettingsStore.getCacheSizeBytes()
                                audioCacheBytes = DesktopSettingsStore.getAudioCacheSizeBytes()
                                imageCacheBytes = DesktopSettingsStore.getImageCacheSizeBytes()
                                lyricsCacheBytes = DesktopSettingsStore.getLyricsCacheSizeBytes()
                                statusMessage = "¡Toda la memoria caché fue liberada!"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    ) {
                        Text("Limpiar toda la caché", color = Color.White)
                    }
                }

                if (statusMessage != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(statusMessage!!, color = Color(0xFF69F0AE), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StorageStatBadge(label: String, value: String, accent: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF282836))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = accent)
        Text(label, color = Color.Gray, fontSize = 11.sp)
    }
}

// -------------------------------------------------------------
// SUB-SCREEN 7: BACKUP & RESTORE
// -------------------------------------------------------------
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importJsonText by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsTopBar("Copia de Seguridad y Restauración", onBack)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Exportar Copia de Seguridad", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Guarda todos tus ajustes y configuración en formato JSON", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val exported = DesktopSettingsStore.exportSettingsJson()
                        val backupFile = File(System.getProperty("user.home"), "YumaPlayer_backup.json")
                        backupFile.writeText(exported)
                        exportStatus = "Copia guardada exitosamente en:\n${backupFile.absolutePath}"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC))
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Exportar copia a archivo", color = Color.Black)
                }

                exportStatus?.let { status ->
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = Color(0xFF69F0AE), fontSize = 12.sp)
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(18.dp))

                Text("Restaurar Copia de Seguridad", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text("Pega el contenido JSON de una copia previa para restaurar los ajustes", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    label = { Text("Contenido JSON") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 6
                )
                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        scope.launch {
                            val success = DesktopSettingsStore.importSettingsJson(importJsonText)
                            importStatus = if (success) "¡Ajustes restaurados exitosamente!" else "Error: JSON no válido"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03DAC5))
                ) {
                    Text("Restaurar ajustes", color = Color.Black)
                }

                importStatus?.let { status ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status,
                        color = if (status.startsWith("¡")) Color(0xFF69F0AE) else Color(0xFFFF5252),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// SUB-SCREEN 8: ABOUT
// -------------------------------------------------------------
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        SettingsTopBar("Acerca de", onBack)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFBB86FC).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFFBB86FC),
                        modifier = Modifier.size(44.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("YumaPlayer Desktop", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White)
                Text("Versión 1.0.0 (Linux Edition)", color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Reproductor de música de alta fidelidad con soporte dual YouTube Music y Lossless FLAC, diseñado para Linux.",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { uriHandler.openUri("https://github.com/Nahiro18/YumaPlayer") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282836))
                    ) {
                        Text("GitHub", color = Color.White)
                    }
                    Button(
                        onClick = { uriHandler.openUri("https://www.gnu.org/licenses/gpl-3.0.html") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282836))
                    ) {
                        Text("Licencia GPL-3.0", color = Color.White)
                    }
                }
            }
        }
    }
}
