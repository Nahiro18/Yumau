/*
 * YumaPlayer Desktop (Linux) port shim.
 * Minimal android.content.ContentResolver used by the Cursor contract.
 */
package android.content

class ContentResolver(
    @Suppress("unused") private val owner: Context? = null,
)
