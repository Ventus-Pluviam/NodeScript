package com.autoscript.appservice.npm

import java.io.IOException
import java.io.InputStream

/**
 * [NpmCliDeployer.CliSource] 的「资产树」实现（§10.2 调用链首段的**生产源**）。
 *
 * 吃 list/open 两个函数而不是 `AssetManager` 本体 —— 与 `:app-service:script-repo` 的
 * `AssetsWalk` 同一条理由：JVM 单测里造不出真的 AssetManager 实例（android.jar 是可 mock
 * 的桩，方法一调就抛），混进本体就得连坐"不可单测"。生产装配是两行，住 `:app` 装配层：
 * ```
 * AssetTreeCliSource("npm", { assets.list(it) }, { assets.open("npm-manifest.json") }) { assets.open(it) }
 * ```
 * 资产键 = `npm/<rel>`（`app/build.gradle.kts` 的 `prepareNpmCliAssets` 产出形状），
 * 故 [root] 传 `"npm"`。
 *
 * 与 [NpmCliDeployer] 的分工：这里只回答"源树里有哪些文件、某文件的字节是什么"。
 * 枚举是**惰性**的（[list] 只走名字、[read] 一次开一个文件）：npm 素材 ~1.5k 文件 /
 * 近 10MB，一次性读成 Map 会在低端机上多压一份常驻内存；而部署侧本来就是逐文件写盘
 * （`deploy` 的 tmp + 逐文件 sha256 校验），根本没有"全量在内存"的需要。
 *
 * 目录判定沿用 `AssetsWalk` 的两条经验：子目录名**可能带尾 '/'**（部分 ROM 的 list 行为），
 * 不带时以「list 出内容」兜底。
 *
 * @param root assets 内的素材根（生产 = `"npm"`）。
 * @param listDir 列目录：返当前层条目（目录名可能带尾 '/'）；目录不存在返 null。
 * @param openManifest 打包前清单读口；不提供或不可读时部署拒绝。
 * @param openFile 开文件流：由本类负责关闭。
 */
class AssetTreeCliSource(
    private val root: String,
    private val listDir: (String) -> Array<String>?,
    private val openManifest: (() -> InputStream?)? = null,
    private val openFile: (String) -> InputStream,
) : NpmCliDeployer.CliSource {

    override fun manifest(): String? = try {
        openManifest?.invoke()?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: IOException) {
        null
    }

    override fun list(): List<String> {
        val out = ArrayList<String>()
        // 判目录时已经读到的子项直接入队；同一趟遍历里不再为了展开目录而 list 第二次。
        // 只留名字、不读文件内容，且缓存仅限本次调用，下次检疫仍现查整棵 APK 树。
        val queue = ArrayDeque<Pair<String, Array<String>>>()
        queue.addLast("" to (listDir(root) ?: return out))
        while (queue.isNotEmpty()) {
            val (rel, names) = queue.removeFirst()
            for (raw in names) {
                if (raw.isBlank()) continue
                // 目录名可能带尾 '/'：入队前剥掉，保证 list/open 收到的路径形态一致
                val bySuffix = raw.endsWith("/")
                val name = raw.trimEnd('/')
                if (name.isEmpty()) continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                val childNames = listDir("$root/$childRel")
                if (bySuffix || !childNames.isNullOrEmpty()) {
                    queue.addLast(childRel to childNames.orEmpty())
                } else {
                    out.add(childRel)
                }
            }
        }
        return out
    }

    override fun read(relPath: String): ByteArray? = try {
        openFile("$root/$relPath").use { it.readBytes() }
    } catch (_: IOException) {
        // 素材缺失（AssetManager.open 对不存在路径抛 FileNotFoundException）→ null，
        // 交部署侧按缺件处理（锚文件缺失会当场抛错，不静默）。0 字节文件是**合法内容**，
        // 走不到这里 —— 它返回空数组。
        null
    }
}
