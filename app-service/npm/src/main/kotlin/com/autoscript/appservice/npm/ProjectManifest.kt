package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson

/**
 * 项目 `package.json`：文件名、缺省骨架、以及 npm 那套**包名判据**的落点。
 *
 * **为什么要造一份骨架**（2026-10-17 用户口径：「应该让它缺的时候自动创建呀」）：
 * 重操作的执行体（[HostNodeExecutor.prepareWorkDir]）要把项目根那份 `package.json` 拷进
 * workDir 当 prefix（npm 的 `--prefix` 只认那里），跑完再把 npm 重写过的那份 harvest 回
 * 项目根 —— **没有它这一步就没法开始**，而项目此前只有**模板部署**才长得出它：
 * 「首页根层建文件夹 = 建项目」（2026-10-16 用户裁定）造出来的是一**空目录**，
 * 于是"自己建的项目装不了依赖"，且报的是 `项目 demo 缺 package.json（…）` 这种
 * 既不是用户的错、用户也无从下手的一句。
 *
 * **缺省不写 `main`**（刻意，别顺手补上）：`ProjectIndex` 拿 `main` 定位入口、缺了退到
 * `main.js`/`index.js`（见 `ProjectIndex.resolveMain`）。不写它，就等于"这份自动生成的
 * 清单对入口判断**零影响**"—— 用户原有的脚本照旧按老判据找得到。写了反而要在我们
 * 并不知道入口是哪个的情况下给一个答案。
 *
 * **`private: true`**：这是应用托管的项目目录，不是要发布的包。写死 true，
 * 免得哪天一句 `npm publish` 真把用户的源码推上去。
 */
internal object ProjectManifest {

    /** 文件名（[NpmProjectLayout] 的布局与 [HostNodeExecutor] 共用这一个字面量）。 */
    const val FILE_NAME: String = "package.json"

    /** npm 包名的字符上限（`validate-npm-package-name` 的数）。 */
    private const val MAX_NAME_CHARS: Int = 214

    /** 名字一个合法字符都留不下时的兜底（中文/纯符号的项目号会走到这里）。 */
    private const val FALLBACK_NAME: String = "autoscript-project"

    /** 折出来的连续 `-` 合并成一档（`a b` 与 `a  b` 该是同一个名字）。 */
    private val DASH_RUN = Regex("-+")

    /**
     * 缺省清单的正文（末尾带换行：`Files.write` 出来的文件该像个文本文件）。
     *
     * 用 [DomainJson.encode] 而不是手搓字符串：名字是我们自己洗过的（只剩 `[a-z0-9-]`），
     * 但"洗过"这件事哪天松了，手搓的 JSON 会**静默**造出一份坏清单 —— 交给编码器兜底，
     * 它至少会把引号/反斜杠转义对。
     */
    fun minimal(projectId: String): String = DomainJson.encode(
        linkedMapOf(
            "name" to npmNameOf(projectId),
            "version" to "1.0.0",
            "private" to true,
        ),
    ) + "\n"

    /**
     * 项目号 → 一个 npm **认**的包名。
     *
     * 为什么非洗不可：项目号判据（`ScriptPaths.PROJECT_ID`）2026-10-16 放宽成
     * "拒分隔符与点"，**中文、大写、空格、`@` 都合法**；而 npm 的包名只认小写
     * URL-safe 字符，撞上不合法的名字它会直接 `EINVALIDPACKAGENAME` 退出来 ——
     * 那等于把"缺清单"换成"清单里名字不合法"，用户照样装不上，且更难看出病因。
     *
     * 洗法（保守到只剩一档字符集）：小写 → 非 `[a-z0-9-]` 一律折成 `-` → 合并连续 `-`
     * → 掐头去尾的 `-` → 截到 [MAX_NAME_CHARS] → 空则回 [FALLBACK_NAME]。
     *
     * 名字在这里**只有展示意义**（`private` 项目：不发布、不参与依赖解析、也没人 import 它），
     * 所以"中文项目号洗成 `autoscript-project`"是可接受的代价 —— 数据仍在项目号上，
     * 本函数不改任何寻址。
     */
    fun npmNameOf(projectId: String): String = projectId.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '-' }
        .joinToString("")
        .replace(DASH_RUN, "-")
        .trim('-')
        .take(MAX_NAME_CHARS)
        .trim('-')
        .ifEmpty { FALLBACK_NAME }
}
