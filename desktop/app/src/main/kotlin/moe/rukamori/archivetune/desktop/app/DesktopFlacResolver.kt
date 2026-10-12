package moe.rukamori.archivetune.desktop.app

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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

        val stream = runCatching { resolver.resolve(query, formatId) }.getOrNull() ?: return null

        val bitDepth = stream.bitsPerSample ?: 24
        val sampleRateKhz = (stream.sampleRateHz ?: 96000) / 1000f
        val sampleRateStr = if (sampleRateKhz % 1f == 0f) "${sampleRateKhz.toInt()} kHz" else "%.1f kHz".format(sampleRateKhz)
        val formatLabel = "FLAC • ${bitDepth}-bit $sampleRateStr"

        return ResolvedAudioStream(
            url = stream.url,
            mimeType = "audio/flac",
            isOpus = false,
            durationMs = if (durationMs > 0) durationMs else 0L,
            clientName = "QOBUZ_FLAC",
            formatLabel = formatLabel
        )
    }
}
