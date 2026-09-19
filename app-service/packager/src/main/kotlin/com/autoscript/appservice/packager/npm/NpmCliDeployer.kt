package com.autoscript.appservice.packager.npm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * vendored npm CLI 部署器（docs/framework-design.md §10.2 存储布局 · `files/npm/`）：
 * assets 里的 npm CLI（~8–9MB）**原子部署**到 filesDir（tmp 写逐文件 + 全量 sha256 校验 + rename）。
 *
 * 幂等（防每次开机重复解 9MB）：目标 `bin/npm-cli.js` 旁写 `.cli-manifest.sha256`
 * 锚文件，内容 = 源侧 npm-cli.js 的 sha256；已存在且匹配即整目录跳过。
 * 升级换版本 = 新哈希 ≠ 锚 → 重部署（版本钉在 node-runtime-build 侧，与主链同轨）。
 *
 * 与 [InstallStaging] 的 install journal 同源但正交：本事务管「CLI 自身就位」；
 * 失败/半途的 tmp 目录由下次 [deploy] 开头的 stale 清扫兜底（调用方无感重试）。
 *
 * 素材源为接缝（[CliSource]）：Android 侧 `assets.list/open("npm/…")` 读 APK 资产；
 * 测试/桌面侧文件系统目录树直读。本模块零 android.*（archUnit 守护）。
 */
object NpmCliDeployer {

    /** 部署后必须存在的锚文件：缺一票否决——防「assets 只跟了一半」的半瘫 CLI。 */
    private val ANCHORS = listOf(
        "bin/npm-cli.js",
        "bin/npx-cli.js",
    )

    /** 素材源：按相对路径取字节 + 可枚举源树全部常规文件 relPath。 */
    interface CliSource {
        fun read(relPath: String): ByteArray?

        /** 枚举源树（assets 侧 = list 递归；目录源 = Files.walk）。 */
        fun list(): List<String>
    }

    /** 部署结果：Ready(cliJs, deployedFresh=false 表示幂等命中）。 */
    sealed interface Outcome {
        data class Ready(val cliJs: Path, val deployedFresh: Boolean) : Outcome
    }

    /** CLI 落位根（`files/npm/`）。 */
    fun npmRoot(filesDir: Path): Path = filesDir.resolve("npm")

    /** `node <filesDir>/npm/bin/npm-cli.js` 的可执行入口（§10.2 调用链末段）。 */
    fun cliJsPath(filesDir: Path): Path = npmRoot(filesDir).resolve("bin/npm-cli.js")

    /**
     * 确保 vendored CLI 就位：枚举源 → 幂等闸 → tmp 全量写 + 逐文件 sha256 校验 → 原子 rename。
     *
     * @return Ready；deployedFresh=false 表示幂等命中——调用方零成本复用上次落盘。
     * @throws IllegalStateException 源树缺 [ANCHORS] 锚文件（检疫：宁可诚实失败也不降级系统 npm）。
     */
    fun deploy(filesDir: Path, source: CliSource): Outcome {
        Files.createDirectories(filesDir)
        val target = npmRoot(filesDir)
        val cli = target.resolve("bin/npm-cli.js")
        val manifest = target.resolve(".cli-manifest.sha256")

        val rels = source.list()
        val cliHash = rels.firstOrNull { it == "bin/npm-cli.js" }
            ?.let { source.read(it) }
            ?.let { DirSizer.sha256(it) }
            ?: throw IllegalStateException("vendored npm CLI 素材缺失：bin/npm-cli.js（assets/npm/ 未随包分发？）")
        val missingAnchors = ANCHORS.filter { it !in rels.toSet() }
        require(missingAnchors.isEmpty()) { "vendored npm CLI 素材缺锚文件：$missingAnchors（防半瘫 CLI 部署）" }

        // 幂等闸：锚哈希匹配 → 整目录跳过（开机路径零 IO）
        if (Files.isRegularFile(cli) && Files.exists(manifest) &&
            Files.readString(manifest, StandardCharsets.UTF_8).trim() == cliHash
        ) {
            return Outcome.Ready(cli, deployedFresh = false)
        }

        // stale 清扫：上次半途 tmp 目录 + 墓碑
        Files.list(filesDir).use { s ->
            s.filter {
                val n = it.fileName.toString()
                n.startsWith(".npm-deploy-") || n.startsWith(".npm-tombstone-")
            }.forEach { it.toFile().deleteRecursively() }
        }

        val tmp = Files.createDirectories(
            filesDir.resolve(".npm-deploy-" + System.nanoTime().toString(36)),
        )
        try {
            // 全量写入 tmp（先写内容 + 期望哈希登记，校验通过才允许 rename）
            val expect = LinkedHashMap<String, String>()
            for (rel in rels) {
                val bytes = source.read(rel) ?: continue
                val dest = tmp.resolve(rel)
                Files.createDirectories(dest.parent)
                Files.write(dest, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                expect[rel] = DirSizer.sha256(bytes)
            }
            require(expect.isNotEmpty()) { "vendored npm CLI 源树为空（$rels）" }
            // 逐文件磁盘哈希复核（rename 前零半截）
            for ((rel, want) in expect) {
                val got = DirSizer.sha256(Files.readAllBytes(tmp.resolve(rel)))
                require(got == want) { "vendored CLI 部署校验失败：$rel（磁盘哈希不匹配）" }
            }
            // 整体就位：旧目录移墓碑再 rename（失败滚回墓碑，不让 CLI 消失）
            if (Files.isDirectory(target)) {
                val tomb = filesDir.resolve(".npm-tombstone-" + System.nanoTime().toString(36))
                Files.move(target, tomb, StandardCopyOption.ATOMIC_MOVE)
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    Files.move(tomb, target, StandardCopyOption.ATOMIC_MOVE)
                    throw e
                }
                tomb.toFile().deleteRecursively()
            } else {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            }
            Files.write(manifest, cliHash.toByteArray(StandardCharsets.UTF_8))
            return Outcome.Ready(cli, deployedFresh = true)
        } finally {
            tmp.toFile().deleteRecursively()   // 失败路径清残骸（成功路径 tmp 已 rename 走）
        }
    }
}

