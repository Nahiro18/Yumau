package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DesktopFlacResolver {
    private val kvCache = ConcurrentHashMap<String, String>()

    // Cache de streams FLAC resueltos (0ms en re-escuchas) + negative cache (fail rapido)
    private val streamCache = ConcurrentHashMap<String, Pair<ResolvedAudioStream, Long>>()
    private val missCache = ConcurrentHashMap<String, Long>()
    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    private const val MISS_TTL_MS = 10 * 60 * 1000L

    // Circuit breaker propio: si Qobuz dice que NINGUN token sirve, no tiene sentido
    // pagar el intento (y su espera) en cada cancion. Se salta FLAC 10 min o hasta
    // que el usuario cambie las credenciales en Ajustes.
    private val flacSkipUntilMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val flacSkipSignature = java.util.concurrent.atomic.AtomicReference("")

    private fun credentialsSignature(): String {
        val s = DesktopSettingsStore.settings.value
        return listOf(s.qobuzAppId, s.qobuzAppSecret, s.qobuzUserAuthToken).joinToString("|")
    }

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
            DesktopSettingsStore.settings.value.qobuzAppId.trim()
                .ifEmpty { DesktopLosslessTokens.QOBUZ_APP_ID }

        // Fallback a credenciales integradas (como hacía la app Android):
        // el usuario puede sobrescribirlas en Ajustes si las suyas fallan.
        override suspend fun qbdlxAppSecret(): String =
            DesktopSettingsStore.settings.value.qobuzAppSecret.trim()
                .ifEmpty { DesktopLosslessTokens.QOBUZ_APP_SECRET }

        override suspend fun qbdlxTokenPool(): String {
            val raw = DesktopSettingsStore.settings.value.qobuzUserAuthToken.trim()
                .ifEmpty { DesktopLosslessTokens.QOBUZ_USER_AUTH_TOKEN }
            // El pool de Android se separa por comas; aceptamos tambien "una por linea".
            return raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
        }
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

    /**
     * Comprueba las credenciales EFECTIVAS (las del usuario o las integradas) contra
     * la API de Qobuz. Devuelve null si funcionan, o el motivo del fallo, para que
     * Ajustes pueda avisar sin tener que reproducir una cancion.
     */
    suspend fun testCredentials(): String? = withContext(Dispatchers.IO) {
        val s = DesktopSettingsStore.settings.value
        val appId = s.qobuzAppId.trim().ifEmpty { DesktopLosslessTokens.QOBUZ_APP_ID }
        val rawToken = s.qobuzUserAuthToken.trim()
            .ifEmpty { DesktopLosslessTokens.QOBUZ_USER_AUTH_TOKEN }
        val first = rawToken.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        // El pool usa "token:PAIS"; la API de Qobuz espera solo el token.
        val token = if (Regex(":[A-Za-z]{2}$").containsMatchIn(first)) first.dropLast(3) else first
        if (token.isBlank()) return@withContext "No hay ningun User Auth Token configurado"

        val url = "https://www.qobuz.com/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", "daft punk")
            .addQueryParameter("type", "tracks")
            .addQueryParameter("limit", "1")
            .addQueryParameter("app_id", appId)
            .build()
        try {
            val request = Request.Builder()
                .url(url)
                .header("X-App-Id", appId)
                .header("X-User-Auth-Token", token)
                .header("Accept", "application/json")
                .header("User-Agent", QOBUZ_UA)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.body?.string()
                when {
                    response.isSuccessful -> null
                    response.code == 401 -> "El token no es valido o esta caducado (401). Pega uno nuevo en User Auth Token."
                    response.code == 403 -> "Token rechazado por Qobuz (403). Puede estar baneado o ser de otra region."
                    else -> "Qobuz respondio HTTP ${response.code}"
                }
            }
        } catch (e: Exception) {
            "No se pudo contactar Qobuz: ${e.message ?: e::class.java.simpleName}"
        }
    }

    private const val QOBUZ_UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

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

        // Si ya sabemos que ningun token vale, saltar FLAC al instante para que
        // la reproduccion caiga a YouTube sin esperas (mismo comportamiento que
        // Android, que reproduce desde el primer momento).
        val signature = credentialsSignature()
        if (flacSkipSignature.get() != signature) {
            flacSkipSignature.set(signature)
            flacSkipUntilMs.set(0L)
        }
        if (System.currentTimeMillis() < flacSkipUntilMs.get()) {
            DesktopDiag.log("flac", 0L, "skip: sin token valido (en pausa)")
            return null
        }

        // Credenciales efectivas: las del usuario o las integradas (fallback).
        val effectiveSecret = s.qobuzAppSecret.trim()
            .ifEmpty { DesktopLosslessTokens.QOBUZ_APP_SECRET }
        val effectiveToken = s.qobuzUserAuthToken.trim()
            .ifEmpty { DesktopLosslessTokens.QOBUZ_USER_AUTH_TOKEN }
        if (effectiveSecret.isBlank() && effectiveToken.isBlank()) {
            // Sin credenciales de ningun tipo: fallo inmediato sin red
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

        // Presupuesto TOTAL para FLAC: 2.2 s. Antes eran 3 s por calidad (hasta 6 s)
        // y, sumado a YouTube, agotaba el tope de 10 s y dejaba la app sin audio.
        // Si el token esta muerto, el primer intento falla en ~0.3 s y se corta.
        var stream: moe.rukamori.archivetune.flaccore.streaming.FlacStreamUrl? = null
        val budgetEndsAt = System.currentTimeMillis() + 2200L
        for (candidateFormat in listOf(formatId, 6).distinct()) {
            val remaining = budgetEndsAt - System.currentTimeMillis()
            if (remaining <= 250L) break
            stream = withTimeoutOrNull(remaining) {
                runCatching { resolver.resolve(query, candidateFormat) }.getOrNull()
            }
            if (stream != null) break
            // Token invalido: probar otra calidad daria el mismo 401. Cortar ya.
            if (runCatching { credentialStore.allDead() }.getOrDefault(false)) break
        }
        if (stream == null) {
            if (runCatching { credentialStore.allDead() }.getOrDefault(false)) {
                flacSkipUntilMs.set(System.currentTimeMillis() + 10 * 60 * 1000L)
                DesktopDiag.log("flac", 0L, "sin token valido: FLAC en pausa 10 min")
            }
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
