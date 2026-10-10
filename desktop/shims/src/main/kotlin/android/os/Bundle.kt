/*
 * YumaPlayer Desktop (Linux) port shim.
 * Minimal android.os.Bundle backed by a HashMap.
 */
package android.os

class Bundle {
    private val map = HashMap<String, Any?>()

    fun putString(key: String, value: String?) { map[key] = value }
    fun getString(key: String): String? = map[key] as String?
    fun putInt(key: String, value: Int) { map[key] = value }
    fun getInt(key: String, default: Int = 0): Int = map[key] as? Int ?: default
    fun putLong(key: String, value: Long) { map[key] = value }
    fun getLong(key: String, default: Long = 0L): Long = map[key] as? Long ?: default
    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun getBoolean(key: String, default: Boolean = false): Boolean = map[key] as? Boolean ?: default
    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun clear() = map.clear()
}
