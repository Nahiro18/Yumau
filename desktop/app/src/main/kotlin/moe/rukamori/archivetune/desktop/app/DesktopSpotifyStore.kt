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

@Serializable
data class SpotifySessionData(
    val spDc: String = "",
    val accessToken: String = "",
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
            displayName = user?.displayName ?: "Usuario de Spotify",
            email = user?.email.orEmpty(),
            avatarUrl = user?.images?.firstOrNull()?.url.orEmpty(),
            isLoggedIn = true
        )
        _session.value = newSession
        saveSession(newSession)
        Result.success(user ?: SpotifyUser(id = "spotify_user", displayName = newSession.displayName))
    }

    suspend fun loginWithToken(token: String): Result<SpotifyUser> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        Spotify.accessToken = cleanToken

        val userResult = Spotify.me()
        val user = userResult.getOrElse { return@withContext Result.failure(it) }

        val newSession = SpotifySessionData(
            spDc = "",
            accessToken = cleanToken,
            displayName = user.displayName ?: "Usuario de Spotify",
            email = user.email.orEmpty(),
            avatarUrl = user.images.firstOrNull()?.url.orEmpty(),
            isLoggedIn = true
        )
        _session.value = newSession
        saveSession(newSession)
        Result.success(user)
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

        // Refresh token via spDc if needed
        val spDc = _session.value.spDc
        if (spDc.isNotBlank()) {
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
