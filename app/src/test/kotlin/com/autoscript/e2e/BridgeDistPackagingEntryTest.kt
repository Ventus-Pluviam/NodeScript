package com.autoscript.e2e

import com.autoscript.appservice.scriptrepo.core.BridgeDistDeploy
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 打包入口 `attachNative` 接线的**全链**验证（§12.4 资产交付轨）：
 * main.cpp 的 kBootstrap（从源文件**现抽** —— 单一事实源，不复制第二份引导串）
 * + BridgeDistDeploy 落位的真 dist + 系统 node 起进程，三件事串起来断言：
 * 1. `require('auto')` 解析得到（`filesDir/node_modules/auto/index.js`，Node 目录缺省入口）；
 * 2. `auto.bridge.installed == true`（kBootstrap 调了 `attachNative({addon})` →
 *    runtimeBridge 装上 handler —— 这正是"打包入口接线"本身）；
 * 3. addon 的 `setup` 被调（TSF 结算面先就位的装配顺序在真 require 下走通）。
 *
 * addon 用假 JS 模块冒充（`AUTOSCRIPT_BRIDGE_ADDON` 是 require 路径，系统 node 下
 * `.js` 即可）—— 本测证的是 **JS 消费面接线**，不是 NAPI；真 addon 全环归
 * `bridge/js` 的 `AUTOSCRIPT_TEST_ADDON` 门禁测。心跳的帧语义归 host 编译烟测，
 * 这里 rid 给 1 只求 kBootstrap 心跳行不炸（unref 定时器 + 脚本显式 exit，等不到 500ms）。
 */
class BridgeDistPackagingEntryTest {

    @TempDir
    lateinit var dir: Path

    private val fakeAddonJs = """
        module.exports = {
          setSocketFd(fd) { global.__fd = fd },
          setup(onFrame) { global.__frame = onFrame },
          invoke(ns, method, payload, reqId, ttl) { (global.__invokes || (global.__invokes = [])).push(ns + '.' + method) },
        };
    """.trimIndent()

    /** 断言用脚本：解析 / installed / setup 三关，任一不过给可检索的退出码与 stderr。 */
    private fun assertScript(): String = """
        const { auto } = require('auto');
        if (!auto || !auto.bridge) { console.error('FAIL: auto 根缺 bridge'); process.exit(2) }
        if (typeof global.__frame !== 'function') { console.error('FAIL: addon.setup 未被调用（attachNative 装配顺序没走通）'); process.exit(4) }
        if (!auto.bridge.installed) { console.error('FAIL: runtimeBridge 未安装（打包入口没调 attachNative）'); process.exit(3) }
        console.log('packaging-entry ok');
        process.exit(0);
    """.trimIndent()

    @Test
    fun `打包入口全链——kBootstrap 调 attachNative，require auto 即有桥`() {
        val bootstrap = repoBootstrap()
        assertTrue(bootstrap.contains("attachNative"), "kBootstrap 丢了 attachNative 接线（§12.4）——打包入口在源头被拆了")

        // 真 dist 落位（与生产同一条 BridgeDistDeploy）
        val files = dir.resolve("files")
        val report = BridgeDistDeploy(files, readBridgeDist()).run()
        assertTrue(report.changed && report.failures.isEmpty(), "dist 首落应成功：$report")
        val autoRoot = ScriptPaths.autoModuleRoot(files)
        assertTrue(Files.isRegularFile(autoRoot.resolve("bootstrap.js")))
        assertTrue(Files.isRegularFile(autoRoot.resolve("index.js")))

        val addon = dir.resolve("fake-addon.js")
        Files.write(addon, fakeAddonJs.toByteArray())

        val scriptDir = files.resolve("scripts").resolve("p1")
        Files.createDirectories(scriptDir)
        val script = scriptDir.resolve("entry.js")
        Files.write(script, assertScript().toByteArray())

        val (code, out, err) = runEntry(bootstrap, script, addon, bridgeDist = autoRoot)
        assertEquals(0, code, "exit=$code out=$out err=$err")
        assertTrue(out.contains("packaging-entry ok"), "out=$out err=$err")
        assertTrue(err.isEmpty(), "成功路径不该有 stderr 噪声：$err")
    }

    @Test
    fun `dist env 缺席——脚本照跑但桥如实未装(不静默冒充)`() {
        val bootstrap = repoBootstrap()
        val files = dir.resolve("files")
        BridgeDistDeploy(files, readBridgeDist()).run()   // dist 落了 —— 只摘 env（半接线形态）
        val addon = dir.resolve("fake-addon.js")
        Files.write(addon, fakeAddonJs.toByteArray())
        val scriptDir = files.resolve("scripts").resolve("p1")
        Files.createDirectories(scriptDir)
        val script = scriptDir.resolve("entry.js")
        Files.write(
            script,
            """
            const { auto } = require('auto');
            if (auto.bridge.installed) { console.error('FAIL: 没给 dist env 却装上了桥'); process.exit(5) }
            console.log('honestly-uninstalled');
            process.exit(0);
            """.trimIndent().toByteArray(),
        )

        val (code, out, err) = runEntry(bootstrap, script, addon, bridgeDist = null)
        assertEquals(0, code, "exit=$code out=$out err=$err")
        assertTrue(out.contains("honestly-uninstalled"), "out=$out")
    }

    @Test
    fun `attachNative 抛错——stderr 点名且脚本照跑(选填件不杀执行)`() {
        val bootstrap = repoBootstrap()
        val files = dir.resolve("files")
        BridgeDistDeploy(files, readBridgeDist()).run()
        // 生产里 env 指向的就是落位根 —— 把 bootstrap.js 换成坏的（模拟落了半截/坏资产）
        val autoRoot = ScriptPaths.autoModuleRoot(files)
        Files.write(autoRoot.resolve("bootstrap.js"), "require('no-such-module-xyz');".toByteArray())

        val addon = dir.resolve("fake-addon.js")
        Files.write(addon, fakeAddonJs.toByteArray())
        val scriptDir = files.resolve("scripts").resolve("p1")
        Files.createDirectories(scriptDir)
        val script = scriptDir.resolve("entry.js")
        Files.write(
            script,
            """
            const { auto } = require('auto');
            if (auto.bridge.installed) { console.error('FAIL: attach 失败却报已装'); process.exit(5) }
            console.log('script-survived');
            process.exit(0);
            """.trimIndent().toByteArray(),
        )

        val (code, out, err) = runEntry(bootstrap, script, addon, bridgeDist = autoRoot)
        assertEquals(0, code, "attach 失败不杀执行：exit=$code out=$out err=$err")
        assertTrue(out.contains("script-survived"), "out=$out")
        assertTrue(err.contains("attachNative 未接上"), "stderr 必须点名（不吞）：$err")
    }
}
