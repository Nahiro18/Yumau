/*
 * Desktop shim: android.database.Cursor / sqlite surface required by
 * androidx.sqlite's SupportSQLiteDatabase contract (used by Room migrations).
 */
package android.database

interface Closeable {
    fun close()
}

interface Cursor : Closeable {
    val count: Int
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getString(columnIndex: Int): String?
    fun getInt(columnIndex: Int): Int
    fun getLong(columnIndex: Int): Long
    fun getColumnIndex(columnName: String): Int
}

open class SQLiteException : RuntimeException {
    constructor() : super()
    constructor(message: String?) : super(message)
}
