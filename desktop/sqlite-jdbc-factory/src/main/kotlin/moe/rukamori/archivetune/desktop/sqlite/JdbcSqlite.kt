/*
 * YumaPlayer Desktop (Linux) port.
 *
 * Real androidx.sqlite implementation backed by the pure-JVM org.xerial:sqlite-jdbc
 * driver. This module is loaded at runtime by :desktop:app and installs itself into
 * the DesktopSqliteBridge hook provided by :desktop:shims, so the verbatim Android
 * `database` sources (Room + FrameworkSQLiteOpenHelperFactory) keep working on the
 * desktop without any source changes.
 */
package moe.rukamori.archivetune.desktop.sqlite

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.DesktopSqliteBridge
import java.sql.Connection
import java.sql.DriverManager

/** Entry point invoked from the desktop app before opening any database. */
object DesktopSqliteInstaller {
    fun install() {
        // Force driver registration even when ServiceLoader is unavailable.
        Class.forName("org.sqlite.JDBC")
        DesktopSqliteBridge.install(JdbcSQLiteOpenHelper.Factory, null)
        androidx.room.desktop.DesktopRoomInstaller.install()
    }
}

/** Minimal JDBC-backed [SupportSQLiteDatabase]. Only what Room actually uses. */
class JdbcSupportDatabase(
    private val path: String,
    private var foreignKeysEnabled: Boolean = true,
) : SupportSQLiteDatabase {
    private var conn: Connection? = null

    private fun db(): Connection =
        conn ?: synchronized(this) {
            conn ?: DriverManager.getConnection("jdbc:sqlite:$path").also { c ->
                c.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode = WAL")
                    st.execute("PRAGMA foreign_keys = ${if (foreignKeysEnabled) "ON" else "OFF"}")
                }
                conn = c
            }
        }

    override val database: Any get() = db()

    override val isOpen: Boolean get() = conn?.isClosed == false

    override val isReadOnly: Boolean get() = false

    override val version: Int
        get() = db().createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").let { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    override fun setVersion(value: Int) {
        db().createStatement().use { it.execute("PRAGMA user_version = $value") }
    }

    override var latestInsertRowId: Long
        get() = db().createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").let { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }
        set(_) {}

    override val changeCount: Long
        get() = db().createStatement().use { st ->
            st.executeQuery("SELECT total_changes()").let { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    override var isInTransaction: Boolean = false
        private set

    override val enableWriteAheadLogging: Boolean get() = true

    override fun beginTransaction() {
        db().autoCommit = false
        isInTransaction = true
    }

    override fun beginTransactionNonExclusive() = beginTransaction()

    override fun setTransactionSuccessful() { /* commit happens in endTransaction */ }

    override fun endTransaction() {
        try {
            db().commit()
        } catch (_: Exception) {
            db().rollback()
        } finally {
            db().autoCommit = true
            isInTransaction = false
        }
    }

    override fun inTransaction(body: () -> Unit) {
        beginTransaction()
        try {
            body()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    override suspend fun <T> withTransaction(block: suspend () -> T): T {
        beginTransaction()
        return try {
            block().also { setTransactionSuccessful() }
        } catch (t: Throwable) {
            try {
                db().rollback()
                db().autoCommit = true
            } catch (_: Exception) {
            } finally {
                isInTransaction = false
            }
            throw t
        } finally {
            if (isInTransaction) endTransaction()
        }
    }

    override fun compileStatement(sql: String): androidx.sqlite.db.SupportSQLiteStatement =
        JdbcPreparedStatment(db(), sql)

    override fun query(sql: String): Cursor = rawQuery(sql, null)

    override fun query(
        sql: String,
        bindArgs: Array<out Any?>?,
    ): Cursor = execQuery(sql, bindArgs?.map { it })

    override fun rawQuery(
        sql: String,
        selectionArgs: Array<out String?>?,
    ): Cursor = execQuery(sql, selectionArgs?.map { it as Any? })

    override fun query(q: androidx.sqlite.db.SupportSQLiteQuery): Cursor =
        execQuery(q.sql, q.bindArgs().toList())

    private fun execQuery(
        sql: String,
        args: List<Any?>?,
    ): Cursor {
        val ps = db().prepareStatement(sql)
        args?.forEachIndexed { i, v -> ps.setObject(i + 1, normalize(v)) }
        return JdbcCursor(ps.executeQuery(), ps)
    }

    override fun update(
        table: String,
        values: android.content.ContentValues,
    ): Long = update(table, CONFLICT_NONE, values, null, null)

    override fun update(
        table: String,
        conflictAlgorithm: Int,
        values: android.content.ContentValues,
    ): Long = update(table, conflictAlgorithm, values, null, null)

    override fun insert(
        table: String,
        values: android.content.ContentValues,
    ): Long = insert(CONFLICT_NONE, table, values)

    override fun insert(
        table: String,
        conflictAlgorithm: Int,
        values: android.content.ContentValues,
    ): Long {
        val cols = values.keySet().toList()
        if (cols.isEmpty()) return -1L
        val onConflict =
            when (conflictAlgorithm) {
                CONFLICT_REPLACE -> " OR REPLACE"
                CONFLICT_IGNORE -> " OR IGNORE"
                else -> ""
            }
        val sql = "INSERT$onConflict INTO `$table` (${cols.joinToString(", ") { "`$it`" }}) VALUES (${cols.joinToString(", ") { "?" }})"
        val ps = db().prepareStatement(sql)
        cols.forEachIndexed { i, c -> ps.setObject(i + 1, normalize(values.get(c))) }
        ps.executeUpdate()
        return latestInsertRowId
    }

    override fun update(
        table: String,
        conflictAlgorithm: Int,
        values: android.content.ContentValues,
        where: String?,
        whereArgs: Array<out Any?>?,
    ): Int {
        val cols = values.keySet().toList()
        if (cols.isEmpty()) return 0
        val sql =
            buildString {
                append("UPDATE")
                when (conflictAlgorithm) {
                    CONFLICT_REPLACE -> append(" OR REPLACE")
                    CONFLICT_IGNORE -> append(" OR IGNORE")
                }
                append(" `$table` SET ")
                append(cols.joinToString(", ") { "`$it` = ?" })
                if (!where.isNullOrEmpty()) {
                    append(" WHERE ").append(where)
                }
            }
        val ps = db().prepareStatement(sql)
        var idx = 1
        cols.forEach { ps.setObject(idx++, normalize(values.get(it))) }
        whereArgs?.forEach { ps.setObject(idx++, normalize(it)) }
        return ps.executeUpdate()
    }

    override fun delete(
        table: String,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int {
        val sql =
            buildString {
                append("DELETE FROM `$table`")
                if (!whereClause.isNullOrEmpty()) append(" WHERE ").append(whereClause)
            }
        val ps = db().prepareStatement(sql)
        whereArgs?.forEachIndexed { i, v -> ps.setObject(i + 1, normalize(v)) }
        return ps.executeUpdate()
    }

    override fun execSQL(sql: String) {
        db().createStatement().use { it.execute(sql) }
    }

    override fun execSQL(
        sql: String,
        bindArgs: Array<out Any?>?,
    ) {
        val ps = db().prepareStatement(sql)
        bindArgs?.forEachIndexed { i, v -> ps.setObject(i + 1, normalize(v)) }
        ps.execute()
        ps.close()
    }

    override fun getColumnNames(table: String): List<String> {
        db().createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").let { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out += rs.getString("name")
                return out
            }
        }
    }

    override fun doesTableExist(tableName: String): Boolean {
        db().createStatement().use { st ->
            st.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='${tableName.replace("'", "''")}' LIMIT 1")
                .let { rs -> return rs.next() }
        }
    }

    override fun doesIndexExist(
        tableName: String,
        indexName: String,
    ): Boolean {
        db().createStatement().use { st ->
            st.executeQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name='${indexName.replace("'", "''")}' LIMIT 1")
                .let { rs -> return rs.next() }
        }
    }

    override fun doesColumnExist(
        tableName: String,
        columnName: String,
    ): Boolean = getColumnNames(tableName).any { it.equals(columnName, ignoreCase = true) }

    override fun attach(
        fileLocation: String,
        dbName: String,
    ) {
        execSQL("ATTACH DATABASE '$fileLocation' AS `$dbName`")
    }

    override fun detach(dbName: String) {
        execSQL("DETACH DATABASE `$dbName`")
    }

    override fun close() {
        conn?.close()
        conn = null
    }

    companion object {
        const val CONFLICT_NONE = 0
        const val CONFLICT_REPLACE = 5
        const val CONFLICT_IGNORE = 4

        internal fun normalize(v: Any?): Any? =
            when (v) {
                is Boolean -> if (v) 1 else 0
                is ByteArray -> v
                else -> v
            }
    }
}

/** Prepared statement over JDBC implementing the Room/Android contract. */
class JdbcPreparedStatment(
    private val conn: Connection,
    private val sql: String,
) : androidx.sqlite.db.SupportSQLiteStatement {
    private val ps: java.sql.PreparedStatement = conn.prepareStatement(sql)
    private var paramIdx = 1

    override fun clearBindings() {
        ps.clearParameters()
        paramIdx = 1
    }

    override fun bindNull(index: Int) = ps.setObject(paramIdx++, null)

    override fun bindLong(index: Int,value: Long) = ps.setObject(paramIdx++, value)

    override fun bindDouble(index: Int,value: Double) = ps.setObject(paramIdx++, value)

    override fun bindString(index: Int,value: String?) = ps.setObject(paramIdx++, value)

    override fun bindBlob(index: Int,value: ByteArray) = ps.setBytes(paramIdx++, value)

    override fun executeSimple() {
        ps.execute()
    }

    override fun executeInsert(): Long {
        ps.executeUpdate()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").let { rs -> return if (rs.next()) rs.getLong(1) else -1L }
        }
    }

    override fun executeUpdateDelete(): Int = ps.executeUpdate()

    override fun simpleQueryForLong(): Long =
        ps.executeQuery().let { rs -> if (rs.next()) rs.getLong(1) else 0L }

    override fun simpleQueryForString(): String? =
        ps.executeQuery().let { rs -> if (rs.next()) rs.getString(1) else null }

    override fun close() {
        ps.close()
    }
}

/** Cursor adapter over a JDBC ResultSet. */
class JdbcCursor(
    private val rs: java.sql.ResultSet,
    private val ps: java.sql.PreparedStatement,
) : android.database.Cursor {
    private val meta = rs.metaData
    override val count: Int get() = -1
    override val columnCount: Int get() = meta.columnCount
    override var isClosed: Boolean = false
        private set

    override fun moveToFirst(): Boolean = rs.first()

    override fun moveToLast(): Boolean = rs.last()

    override fun moveToPosition(position: Int): Boolean = rs.absolute(position + 1)

    override fun move(count: Int): Boolean = rs.relative(count)

    override fun moveToNext(): Boolean = rs.next()

    override fun moveToPrevious(): Boolean = rs.previous()

    override fun isFirst(): Boolean = rs.isFirst

    override fun isLast(): Boolean = rs.isLast

    override fun isBeforeFirst(): Boolean = rs.isBeforeFirst

    override fun isAfterLast(): Boolean = rs.isAfterLast

    override fun getColumnIndex(columnName: String): Int {
        for (i in 1..columnCount) {
            if (meta.getColumnLabel(i).equals(columnName, ignoreCase = true)) return i - 1
        }
        return -1
    }

    override fun getColumnIndexOrThrow(columnName: String): Int =
        getColumnIndex(columnName).takeIf { it >= 0 } ?: throw IllegalArgumentException("No column $columnName")

    override fun getColumnName(columnIndex: Int): String = meta.getColumnLabel(columnIndex + 1)

    override fun getColumnNames(): Array<String> = (0 until columnCount).map { meta.getColumnLabel(it + 1) }.toTypedArray()

    override fun getString(columnIndex: Int): String? = rs.getString(columnIndex + 1)

    override fun getShort(columnIndex: Int): Short = rs.getShort(columnIndex + 1)

    override fun getInt(columnIndex: Int): Int = rs.getInt(columnIndex + 1)

    override fun getLong(columnIndex: Int): Long = rs.getLong(columnIndex + 1)

    override fun getFloat(columnIndex: Int): Float = rs.getFloat(columnIndex + 1)

    override fun getDouble(columnIndex: Int): Double = rs.getDouble(columnIndex + 1)

    override fun getType(columnIndex: Int): Int =
        when (meta.getColumnType(columnIndex + 1)) {
            java.sql.Types.BLOB -> FIELD_TYPE_BLOB
            java.sql.Types.FLOAT, java.sql.Types.DOUBLE, java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> FIELD_TYPE_FLOAT
            else -> FIELD_TYPE_STRING
        }

    override fun getBlob(columnIndex: Int): ByteArray = rs.getBytes(columnIndex + 1) ?: ByteArray(0)

    override fun isNull(columnIndex: Int): Boolean = rs.getObject(columnIndex + 1) == null

    override fun close() {
        isClosed = true
        runCatching { rs.close() }
        runCatching { ps.close() }
    }

    override fun copyStringToBuffer(columnIndex: Int,result: android.database.CharArrayBuffer?) {
        val s = getString(columnIndex)
        if (s != null && result != null) {
            val len = minOf(s.length, result.data.size)
            s.toCharArray(result.data, 0, 0, len)
            result.sizeCopied = len
        }
    }

    override fun deactivate() {}

    override fun reconfigure() {}

    override fun registerContentObserver(observer: android.database.ContentObserver?) {}

    override fun unregisterContentObserver(observer: android.database.ContentObserver?) {}

    override fun registerDataSetObserver(observer: android.database.DataSetObserver?) {}

    override fun unregisterDataSetObserver(observer: android.database.DataSetObserver?) {}

    override fun setNotificationUri(cr: android.content.ContentResolver?,uri: android.net.Uri?) {}

    override fun getNotificationUri(): android.net.Uri? = null

    override fun getWordingForColumnType(columnIndex: Int): String = meta.getColumnTypeName(columnIndex + 1)
}

/** androidx.sqlite open-helper implemented over JDBC. */
class JdbcSQLiteOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
) : SupportSQLiteOpenHelper {
    private var dbImpl: JdbcSupportDatabase? = null

    override val databaseName: String get() = configuration.name ?: ":memory:"

    override val writableDatabase: SupportSQLiteDatabase get() = getWritableDatabase()

    override val readableDatabase: SupportSQLiteDatabase get() = getReadableDatabase()

    override fun getWritableDatabase(): SupportSQLiteDatabase =
        dbImpl ?: synchronized(this) {
            dbImpl ?: JdbcSupportDatabase(path = configuration.name ?: createTempFileDb()).also {
                it.beginTransaction()
                try {
                    configuration.callback?.onCreate(it)
                    it.setTransactionSuccessful()
                } finally {
                    it.endTransaction()
                }
                dbImpl = it
                it
            }
        }

    override fun getReadableDatabase(): SupportSQLiteDatabase = getWritableDatabase()

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {}

    override fun resetReference() {}

    override fun close() {
        dbImpl?.close()
        dbImpl = null
    }

    override fun onConfigure(db: SupportSQLiteDatabase) {
        configuration.callback?.onConfigure(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        configuration.callback?.onOpen(db)
    }

    private fun createTempFileDb(): String =
        java.io.File.createTempFile("yuma", ".db").absolutePath

    object Factory : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            JdbcSQLiteOpenHelper(configuration)
    }
}

/** Room JVM provider installed into the shim bridge (see DesktopRoomBridge.kt). */
