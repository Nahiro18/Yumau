package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

object DesktopSpotifyBrowserAuth {
    const val CLIENT_ID = "d748648d3e424074b126307686526eb5"
    private const val REDIRECT_URI = "http://127.0.0.1:8888/callback"
    private const val PORT = 8888

    private var authJob: Job? = null
    private var activeServerSocket: ServerSocket? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun startBrowserLogin(
        scope: CoroutineScope,
        onSuccess: (token: String, refreshToken: String?) -> Unit,
        onError: (String) -> Unit
    ) {
        cancel()
        authJob = scope.launch(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress("127.0.0.1", PORT))
                serverSocket.soTimeout = 180_000
                activeServerSocket = serverSocket

                val codeVerifier = generateCodeVerifier()
                val codeChallenge = generateCodeChallenge(codeVerifier)

                val scopes = listOf(
                    "user-read-private",
                    "user-read-email",
                    "user-library-read",
                    "playlist-read-private",
                    "playlist-read-collaborative"
                ).joinToString("%20")

                val authUrl = "https://accounts.spotify.com/authorize?" +
                        "client_id=$CLIENT_ID" +
                        "&response_type=code" +
                        "&redirect_uri=$REDIRECT_URI" +
                        "&code_challenge_method=S256" +
                        "&code_challenge=$codeChallenge" +
                        "&scope=$scopes" +
                        "&show_dialog=true"

                openUrl(authUrl)

                val cookieScannerJob = launch {
                    for (i in 1..45) {
                        delay(2500)
                        val cookie = scanLocalBrowsersForSpotifyCookie()
                        if (!cookie.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                onSuccess(cookie, null)
                            }
                            runCatching { serverSocket?.close() }
                            return@launch
                        }
                    }
                }

                while (isActive) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: SocketException) {
                        break
                    } catch (_: SocketTimeoutException) {
                        withContext(Dispatchers.Main) { onError("Tiempo de espera agotado. Intenta de nuevo.") }
                        break
                    }

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val requestLine = reader.readLine() ?: ""
                    val out = OutputStreamWriter(socket.getOutputStream())

                    if (requestLine.contains("favicon.ico")) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        out.flush()
                        socket.close()
                        continue
                    }

                    if (requestLine.contains("GET /callback")) {
                        val query = requestLine.substringAfter("GET /callback?").substringBefore(" HTTP")
                        val code = query.split("&")
                            .firstOrNull { it.startsWith("code=") }
                            ?.substringAfter("code=")
                        val error = query.split("&")
                            .firstOrNull { it.startsWith("error=") }
                            ?.substringAfter("error=")

                        if (!code.isNullOrBlank()) {
                            val html = """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                    <meta charset="UTF-8">
                                    <title>YumaPlayer - Conectado</title>
                                    <style>
                                        body { background: #0f1117; color: #f3f4f6; font-family: system-ui, -apple-system, sans-serif; text-align: center; padding-top: 80px; }
                                        .card { background: #161922; max-width: 460px; margin: 0 auto; padding: 36px; border-radius: 16px; border: 1px solid rgba(255,255,255,0.1); box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
                                        h2 { color: #1db954; font-size: 24px; margin-bottom: 12px; }
                                        p { color: #9ca3af; font-size: 15px; line-height: 1.5; }
                                    </style>
                                </head>
                                <body>
                                    <div class="card">
                                        <h2>¡Conectado exitosamente con Spotify!</h2>
                                        <p>Tu cuenta se ha vinculado a YumaPlayer.<br>Ya puedes cerrar esta pestaña y volver a la app.</p>
                                    </div>
                                    <script>
                                        setTimeout(() => { try { window.close(); } catch(e){} }, 2000);
                                    </script>
                                </body>
                                </html>
                            """.trimIndent()
                            val bytes = html.toByteArray(Charsets.UTF_8)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$html")
                            out.flush()
                            socket.close()

                            cookieScannerJob.cancel()
                            runCatching { serverSocket.close() }

                            val tokens = exchangeCodeForToken(code, codeVerifier, REDIRECT_URI, CLIENT_ID)
                            if (tokens != null) {
                                withContext(Dispatchers.Main) {
                                    onSuccess(tokens.first, tokens.second)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    onError("No se pudo obtener el token de Spotify")
                                }
                            }
                            break
                        } else if (!error.isNullOrBlank()) {
                            val html = """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                    <meta charset="UTF-8">
                                    <title>YumaPlayer - Error</title>
                                    <style>
                                        body { background: #0f1117; color: #f3f4f6; font-family: system-ui, -apple-system, sans-serif; text-align: center; padding-top: 80px; }
                                        .card { background: #161922; max-width: 460px; margin: 0 auto; padding: 36px; border-radius: 16px; border: 1px solid rgba(255,255,255,0.1); }
                                        h2 { color: #ef4444; font-size: 24px; margin-bottom: 12px; }
                                        p { color: #9ca3af; font-size: 15px; }
                                    </style>
                                </head>
                                <body>
                                    <div class="card">
                                        <h2>Autorización no completada</h2>
                                        <p>Spotify reportó: $error</p>
                                    </div>
                                </body>
                                </html>
                            """.trimIndent()
                            val bytes = html.toByteArray(Charsets.UTF_8)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$html")
                            out.flush()
                            socket.close()

                            cookieScannerJob.cancel()
                            runCatching { serverSocket.close() }

                            withContext(Dispatchers.Main) {
                                onError("Autorización cancelada: $error")
                            }
                            break
                        } else {
                            socket.close()
                        }
                    } else {
                        socket.close()
                    }
                }
            } catch (e: Exception) {
                if (e !is SocketException) {
                    withContext(Dispatchers.Main) { onError(e.message ?: "Error al autenticar") }
                }
            } finally {
                runCatching { serverSocket?.close() }
                activeServerSocket = null
            }
        }
    }

    fun cancel() {
        authJob?.cancel()
        authJob = null
        runCatching { activeServerSocket?.close() }
        activeServerSocket = null
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun exchangeCodeForToken(
        code: String,
        codeVerifier: String,
        redirectUri: String,
        clientId: String
    ): Pair<String, String?>? {
        return try {
            val formBody = buildString {
                append("grant_type=authorization_code")
                append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
                append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
                append("&code_verifier=").append(URLEncoder.encode(codeVerifier, "UTF-8"))
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
                val accessToken = jsonTree?.get("access_token")?.jsonPrimitive?.content
                val refreshToken = jsonTree?.get("refresh_token")?.jsonPrimitive?.contentOrNull
                if (!accessToken.isNullOrBlank()) {
                    Pair(accessToken, refreshToken)
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun openUrl(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (_: Exception) {}
        try {
            ProcessBuilder("xdg-open", url).start()
        } catch (_: Exception) {}
    }

    private fun scanLocalBrowsersForSpotifyCookie(): String? {
        val candidatePaths = listOf(
            File(System.getProperty("user.home"), ".var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser/Default/Cookies"),
            File(System.getProperty("user.home"), ".config/google-chrome/Default/Cookies"),
            File(System.getProperty("user.home"), ".config/chromium/Default/Cookies"),
            File(System.getProperty("user.home"), ".config/BraveSoftware/Brave-Browser/Default/Cookies")
        )

        for (dbFile in candidatePaths) {
            if (!dbFile.exists()) continue
            try {
                val text = dbFile.readBytes().toString(Charsets.ISO_8859_1)
                val idx = text.indexOf("sp_dc")
                if (idx >= 0) {
                    val sub = text.substring(idx + 5, (idx + 250).coerceAtMost(text.length))
                    val match = Regex("""([a-zA-Z0-9_\-]{50,150})""").find(sub)
                    if (match != null) {
                        return match.value
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}

