package moe.rukamori.archivetune.desktop.app

/**
 * Credenciales lossless integradas (mismas que llevaba la app Android en
 * LosslessTokens.kt). Se usan como FALLBACK cuando el usuario no ha pegado las
 * suyas en Ajustes -> Reproduccion y Audio -> Credenciales Qobuz.
 *
 * Flujo de prioridad (igual que hacía Android):
 *   settings del usuario  >  estos valores integrados
 */
object DesktopLosslessTokens {
    const val QOBUZ_APP_ID = "798273057"
    const val QOBUZ_APP_SECRET = "abb21364945c0583309667d13ca3d93a"
    const val QOBUZ_USER_AUTH_TOKEN = "jM-6F2QcDpfG7fj1RRPq7bAa7tBVCykt__5HD1K25v2yFq0c9_-SmXEhG-74moNpN5YQTmFFyyMq2F70h1G17A:FR"
}
