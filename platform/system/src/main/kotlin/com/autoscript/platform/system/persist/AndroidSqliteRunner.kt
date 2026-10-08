package com.autoscript.platform.system.persist

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * [SqliteIntentStore.Runner] 的真机实现：唯一碰 `SQLiteOpenHelper`/游标的地方
 * （与 `datastore/SqliteKvOps` 同一条分层纪律 —— Android 触点收在一个缝后面，
 * 语义与 SQL 文本都在纯 JVM 可测的那一侧）。
 *
 * **崩溃持久（§8.5）**：开库即 `PRAGMA synchronous=FULL`。
 * 为什么不用默认值：Android 的默认 journal 模式是 `PERSIST`（非 WAL），而 `synchronous`
 * 跟随系统属性（低内存设备上常被置成 `NORMAL`/`OFF`）—— 那两档在**断电**（不是进程被杀）
 * 时会丢最近已提交的事务，而意图日志正是为「手机重启」准备的。代价是每次提交多一次
 * fsync：意图日志每次 run 只两条行（设计已记「调度写放大，P0 可接受」），照单全收。
 *
 * **为什么不自己开 WAL**：WAL 下 `SQLiteOpenHelper` 的连接池会真的开出多条连接，而
 * `PRAGMA synchronous` 是**每连接**的、只在执行它的那条上生效 —— 想在整个连接池上保证
 * FULL 就得同时钉 `journal_mode` 与连接池配置。非 WAL 模式下池里只有一条连接，一句
 * pragma 就是全库口径。选简单且可证明的那条。
 *
 * **单写者**：意图日志的写者只有 `:main` 的单例调度器（§8.5）—— 本类不额外加锁，
 * 靠 `SQLiteDatabase` 自身的串行化与 `ux_nonce` 的原子拒绝兜底。**锚点由引擎强制**：
 * 即便真有第二个写者进来，唯一索引照样拒绝（已退役的 jsonl 存储则不同 —— 它的
 * 「锁内先查后写」只在单写者前提下成立）。
 *
 * 连接生命周期：`SQLiteOpenHelper` 持应用级单例库；[close] 随壳收口时由装配层调
 * （同 `SqliteKvOps` 的收口入口形状）。
 */
class AndroidSqliteRunner(context: Context) : SqliteIntentStore.Runner {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            // 建表语句逐条 execSQL：Android 的 execSQL 只跑第一条语句（SQLiteStatement 只
            // prepare 一次），整段 DDL 一把塞进去会静默只建表、不建索引 —— 而索引正是
            // 幂等锚点，缺了它「同 nonce 拒绝」会退化成「来者不拒」。
            IntentStoreSql.SCHEMA.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // P0 单版本无迁移路径：升级即重建。与 `SqliteKvOps` 同一条口径 —— 意图日志是
            // 调度状态，干净重建比留一个半迁移状态好解释；真需要跨版本保数据时，迁移入口是
            // [SqliteIntentStore.importRows]（显式、带原 runId、幂等）。
            db.execSQL("DROP TABLE IF EXISTS ${IntentStoreSql.TABLE}")
            onCreate(db)
        }
    }

    /**
     * 单事务跑完整批：逐 op 先 DML 后紧跟它的 SELECT（`last_insert_rowid()`/`changes()`
     * 是**连接级**状态，只有紧跟着才读得到自己那条语句的结果），返回与 [ops] 同序同长的
     * 结果表。`setTransactionSuccessful` 之前任何一步抛错 → 整批回滚（`finally` 兜底
     * `endTransaction`，否则连接挂着未结事务、后续写全卡死）。
     */
    override fun run(ops: List<SqlOp>): List<List<Map<String, Any?>>> {
        val db = helper.writableDatabase
        IntentStoreSql.OPEN_PRAGMAS.forEach(db::execSQL)   // 崩溃持久口径，开库后立刻钉住（幂等）
        db.beginTransaction()
        return try {
            val out = ops.map { op ->
                for (sql in op.dml) db.execSQL(sql)
                queryOn(db, op.query)
            }
            db.setTransactionSuccessful()
            out
        } catch (e: SQLiteConstraintException) {
            throw e.asConstraintViolation()
        } finally {
            db.endTransaction()
        }
    }

    override fun ddl(statements: List<String>) {
        val db = helper.writableDatabase
        IntentStoreSql.OPEN_PRAGMAS.forEach(db::execSQL)
        for (sql in statements) db.execSQL(sql)
    }

    override fun query(sql: String): List<Map<String, Any?>> = queryOn(helper.readableDatabase, sql)

    override fun close() {
        helper.close()
    }

    private fun queryOn(db: SQLiteDatabase, sql: String): List<Map<String, Any?>> =
        db.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) add(c.toRow())
            }
        }

    /**
     * 游标一行 → 「列名 → 值」。类型按 `getType` 显式分派（不靠 `isNull` 猜）：
     * 列的类型是建表时写下的契约，读侧照契约取值，遇到契约外的类型**响亮失败**
     * （库被外部工具改过 / 半截写）比静默给个脏值好。
     */
    private fun Cursor.toRow(): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>(columnCount)
        for (i in 0 until columnCount) {
            row[getColumnName(i)] = when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_STRING -> getString(i)
                Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                else -> error(
                    "intent_log 列 ${getColumnName(i)} 类型越界（type=${getType(i)}）—— 库被外部改过？",
                )
            }
        }
        return row
    }

    companion object {
        /** 与 `datastore` 的 kv 库分开一个文件：两张表的升级路径不同（kv 可丢、意图日志不可）。 */
        private const val DB_NAME = "autoscript-intent-log.db"
        private const val DB_VERSION = 1
    }
}

/** `android.database.sqlite.SQLiteConstraintException` → 本层的 [ConstraintViolationException]。 */
internal fun SQLiteConstraintException.asConstraintViolation(): ConstraintViolationException =
    ConstraintViolationException(message ?: "SQLite 约束冲突", this)
