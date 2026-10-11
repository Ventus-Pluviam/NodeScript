package com.autoscript.platform.system.datastore

/**
 * kv 库的**升级路径表**（2026-10-11 批 97，外审 M8）。
 *
 * ## 为什么把它从 [SqliteKvOps] 里抽成纯函数
 *
 * 原 `onUpgrade` 是「版本一升就 `DROP TABLE` + 重建」—— 脚本可再生的数据说丢就丢，
 * 而用户看到的是「装了个新版本，依赖面板里的东西全没了」。它还**没有 `onDowngrade`**
 * （`SQLiteOpenHelper` 的缺省实现抛异常，但那是平台默认，不是一条被审过的口径）。
 *
 * 抽成纯函数的两个理由：
 * 1. **可单测**：`:platform:system` 的 JVM 单测没有 Robolectric，Android 面
 *    （[SqliteKvOps]）本机只编译不执行 —— 升级策略住在 Android 类里就永远只能靠人读。
 * 2. **口径写在数据里而不是在注释里**：`upgradeSteps` 是「从哪个版本升到哪个版本、
 *    按什么顺序执行哪几条 SQL」的显式表。新加一列时在这张表里加一行；
 *    **没有对应行 = 响亮失败**，绝不「顺手重建」。
 *
 * ## 纪律：宁可不迁移，不可静默丢数据
 *
 * 找不到路径时抛 [UnknownMigration]，让 `SQLiteOpenHelper` 的升级流程整体失败 ——
 * 现场是「应用启动报错 + 崩溃栈里有这句」，而不是「数据莫名其妙没了」。这条与
 * §8.5 意图日志那条「打不开不回落、异常直接冒到装配」的纪律同源（批 78 裁定）。
 */
internal object KvSchemaMigrations {

    /** 一段升版 SQL（按序执行；事务由调用方 [SqliteKvOps] 的 helper 包住）。 */
    data class Step(val sql: String)

    /** 找不到 old → new 的路径。**不是**「清空重建」的信号。 */
    class UnknownMigration(val from: Int, val to: Int) : IllegalStateException(
        "kv 库无 $from → $to 的迁移路径：拒绝静默重建（那会丢脚本数据）。" +
            "确需清空时显式调 clear() 或手动删库，别把丢数据写成升级策略",
    )

    /** 从 [from] 升到 [to] 的步骤序列（空 = 无需动 schema）。找不到路径抛 [UnknownMigration]。 */
    fun upgradeSteps(from: Int, to: Int): List<Step> {
        if (from == to) return emptyList()
        if (from > to) {
            error("kv 库版本回退 $from → $to：拒绝降级（降级读到的列可能不存在）。删库重建是显式动作，不是自动路径")
        }
        var version = from
        val steps = mutableListOf<Step>()
        while (version < to) {
            val step = MIGRATIONS[version]
                ?: throw UnknownMigration(version, to)
            steps += step
            version++
        }
        return steps
    }

    /** 版本 v → v+1 的那一步；键是**旧**版本号。空表 = 当前没有任何迁移（v1 是初始版）。 */
    private val MIGRATIONS: Map<Int, Step> = emptyMap()
}
