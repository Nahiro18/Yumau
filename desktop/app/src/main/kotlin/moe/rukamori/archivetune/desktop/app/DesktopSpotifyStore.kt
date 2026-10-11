package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.SearchFilter
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyAuth
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTrack
import moe.rukamori.archivetune.spotify.models.SpotifySavedTrack
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.spotify.models.SpotifyUser
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SpotifySessionData(
    val spDc: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val displayName: String = "",
    val email: String = "",
    val avatarUrl: String = "",
    val isLoggedIn: Boolean = false,
)

object DesktopSpotifyStore {
    private val configDir = File(System.getProperty("user.home"), ".config/YumaPlayer").apply { mkdirs() }
    private val sessionFile = File(configDir, "spotify_session.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _session = MutableStateFlow(SpotifySessionData())
    val session: StateFlow<SpotifySessionData> = _session.asStateFlow()

    private val _likedSongs = MutableStateFlow<List<SpotifySavedTrack>>(emptyList())
    val likedSongs: StateFlow<List<SpotifySavedTrack>> = _likedSongs.asStateFlow()

    private val _playlists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val playlists: StateFlow<List<SpotifyPlaylist>> = _playlists.asStateFlow()

    init {
        loadSession()
    }

    private fun loadSession() {
        try {
            if (sessionFile.exists()) {
                val data = json.decodeFromString<SpotifySessionData>(sessionFile.readText())
                _session.value = data
                if (data.accessToken.isNotBlank()) {
                    Spotify.accessToken = data.accessToken
                }
            }
        } catch (_: Exception) {}
    }

    fun openBrowserLogin() {
        val url = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                return
            }
        } catch (_: Exception) {}
        try {
            ProcessBuilder("xdg-open", url).start()
        } catch (_: Exception) {}
    }

    suspend fun loginWithSpDc(spDc: String): Result<SpotifyUser> = withContext(Dispatchers.IO) {
        val cleanSpDc = spDc.trim()
        val tokenResult = SpotifyAuth.fetchAccessToken(cleanSpDc)
        val token = tokenResult.getOrElse { return@withContext Result.failure(it) }

        Spotify.accessToken = token.accessToken

        val userResult = Spotify.me()
        val user = userResult.getOrNull()

        val newSession = SpotifySessionData(
            spDc = cleanSpDc,
            accessToken = token.accessToken,
            refreshToken = "",
            displayName = user?.displayName ?: "Usuario de Spotify",
            email = user?.email.orEmpty(),
            avatarUrl = user?.images?.firstOrNull()?.url.orEmpty(),
            isLoggedIn = true
        )
        _session.value = newSession
        saveSession(newSession)
        Result.success(user ?: SpotifyUser(id = "spotify_user", displayName = newSession.displayName))
    }

    private const val DEFAULT_CLIENT_ID = "d420a117a32841c2b3474932e49fb54b"

    suspend fun loginWithToken(token: String, refreshToken: String? = null): Result<SpotifyUser> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.startsWith("AQ") || cleanToken.length in 50..250) {
            val spDcRes = loginWithSpDc(cleanToken)
            if (spDcRes.isSuccess) return@withContext spDcRes
        }

        Spotify.accessToken = cleanToken

        val userResult = Spotify.me()
        val user = userResult.getOrElse {
            val spDcRes = loginWithSpDc(cleanToken)
            if (spDcRes.isSuccess) return@withContext spDcRes
            return@withContext Result.failure(it)
        }

        val newSession = SpotifySessionData(
            spDc = "",
            accessToken = cleanToken,
            refreshToken = refreshToken.orEmpty(),
            displayName = user.displayName ?: "Usuario de Spotify",
            email = user.email.orEmpty(),
            avatarUrl = user.images.firstOrNull()?.url.orEmpty(),
            isLoggedIn = true
        )
        _session.value = newSession
        saveSession(newSession)
        Result.success(user)
    }

    suspend fun refreshWithRefreshToken(refreshToken: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val formBody = buildString {
                append("grant_type=refresh_token")
                append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"))
                append("&client_id=").append(URLEncoder.encode(DEFAULT_CLIENT_ID, "UTF-8"))
            }

            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val jsonTree = json.parseToJsonElement(response.body()) as? JsonObject
                val newAccess = jsonTree?.get("access_token")?.jsonPrimitive?.content
                if (!newAccess.isNullOrBlank()) {
                    val newRefresh = jsonTree.get("refresh_token")?.jsonPrimitive?.contentOrNull ?: refreshToken
                    val updated = _session.value.copy(accessToken = newAccess, refreshToken = newRefresh)
                    _session.value = updated
                    saveSession(updated)
                    Spotify.accessToken = newAccess
                    return@withContext Result.success(newAccess)
                }
            }
            Result.failure(Exception("HTTP ${response.statusCode()}: ${response.body()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        Spotify.accessToken = null
        val cleared = SpotifySessionData()
        _session.value = cleared
        _likedSongs.value = emptyList()
        _playlists.value = emptyList()
        saveSession(cleared)
    }

    private fun saveSession(data: SpotifySessionData) {
        try {
            sessionFile.writeText(json.encodeToString(data))
        } catch (_: Exception) {}
    }

    suspend fun refreshLibrary() = withContext(Dispatchers.IO) {
        if (!_session.value.isLoggedIn) return@withContext

        val refreshToken = _session.value.refreshToken
        val spDc = _session.value.spDc
        if (refreshToken.isNotBlank()) {
            refreshWithRefreshToken(refreshToken)
        } else if (spDc.isNotBlank()) {
            val tokenRes = SpotifyAuth.fetchAccessToken(spDc)
            tokenRes.getOrNull()?.let {
                Spotify.accessToken = it.accessToken
                _session.value = _session.value.copy(accessToken = it.accessToken)
                saveSession(_session.value)
            }
        }

        runCatching {
            val liked = Spotify.likedSongs(limit = 50).getOrNull()
            if (liked != null) {
                _likedSongs.value = liked.items
            }
        }

        runCatching {
            val pls = Spotify.myPlaylists(limit = 50).getOrNull()
            if (pls != null) {
                _playlists.value = pls.items
            }
        }
    }

    suspend fun fetchPlaylistTracks(playlistId: String): List<SpotifyPlaylistTrack> = withContext(Dispatchers.IO) {
        Spotify.playlistTracks(playlistId, limit = 100).getOrNull()?.items.orEmpty()
    }

    suspend fun matchSpotifyTrackToYouTube(track: SpotifyTrack): SongItem? = withContext(Dispatchers.IO) {
        val artistName = track.artists.firstOrNull()?.name.orEmpty()
        val query = "${track.name} $artistName"
        try {
            val res = YouTube.search(query, filter = SearchFilter.FILTER_SONG).getOrNull()
            val songs = res?.items?.filterIsInstance<SongItem>().orEmpty()
            if (songs.isNotEmpty()) {
                // Find closest match or return first
                val exact = songs.find { s ->
                    s.title.contains(track.name, ignoreCase = true) ||
                    track.name.contains(s.title, ignoreCase = true)
                }
                return@withContext exact ?: songs.first()
            }
        } catch (_: Exception) {}
        null
    }
}
