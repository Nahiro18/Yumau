package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI

object DesktopSpotifyBrowserAuth {
    private const val CLIENT_ID = "d748648d3e424074b126307686526eb5"
    private const val REDIRECT_URI = "http://127.0.0.1:8888/callback"
    private const val PORT = 8888

    private var authJob: Job? = null

    fun startBrowserLogin(
        scope: CoroutineScope,
        onSuccess: (token: String) -> Unit,
        onError: (String) -> Unit
    ) {
        authJob?.cancel()
        authJob = scope.launch(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            try {
                try {
                    serverSocket = ServerSocket(PORT)
                } catch (e: Exception) {
                    serverSocket = ServerSocket(0)
                }
                serverSocket.soTimeout = 180_000 // 3 min timeout
                val actualPort = serverSocket.localPort
                val redirectUri = "http://127.0.0.1:$actualPort/callback"

                val scopes = listOf(
                    "user-read-private",
                    "user-read-email",
                    "user-library-read",
                    "playlist-read-private",
                    "playlist-read-collaborative"
                ).joinToString("%20")

                val authUrl = "https://accounts.spotify.com/authorize?" +
                        "client_id=$CLIENT_ID" +
                        "&response_type=token" +
                        "&redirect_uri=$redirectUri" +
                        "&scope=$scopes" +
                        "&show_dialog=true"

                openUrl(authUrl)

                // Background scanner: also check if user logged in via cookies in Brave / Chrome / Firefox
                val cookieScannerJob = launch {
                    for (i in 1..45) {
                        delay(2500)
                        val cookie = scanLocalBrowsersForSpotifyCookie()
                        if (!cookie.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                onSuccess(cookie)
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

                    if (requestLine.contains("GET /callback")) {
                        val html = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta charset="UTF-8">
                                <title>YumaPlayer - Conectando Spotify</title>
                                <style>
                                    body { background: #0f1117; color: #f3f4f6; font-family: system-ui, -apple-system, sans-serif; text-align: center; padding-top: 80px; }
                                    .card { background: #161922; max-width: 460px; margin: 0 auto; padding: 36px; border-radius: 16px; border: 1px solid rgba(255,255,255,0.1); box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
                                    h2 { color: #1db954; font-size: 24px; margin-bottom: 12px; }
                                    p { color: #9ca3af; font-size: 15px; line-height: 1.5; }
                                </style>
                            </head>
                            <body>
                                <div class="card">
                                    <h2>¡Sesión de Spotify detectada!</h2>
                                    <p id="msg">Sincronizando cuenta con YumaPlayer...</p>
                                </div>
                                <script>
                                    const hash = window.location.hash.substring(1);
                                    if (hash && hash.includes('access_token')) {
                                        fetch('/token?' + hash).then(() => {
                                            document.getElementById('msg').innerText = '¡Conectado exitosamente! Ya puedes cerrar esta pestaña y disfrutar tu música en YumaPlayer.';
                                            setTimeout(() => { try { window.close(); } catch(e){} }, 2000);
                                        });
                                    } else {
                                        document.getElementById('msg').innerText = 'Esperando autorización de Spotify...';
                                    }
                                </script>
                            </body>
                            </html>
                        """.trimIndent()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html")
                        out.flush()
                        socket.close()
                    } else if (requestLine.contains("GET /token?")) {
                        val params = requestLine.substringAfter("GET /token?").substringBefore(" HTTP")
                        val token = params.split("&")
                            .firstOrNull { it.startsWith("access_token=") }
                            ?.substringAfter("access_token=")

                        val resp = "OK"
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${resp.length}\r\nConnection: close\r\n\r\n$resp")
                        out.flush()
                        socket.close()

                        cookieScannerJob.cancel()
                        runCatching { serverSocket.close() }

                        if (!token.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                onSuccess(token)
                            }
                        }
                        break
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
            }
        }
    }

    fun cancel() {
        authJob?.cancel()
        authJob = null
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
                    // Extract printable cookie characters
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
