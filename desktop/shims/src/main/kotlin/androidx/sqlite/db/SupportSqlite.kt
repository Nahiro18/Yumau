/*
 * YumaPlayer Desktop (Linux) port shim.
 *
 * Source-compatible stand-in for the legacy `androidx.sqlite.db.SupportSQLite*`
 * contracts. The pure-JVM flavour of androidx.sqlite (sqlite-jvm artifact) does
 * NOT ship these types — they only exist in the Android/AAR flavours — so we
 * declare them here with the same shape the shared `database` sources expect.
 * Real behaviour is contributed at runtime by :desktop:sqlite-jdbc-factory
 * (org.xerial:sqlite-jdbc).
 */
package androidx.sqlite.db

import android.content.ContentValues
import android.database.Cursor

/** Legacy exception type kept for API compatibility with the Android sources. */
open class SQLiteException(
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Statement contract used by Room migrations/callbacks (Android-compatible shape). */
interface SupportSQLiteStatement {
    fun bindNull(index: Int)

    fun bindLong(index: Int, value: Long)

    fun bindDouble(index: Int, value: Double)

    fun bindString(index: Int, value: String?)

    fun bindBlob(index: Int, value: ByteArray)

    fun clearBindings()

    fun executeInsert(): Long

    fun executeUpdateDelete(): Int

    fun executeSimple()

    fun execute() { executeSimple() }

    fun simpleQueryForLong(): Long

    fun simpleQueryForString(): String?

    fun close()
}

/** Query abstraction (sql + bind args) passed to [SupportSQLiteDatabase.query]. */
interface SupportSQLiteQuery {
    val sql: String

    /** Mirrors the real androidx helper that flattens bound arguments. */
    fun bindArgs(): Array<out Any?> = emptyArray()

    fun bindTo(statement: SupportSQLiteStatement)
}

class SimpleSQLiteQuery(
    override val sql: String,
    private val bindArgsArray: Array<Any?> = emptyArray(),
) : SupportSQLiteQuery {
    override fun bindArgs(): Array<out Any?> = bindArgsArray

    override fun bindTo(statement: SupportSQLiteStatement) {
        bindArgsArray.forEachIndexed { index, arg ->
            when (arg) {
                null -> statement.bindNull(index + 1)
                is Long -> statement.bindLong(index + 1, arg)
                is Int -> statement.bindLong(index + 1, arg.toLong())
                is Short -> statement.bindLong(index + 1, arg.toLong())
                is Byte -> statement.bindLong(index + 1, arg.toLong())
                is Boolean -> statement.bindLong(index + 1, if (arg) 1L else 0L)
                is Double -> statement.bindDouble(index + 1, arg)
                is Float -> statement.bindDouble(index + 1, arg.toDouble())
                is ByteArray -> statement.bindBlob(index + 1, arg)
                is String -> statement.bindString(index + 1, arg)
                else -> statement.bindString(index + 1, arg.toString())
            }
        }
    }
}

/**
 * Database contract consumed by Room callbacks, migrations and app code.
 * Method set mirrors what androidx.sqlite's SupportSQLiteDatabase exposes on
 * Android (superset trimmed to what this project actually calls).
 */
interface SupportSQLiteDatabase {
    val database: Any?

    val latestInsertRowId: Long

    val changeCount: Long

    val isOpen: Boolean

    val isReadOnly: Boolean

    fun getVersion(): Int

    fun setVersion(value: Int)

    val isInTransaction: Boolean

    val enableWriteAheadLogging: Boolean

    fun beginTransaction()

    fun beginTransactionNonExclusive()

    fun setTransactionSuccessful()

    fun endTransaction()

    fun inTransaction(body: () -> Unit)

    suspend fun <T> withTransaction(block: suspend () -> T): T

    fun query(sql: String): Cursor

    fun query(sql: String, bindArgs: Array<out Any?>?): Cursor

    fun query(supportQuery: SupportSQLiteQuery): Cursor

    fun rawQuery(sql: String, selectionArgs: Array<out String?>?): Cursor

    fun update(
        table: String,
        values: ContentValues,
    ): Long

    fun update(
        table: String,
        conflictAlgorithm: Int,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int

    fun insert(
        table: String,
        values: ContentValues,
    ): Long

    fun insert(
        conflictAlgorithm: Int,
        table: String,
        values: ContentValues,
    ): Long

    fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int

    fun compileStatement(sql: String): SupportSQLiteStatement

    fun execSQL(sql: String)

    fun execSQL(sql: String, bindArgs: Array<out Any?>?)

    fun getColumnNames(table: String): List<String>

    fun doesTableExist(tableName: String): Boolean

    fun doesIndexExist(
        tableName: String,
        indexName: String,
    ): Boolean

    fun doesColumnExist(
        tableName: String,
        columnName: String,
    ): Boolean

    fun attach(
        fileLocation: String,
        dbName: String,
    )

    fun detach(dbName: String)

    fun close()
}

/** Open-helper contract; Room instantiates databases through a [Factory]. */
interface SupportSQLiteOpenHelper {
    val databaseName: String?

    val writableDatabase: SupportSQLiteDatabase

    val readableDatabase: SupportSQLiteDatabase

    fun openWritableDatabase(): SupportSQLiteDatabase = writableDatabase

    fun openReadableDatabase(): SupportSQLiteDatabase = readableDatabase

    fun setWriteAheadLoggingEnabled(enabled: Boolean)

    fun resetReference()

    fun close()

    /**
     * Matches the Android shape: an abstract class (sources write
     * `object : SupportSQLiteOpenHelper.Callback(version) { ... }`).
     */
    abstract class Callback(
        @JvmField
        val version: Int,
    ) {
        abstract fun onCreate(db: SupportSQLiteDatabase)

        abstract fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        )

        open fun onConfigure(db: SupportSQLiteDatabase) = Unit

        open fun onDowngrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            throw SQLiteException(
                "A migration from $oldVersion to $newVersion was required but not found. " +
                    "Please provide the necessary Migration path via RoomDatabase" +
                    ".Builder.addMigrations or allow resetting it via fallbackToDestructiveMigration.",
            )
        }

        open fun onOpen(db: SupportSQLiteDatabase) = Unit

        open fun onCorruption(db: SupportSQLiteDatabase) = Unit
    }

    class Configuration private constructor(
        val context: android.content.Context?,
        val name: String?,
        val callback: Callback,
    ) {
        companion object {
            @JvmStatic
            fun builder(context: android.content.Context?): Builder = Builder(context)
        }

        class Builder(private val context: android.content.Context?) {
            private var name: String? = null
            private var callback: Callback? = null

            fun name(name: String?): Builder = apply { this.name = name }

            fun callback(callback: Callback): Builder = apply { this.callback = callback }

            fun build(): Configuration =
                Configuration(
                    context = context,
                    name = name,
                    callback = requireNotNull(callback) { "callback must be set" },
                )
        }
    }

    fun interface Factory {
        fun create(configuration: Configuration): SupportSQLiteOpenHelper
    }
}
