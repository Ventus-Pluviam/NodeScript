package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * §10.5-3「禁止静默」的扫描面：**哪些包的 lifecycle 脚本被 `--ignore-scripts` 跳掉了**。
 *
 * **为什么在 `InstallCoordinator` 之外**：这一段只认一个目录（暂存树）和 `package.json`，
 * 不认识安装会话、事务、锁 —— 而协调器那个类体已越过 detekt 的 `LargeClass` 线。
 * 与 [nodeModulesBytesOf]（`NodeModulesSizing.kt`）同一条外迁手法：这段本来就该有自己的
 * 位置，顺带也不必去抬阈值。
 *
 * ## 判据的唯一出处：素材 npm 自己的源码
 *
 * `NPM_CLI_VERSION` 钉的是 **npm 12.2.0**，本文件的判据逐条对到它的
 * `@npmcli/arborist/lib/arborist/rebuild.js`（不是文档转述，也不是凭印象）：
 *
 * - `#build()` 对**普通依赖**只 `#runScripts('preinstall' | 'install' | 'postinstall')`；
 *   `prepare` 那条被圈在 `if (type === 'links')` 里 —— 即 `npm install <folder>` 的
 *   软链依赖，registry 装来的包根本走不到；
 * - `#addToBuildSet()` 里有一条**看不见于 package.json** 的自动注入：
 *   `gypfile !== false && !install && !preinstall && await isNodeGypPackage(path)`
 *   为真时 npm 自己补 `scripts.install = 'node-gyp rebuild'`；
 * - `uninstall` 系（`preuninstall`/`uninstall`/`postuninstall`）**npm v7 起不实现**
 *   （官方 scripts.md：「not implemented and will not function」）；
 * - `prepack`/`postpack`/`prepublishOnly` 属 `npm pack`/`npm publish`，
 *   `preprepare`/`prepare`/`postprepare` 属**项目自身**的裸 `npm install`/`npm ci` ——
 *   都不是「装一个依赖」这条路上会跑的事件。
 *
 * 换 `NPM_CLI_VERSION` 时必须回来对这两段（与 §10.1「版本钉死 + 全链回归」同一条纪律）。
 *
 * ## 修掉的两类失真（2026-11-09 真机：装 axios 报「安装脚本未运行：axios」）
 *
 * 1. **虚警**：原扫描面是九个键的全文子串匹配（`"\"install\"" in text`），于是
 *    `prepare`（registry 装 axios 时**本来就不会跑**，它只有 `prepare: husky`）、
 *    `uninstall` 系、以及**根本不是 npm lifecycle 名**的 `preparePack`（12.2.0 源码里
 *    零命中）都会点名进警告。用户照这句去查一个不存在的构建步骤，查不出来 ——
 *    「禁止静默」的反面不是「多说几条」，是**说的每条都得是真的**。全文子串还会命中
 *    `bin` 里名叫 `install` 的命令、`devDependencies` 里名叫 `install` 的包。
 * 2. **漏报**：原来只扫深度 1，`@scope/` 那一层被当成一个包（`@scope/package.json`
 *    恒不存在）→ **所有带 scope 的包从来没被扫过**，漏掉的正好是
 *    `@parcel/watcher` 这类必带 `postinstall` 的原生包。
 *
 * 两条都在 [SkippedLifecycleScanTest] 里各有一根钉。
 */

/** npm 对**依赖**真会跑的那三个 lifecycle 事件（见文件头：`rebuild.js#build()`）。 */
private val lifecycleEvents = listOf("preinstall", "install", "postinstall")

/** 关掉 node-gyp 自动探测的那个字段（`gypfile: false`）。 */
private const val GYPFILE_KEY = "gypfile"

/** node-gyp 包的标记文件：有它且没声明 `install`/`preinstall` → npm 自动注入一条。 */
private const val BINDING_GYP = "binding.gyp"

/** 暂存树里 npm 自己的记账，不是包（`.bin`、`.package-lock.json`、嵌套 `node_modules`）。 */
private const val NESTED_TREE = "node_modules"

/**
 * 扫暂存树，返回**被跳掉了 lifecycle 脚本的顶层包名**（排序后；空 = 没有，不发警告）。
 *
 * 只读顶层（深度 1 的 `node_modules/＊`，`@scope/` 再展开一层）—— 传递依赖同属这些包
 * 自己的声明，按顶层包汇总即可覆盖；嵌套树（`a/node_modules/b`）算在最外层那个包头上，
 * 与 `NodeModulesSizing.kt` 的 `topLevelPkgOf` 同一条口径。
 *
 * 名字用 `@scope/pkg` 形态（与 lock v3 的键同一口径，UI 拿去渲染不用再拼）。
 *
 * **不抛**：这是一条信息面警告，读不出某个包的清单不该让一次已经跑完的安装失败；
 * 而那棵树是 npm 自己装出来的，清单坏了 npm 早就红了。
 */
internal fun skippedLifecyclePkgs(stageDir: Path): List<String> {
    if (!Files.isDirectory(stageDir)) return emptyList()
    val marked = ArrayList<String>()
    Files.list(stageDir).use { entries ->
        entries.filter { Files.isDirectory(it) && isPackageDirName(it.fileName.toString()) }
            .forEach { dir -> collectFromTopLevel(dir, marked) }
    }
    return marked.sorted()
}

/** 顶层那一格要不要扫：npm 的记账（点开头）与嵌套树都不是包。 */
private fun isPackageDirName(name: String): Boolean = !name.startsWith(".") && name != NESTED_TREE

/**
 * 一个顶层条目的收集：普通包直接判；`@scope/` 目录**再下一层**才是包
 * （原实现把 `@scope` 当包，于是带 scope 的包一个都扫不到 —— 见文件头第 2 条）。
 */
private fun collectFromTopLevel(dir: Path, out: MutableList<String>) {
    val name = dir.fileName.toString()
    if (!name.startsWith("@")) {
        if (hasSkippedLifecycleScript(dir)) out += name
        return
    }
    Files.list(dir).use { inner ->
        inner.filter { Files.isDirectory(it) && isPackageDirName(it.fileName.toString()) }
            .forEach { scoped ->
                if (hasSkippedLifecycleScript(scoped)) out += "$name/${scoped.fileName}"
            }
    }
}

/**
 * 这个包在 `--ignore-scripts` 下有没有**真被跳掉**的 lifecycle 脚本。
 *
 * **必须解析 `scripts` 段**（不能做全文子串匹配，理由见文件头第 1 条）：
 * 1. `scripts` 里声明了 [lifecycleEvents] 之一；
 * 2. 或有 node-gyp 的自动注入那条（`binding.gyp` 在场 + 没声明 `install`/`preinstall`
 *    + 没写 `gypfile: false`）。
 */
private fun hasSkippedLifecycleScript(pkgDir: Path): Boolean {
    val pkgJson = pkgDir.resolve("package.json")
    if (!Files.isRegularFile(pkgJson)) return false
    val manifest = readManifestOrNull(pkgJson) ?: return false
    val scripts = (manifest["scripts"] as? DomainJson.Value.Obj)?.fields.orEmpty()
    if (lifecycleEvents.any { it in scripts }) return true
    val gypfileOff = (manifest[GYPFILE_KEY] as? DomainJson.Value.B)?.v == false
    return !gypfileOff &&
        "install" !in scripts &&
        "preinstall" !in scripts &&
        Files.isRegularFile(pkgDir.resolve(BINDING_GYP))
}

private fun readManifestOrNull(pkgJson: Path): Map<String, DomainJson.Value>? = runCatching {
    DomainJson.decodeObject(String(Files.readAllBytes(pkgJson), StandardCharsets.UTF_8))
}.getOrNull()
