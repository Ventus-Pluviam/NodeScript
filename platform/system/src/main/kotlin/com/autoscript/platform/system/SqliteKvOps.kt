package com.autoscript.platform.system

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.autoscript.domain.storage.StoredEntry

/**
 * [AndroidDataStore.KvOps] 的真机实现（docs §9.6）：唯一碰 `SQLiteOpenHelper`/
 * SQL 的地方。发号语义、暂存、IO 调度都在宿主 [AndroidDataStore] 里，这里只有
 * 「单键读写」与「事务三拍的原子批」。
 *
 * - 单表 `kv(key PRIMARY KEY, kind, value)`；`value` 无类型列（BLOB 亲和 = 原样存），
 *   文本/字节由显式 `kind` 列裁定（见 [KvRowCodec] —— 不靠 `typeof()` 猜）；
 * - [applyAll] = `beginTransaction` → 逐笔 → `setTransactionSuccessful` → `endTransaction`
 *   （finally 兜底：中途异常也一定 end，否则连接挂着未结事务，后续写全卡死）；
 * - 本机 JVM **只编译不执行**（Android stub 运行期抛异常；测试站 [AndroidDataStore]
 *   的缝这边，见 README ops 表）。
 *
 * 连接生命周期：`SQLiteOpenHelper` 持应用级单例库；[close] 随壳收口时由装配层调
 * （生产拼装仍待拓扑决策 —— 这里先提供收口入口，不自作主张在构造点注册钩子）。
 */
class SqliteKvOps(context: Context) : AndroidDataStore.KvOps, AutoCloseable {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_SQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // P0 单版本无迁移路径：升级即重建。kv 是脚本可再生数据，干净重建比
            // 留一个半迁移状态好解释（半迁移 = 读出来 kind 对不上、写进去的又混型）。
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    override fun put(key: String, value: StoredEntry) {
        val cv = ContentValues().apply {
            put(COL_KEY, key)
            put(COL_KIND, KvRowCodec.kindOf(value))
            when (value) {
                is StoredEntry.Json -> put(COL_VALUE, value.text)
                is StoredEntry.Bytes -> put(COL_VALUE, value.bytes)
            }
        }
        helper.writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun get(key: String): StoredEntry? =
        helper.readableDatabase
            .query(TABLE, arrayOf(COL_KIND, COL_VALUE), "$COL_KEY=?", arrayOf(key), null, null, null)
            .use { c ->
                if (!c.moveToFirst()) return null
                val kind = c.getString(0)
                KvRowCodec.decode(
                    kind,
                    text = { if (c.isNull(1)) null else c.getString(1) },
                    blob = { if (c.isNull(1)) null else c.getBlob(1) },
                )
            }

    override fun remove(key: String): Boolean =
        helper.writableDatabase.delete(TABLE, "$COL_KEY=?", arrayOf(key)) > 0

    override fun contains(key: String): Boolean =
        helper.readableDatabase
            .query(TABLE, arrayOf(COL_KEY), "$COL_KEY=?", arrayOf(key), null, null, null)
            .use { it.moveToFirst() }

    override fun keys(): List<String> =
        helper.readableDatabase
            .query(TABLE, arrayOf(COL_KEY), null, null, null, null, null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(c.getString(0))
                }
            }

    override fun clear() {
        helper.writableDatabase.delete(TABLE, null, null)
    }

    override fun applyAll(ops: List<AndroidDataStore.KvMutation>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (op in ops) {
                when (op) {
                    // 同一连接（SQLiteOpenHelper 缓存单例 db）：事务内直写即可。
                    is AndroidDataStore.KvMutation.Put -> put(op.key, op.value)
                    is AndroidDataStore.KvMutation.Remove -> db.delete(TABLE, "$COL_KEY=?", arrayOf(op.key))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        helper.close()
    }

    companion object {
        private const val DB_NAME = "autoscript-kv.db"
        private const val DB_VERSION = 1
        private const val TABLE = "kv"
        private const val COL_KEY = "key"
        private const val COL_KIND = "kind"
        private const val COL_VALUE = "value"

        // value 不声明类型 = BLOB 亲和（原样存，文本进文本、字节进字节）；
        // 归属由 kind 列裁定，见 KvRowCodec。
        private val CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_KEY TEXT PRIMARY KEY NOT NULL,
                $COL_KIND TEXT NOT NULL,
                $COL_VALUE
            )
        """.trimIndent()
    }
}
