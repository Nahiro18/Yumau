/*
 * YumaPlayer Desktop (Linux) port shim.
 * Minimal android.net.Uri replacement good enough for opaque/string URIs
 * used by the portable code paths (data-store keys, song ids, http urls).
 */
package android.net

class Uri private constructor(private val uriString: String) {
    override fun toString(): String = uriString

    val isOpaque: Boolean get() = !uriString.startsWith("content://") || uriString.contains(":") && !uriString.startsWith("content://")

    companion object {
        fun parse(uriString: String): Uri = Uri(uriString)
    }
}
