package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.flaccore.FlacConfig
import moe.rukamori.archivetune.flaccore.FlacKvStore
import moe.rukamori.archivetune.flaccore.model.TrackQuery
import moe.rukamori.archivetune.flaccore.qbdlx.QbdlxApiClient
import moe.rukamori.archivetune.flaccore.qbdlx.QbdlxCredentialStore
import moe.rukamori.archivetune.flaccore.qbdlx.QbdlxPoolProvider
import moe.rukamori.archivetune.flaccore.qbdlx.QbdlxQobuzSource
import moe.rukamori.archivetune.flaccore.qbdlx.QbdlxSigner
import moe.rukamori.archivetune.flaccore.ratelimit.AggregatorRateLimiter
import moe.rukamori.archivetune.flaccore.streaming.QbdlxStreamResolver
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DesktopFlacResolver {
    private val kvCache = ConcurrentHashMap<String, String>()

    // Cache de streams FLAC resueltos (0ms en re-escuchas) + negative cache (fail rapido)
    private val streamCache = ConcurrentHashMap<String, Pair<ResolvedAudioStream, Long>>()
    private val missCache = ConcurrentHashMap<String, Long>()
    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    private const val MISS_TTL_MS = 30 * 60 * 1000L

    /** Las URLs de streaming traen ?expire=<unix>; no reusar una caducada (provocaria 403). */
    private fun isUrlExpired(url: String): Boolean {
        return runCatching {
            val query = url.substringAfter("?", "")
            if (query.isEmpty()) return false
            val expire = query.split("&")
                .firstOrNull { it.startsWith("expire=") }
                ?.substringAfter("=")
                ?.toLongOrNull() ?: return false
            expire < (System.currentTimeMillis() / 1000L) - 60L
        }.getOrDefault(false)
    }

    private val flacConfig = object : FlacConfig {
        override suspend fun qbdlxEnabled(): Boolean =
            DesktopSettingsStore.settings.value.playbackSource == "FLAC"

        override suspend fun qbdlxAppId(): String =
            DesktopSettingsStore.settings.value.qobuzAppId.trim().ifEmpty { "798273057" }

        override suspend fun qbdlxAppSecret(): String =
            DesktopSettingsStore.settings.value.qobuzAppSecret.trim()

        override suspend fun qbdlxTokenPool(): String =
            DesktopSettingsStore.settings.value.qobuzUserAuthToken.trim()
    }

    private val flacKvStore = object : FlacKvStore {
        override suspend fun get(key: String): String? = kvCache[key]
        override suspend fun put(key: String, value: String?) {
            if (value == null) kvCache.remove(key) else kvCache[key] = value
        }
    }

    private val credentialStore = QbdlxCredentialStore(
        config = flacConfig,
        kvStore = flacKvStore,
        poolProvider = QbdlxPoolProvider { flacConfig.qbdlxTokenPool() }
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val apiClient = QbdlxApiClient(
        config = flacConfig,
        sharedClient = httpClient,
        signer = QbdlxSigner(),
        signingResolver = credentialStore
    )

    private val qobuzSource = QbdlxQobuzSource(
        apiClient = apiClient,
        credentialStore = credentialStore,
        rateLimiter = AggregatorRateLimiter(),
        config = flacConfig
    )

    private val resolver = QbdlxStreamResolver(qobuzSource)

    suspend fun resolve(
        title: String,
        artist: String,
        durationMs: Long = 0L,
        quality: String = "HI_RES"
    ): ResolvedAudioStream? {
        val key = "${title.lowercase()}|${artist.lowercase()}|$quality"
        streamCache[key]?.let { (cached, ts) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS && !isUrlExpired(cached.url)) return cached
            else streamCache.remove(key)
        }
        missCache[key]?.let { ts ->
            if (System.currentTimeMillis() - ts < MISS_TTL_MS) return null
            else missCache.remove(key)
        }

        val s = DesktopSettingsStore.settings.value
        val hasSecret = s.qobuzAppSecret.trim().isNotBlank()
        val hasToken = s.qobuzUserAuthToken.trim().isNotBlank()
        if (!hasSecret && !hasToken) {
            // Sin credenciales: fallo inmediato sin red (no penaliza a YT en la carrera)
            return null
        }

        val formatId = when (quality) {
            "CD" -> 6
            "HI_RES" -> 7
            "MAX" -> 27
            else -> 7
        }
        val query = TrackQuery(
            artist = artist,
            title = title,
            durationMs = if (durationMs > 0) durationMs else null
        )

        val stream = withTimeoutOrNull(1500L) {
            runCatching { resolver.resolve(query, formatId) }.getOrNull()
        }
        if (stream == null) {
            missCache[key] = System.currentTimeMillis()
            return null
        }

        val bitDepth = stream.bitsPerSample ?: 24
        val sampleRateKhz = (stream.sampleRateHz ?: 96000) / 1000f
        val sampleRateStr = if (sampleRateKhz % 1f == 0f) "${sampleRateKhz.toInt()} kHz" else "%.1f kHz".format(sampleRateKhz)
        val formatLabel = "FLAC • ${bitDepth}-bit $sampleRateStr"

        val resolved = ResolvedAudioStream(
            url = stream.url,
            mimeType = "audio/flac",
            isOpus = false,
            durationMs = if (durationMs > 0) durationMs else 0L,
            clientName = "QOBUZ_FLAC",
            formatLabel = formatLabel
        )
        streamCache[key] = resolved to System.currentTimeMillis()
        // LRU simple: cap a 200 entradas
        if (streamCache.size > 200) {
            val oldest = streamCache.entries.minByOrNull { it.value.second }?.key
            if (oldest != null) streamCache.remove(oldest)
        }
        return resolved
    }
}
