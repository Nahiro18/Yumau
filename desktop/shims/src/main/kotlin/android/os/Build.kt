/*
 * YumaPlayer Desktop (Linux) port shim.
 * Minimal android.os.Build surface used by shared sources.
 */
package android.os

object Build {
    const val MODEL: String = "Linux Desktop"
    const val MANUFACTURER: String = "Generic"
    const val BRAND: String = "Desktop"

    object VERSION {
        const val RELEASE: String = "1.0"
        const val SDK_INT: Int = 0
    }
}
