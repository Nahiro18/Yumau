/*
 * YumaPlayer Desktop (Linux) port shim.
 * Provides the tiny slice of android.database.sqlite that androidx.sqlite's
 * JVM implementation (SupportSQLiteProgram / SupportSQLiteStatement) extends on Android.
 */
package android.database

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable

interface SqliteCursorRow {
    fun getType(columnIndex: Int): Int
}

open class SQLException(detailMessage: String? = null, cause: Throwable? = null) :
    Exception(detailMessage, cause)

abstract class SQLiteProgram(private val handlePointer: Long) {
    protected val database: SQLiteDatabase? get() = null

    fun bindString(index: Int, value: String?) {
        checkIndex(index)
        internalBindString(index, value)
    }

    fun bindLong(index: Int, value: Long) {
        checkIndex(index)
        internalBindLong(index, value)
    }

    fun bindDouble(index: Int, value: Double) {
        checkIndex(index)
        internalBindDouble(index, value)
    }

    fun bindNull(index: Int) {
        checkIndex(index)
        internalBindBlob(index, null)
    }

    private fun checkIndex(index: Int) {
        require(index >= 1) { "Invalid index $index, must be >= 1" }
    }

    internal abstract fun internalBindString(index: Int, value: String?)

    internal abstract fun internalBindLong(index: Int, value: Long)

    internal abstract fun internalBindDouble(index: Int, value: Double)

    internal abstract fun internalBindBlob(index: Int, value: ByteArray?)
}

abstract class SQLiteStatement : SQLiteProgram, SqliteCursorRow {
    constructor(handlePtr: Long) : super(handlePtr)

    abstract fun executeInsert(): Long

    abstract fun executeUpdateDelete(): Int

    abstract fun executeSimpleQuery(): android.database.Cursor

    abstract fun execute(): Boolean
}
