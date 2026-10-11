package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import javax.swing.SwingUtilities

@Serializable
data class BrowserSpotifySession(
    val spDc: String,
    val spKey: String = "",
    val spT: String = "",
    val fullCookieHeader: String = ""
)

object DesktopSpotifyBrowserAuth {
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

    private var authJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    private fun runOnMain(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }

    fun startBrowserLogin(
        scope: CoroutineScope,
        onSuccess: (session: BrowserSpotifySession) -> Unit,
        onError: (String) -> Unit
    ) {
        cancel()
        authJob = scope.launch(Dispatchers.IO) {
            try {
                val existingSession = scanAllBrowserCookies()
                if (existingSession != null && existingSession.spDc.isNotBlank()) {
                    val loginRes = DesktopSpotifyStore.loginWithBrowserSession(existingSession)
                    if (loginRes.isSuccess) {
                        runOnMain {
                            onSuccess(existingSession)
                        }
                        return@launch
                    }
                }

                openUrl(LOGIN_URL)

                val startTime = System.currentTimeMillis()
                val timeoutMs = 180_000L
                var lastSpDc = existingSession?.spDc.orEmpty()

                while (isActive && System.currentTimeMillis() - startTime < timeoutMs) {
                    delay(2000)
                    val session = scanAllBrowserCookies()
                    if (session != null && session.spDc.isNotBlank() && session.spDc != lastSpDc) {
                        val loginRes = DesktopSpotifyStore.loginWithBrowserSession(session)
                        if (loginRes.isSuccess) {
                            runOnMain {
                                onSuccess(session)
                            }
                            return@launch
                        }
                        lastSpDc = session.spDc
                    }
                }

                if (isActive) {
                    runOnMain {
                        onError("Tiempo de espera agotado. Inicia sesión en Spotify en tu navegador e intenta de nuevo.")
                    }
                }
            } catch (e: Exception) {
                runOnMain {
                    onError(e.message ?: "Error al sincronizar con el navegador")
                }
            }
        }
    }

    fun cancel() {
        authJob?.cancel()
        authJob = null
    }

    fun openUrl(url: String) {
        val commands = listOf(
            listOf("xdg-open", url),
            listOf("gio", "open", url),
            listOf("firefox", url),
            listOf("google-chrome", url),
            listOf("chromium", url),
            listOf("brave-browser", url)
        )
        for (cmd in commands) {
            try {
                val proc = ProcessBuilder(cmd).start()
                if (proc.isAlive) return
            } catch (_: Exception) {}
        }
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI(url))
            }
        } catch (_: Exception) {}
    }

    fun scanAllBrowserCookies(): BrowserSpotifySession? {
        try {
            val pyScript = """
                import sqlite3, glob, os, shutil, tempfile, json
                home = os.path.expanduser('~')
                patterns = [
                    f'{home}/.mozilla/firefox/*/cookies.sqlite',
                    f'{home}/.config/mozilla/firefox/*/cookies.sqlite',
                    f'{home}/.var/app/org.mozilla.firefox/.mozilla/firefox/*/cookies.sqlite',
                    f'{home}/snap/firefox/common/.mozilla/firefox/*/cookies.sqlite'
                ]
                paths = []
                for pat in patterns:
                    paths.extend(glob.glob(pat))

                for p in paths:
                    if not os.path.exists(p): continue
                    tmp = None
                    try:
                        tmp = tempfile.mktemp('.db')
                        shutil.copy2(p, tmp)
                        conn = sqlite3.connect(tmp)
                        c = conn.cursor()
                        c.execute("SELECT name, value FROM moz_cookies WHERE host LIKE '%spotify.com%'")
                        rows = c.fetchall()
                        conn.close()
                        if tmp and os.path.exists(tmp): os.remove(tmp)

                        cookie_dict = {r[0]: r[1] for r in rows}
                        if 'sp_dc' in cookie_dict and cookie_dict['sp_dc'].startswith('AQ'):
                            full_header = '; '.join([f'{k}={v}' for k, v in cookie_dict.items()])
                            out = {
                                'spDc': cookie_dict.get('sp_dc', ''),
                                'spKey': cookie_dict.get('sp_key', ''),
                                'spT': cookie_dict.get('sp_t', ''),
                                'fullCookieHeader': full_header
                            }
                            print(json.dumps(out))
                            exit(0)
                    except:
                        if tmp and os.path.exists(tmp):
                            try: os.remove(tmp)
                            except: pass
            """.trimIndent()
            val process = ProcessBuilder("python3", "-c", pyScript).start()
            val line = process.inputStream.bufferedReader().readLine()?.trim()
            if (!line.isNullOrBlank() && line.startsWith("{")) {
                return json.decodeFromString<BrowserSpotifySession>(line)
            }
        } catch (_: Exception) {}

        val home = File(System.getProperty("user.home"))
        val candidateDirs = listOf(
            File(home, ".config/mozilla/firefox"),
            File(home, ".mozilla/firefox"),
            File(home, ".var/app/org.mozilla.firefox/.mozilla/firefox"),
            File(home, "snap/firefox/common/.mozilla/firefox"),
            File(home, ".config/google-chrome"),
            File(home, ".config/chromium"),
            File(home, ".config/BraveSoftware/Brave-Browser"),
            File(home, ".var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser"),
            File(home, ".config/microsoft-edge"),
            File(home, ".config/opera"),
            File(home, ".config/vivaldi")
        )

        val filesToScan = mutableListOf<File>()
        for (dir in candidateDirs) {
            if (!dir.exists()) continue
            try {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && (file.name == "cookies.sqlite" || file.name == "Cookies")) {
                        filesToScan.add(file)
                    }
                }
            } catch (_: Exception) {}
        }

        val regex = Regex("""sp_dc(AQ[a-zA-Z0-9_\-]{50,250})""")
        for (file in filesToScan) {
            try {
                val bytes = file.readBytes()
                val text = bytes.toString(Charsets.ISO_8859_1)
                val match = regex.find(text)
                if (match != null) {
                    val spDc = match.groupValues[1]
                    return BrowserSpotifySession(spDc = spDc, fullCookieHeader = "sp_dc=$spDc")
                }
            } catch (_: Exception) {}
        }

        return null
    }
}
