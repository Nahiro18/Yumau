/*
 * YumaPlayer Desktop (Linux) port shim.
 * Minimal android.content.ContentValues replacement. The desktop build runs
 * Room on the JVM (androidx.room's JVM support consumes these Android types).
 */
package android.content

class ContentValues(
    val values: MutableMap<String, Any?> = mutableMapOf(),
) {
    constructor(initialCapacity: Int) : this(mutableMapOf())

    fun put(
        key: String,
        value: Any?,
    ) {
        values[key] = value
    }

    fun putNull(key: String) {
        values[key] = null
    }

    operator fun get(key: String): Any? = values[key]

    fun size(): Int = values.size

    fun isEmpty(): Boolean = values.isEmpty()

    fun keySet(): Set<String> = values.keys

    fun valueSet(): Set<Map.Entry<String, Any?>> = values.entries

    override fun toString(): String = values.toString()
}

fun contentValuesOf(vararg pairs: Pair<String, Any?>): ContentValues = ContentValues(pairs.toMap().toMutableMap())
