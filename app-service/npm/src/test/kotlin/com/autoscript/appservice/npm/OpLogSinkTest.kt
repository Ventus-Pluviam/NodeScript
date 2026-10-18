package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 操作事实那条去向的取值锚（`OpLogSink` / `OpName`）。
 *
 * 为什么值得一测：这些字面量**是用户在系统日志里看到的字**（管理面板 → 日志管理 →
 * 系统日志段），而它们原先住在 `:domain` 的 `InstallHistoryOp` 上、由契约测试钉着
 * —— 2026-10-17「去掉审计」把那本账与那页一起去掉，取值锚跟着搬进本模块
 * （唯一的消费方），锚不能跟着页面一起消失：改了字面量等于改了日志里的可检索词，
 * 而没有任何东西会因此报错。
 */
class OpLogSinkTest {

    @Test
    fun `已知操作名逐字钉住（日志里的可检索词）`() {
        assertEquals(
            listOf("install", "ci", "uninstall", "prune", "dedupe", "registry", "import", "export"),
            listOf(
                OpName.INSTALL, OpName.CI, OpName.UNINSTALL,
                OpName.PRUNE, OpName.DEDUPE, OpName.REGISTRY,
                OpName.IMPORT, OpName.EXPORT,
            ),
        )
        // 跑出来的取值：opName(args) 直取 argv 首词；T1 两条由 runScript/exec 直接给常量。
        assertEquals("ls", OpName.LS)
        assertEquals("audit", OpName.AUDIT)
        assertEquals("update", OpName.UPDATE)
        assertEquals("run_script", OpName.RUN_SCRIPT)
        assertEquals("exec", OpName.EXEC)
        assertEquals("cache_reclaim", OpName.CACHE_RECLAIM, "不许叫 cache_clean：那不是 npm 的 cache clean")
    }

    @Test
    fun `缺省去向是空实现：未接线时不记也不抛`() {
        // 不抛是本缝的硬约束：诊断面把一次本来能跑完的安装变成失败是本末倒置。
        OpLogSink.None.log(OpName.INSTALL, "p1", success = false, detail = "随便什么原因")
        assertTrue(OpLogSink.None is OpLogSink)
    }
}
