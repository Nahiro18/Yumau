/*
 * Desktop shim: android.database.Cursor / sqlite surface required by
 * androidx.sqlite's SupportSQLiteDatabase contract (used by Room migrations).
 */
package android.database

interface Closeable {
    fun close()
}

/**
 * Minimal android.database.Cursor surface used by the desktop JDBC bridge and
 * Room's SupportSQLiteDatabase.query(...) contract.
 */
interface Cursor : Closeable {
    val count: Int
    val columnCount: Int
    val isClosed: Boolean
    fun moveToFirst(): Boolean
    fun moveToLast(): Boolean
    fun moveToNext(): Boolean
    fun moveToPrevious(): Boolean
    fun moveToPosition(position: Int): Boolean
    fun move(count: Int): Boolean
    fun isFirst(): Boolean
    fun isLast(): Boolean
    fun isBeforeFirst(): Boolean
    fun isAfterLast(): Boolean
    fun getString(columnIndex: Int): String?
    fun getShort(columnIndex: Int): Short
    fun getInt(columnIndex: Int): Int
    fun getLong(columnIndex: Int): Long
    fun getFloat(columnIndex: Int): Float
    fun getDouble(columnIndex: Int): Double
    fun getBlob(columnIndex: Int): ByteArray
    fun isNull(columnIndex: Int): Boolean
    fun getType(columnIndex: Int): Int
    fun getColumnIndex(columnName: String): Int
    fun getColumnIndexOrThrow(columnName: String): Int
    fun getColumnName(columnIndex: Int): String
    fun getColumnNames(): Array<String>
    fun copyStringToBuffer(columnIndex: Int, result: CharArrayBuffer?)
    fun deactivate()
    fun reconfigure()
    fun registerContentObserver(observer: ContentObserver?)
    fun unregisterContentObserver(observer: ContentObserver?)
    fun registerDataSetObserver(observer: DataSetObserver?)
    fun unregisterDataSetObserver(observer: DataSetObserver?)
    fun setNotificationUri(cr: android.content.ContentResolver?, uri: android.net.Uri?)
    fun getNotificationUri(): android.net.Uri?
    fun getWordingForColumnType(columnIndex: Int): String

    companion object {
        const val FIELD_TYPE_NULL = 0
        const val FIELD_TYPE_INTEGER = 1
        const val FIELD_TYPE_FLOAT = 2
        const val FIELD_TYPE_STRING = 3
        const val FIELD_TYPE_BLOB = 4
    }
}

class CharArrayBuffer(
    val size: Int = 50,
) {
    val data: CharArray = CharArray(size)
    var sizeCopied: Int = 0
}

open class ContentObserver(@Suppress("unused") private val handler: Any? = null) {
    open fun onChange(selfChange: Boolean) {}
}

abstract class DataSetObserver {
    open fun onChanged() {}

    open fun onInvalidated() {}
}

open class SQLiteException : RuntimeException {
    constructor() : super()
    constructor(message: String?) : super(message)
}
