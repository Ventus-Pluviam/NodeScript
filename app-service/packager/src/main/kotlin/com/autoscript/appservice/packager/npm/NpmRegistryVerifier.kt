package com.autoscript.appservice.packager.npm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * 多镜像 integrity 交叉校验（docs/framework-design.md §10.5-1）。
 *
 * 补的洞：npm 的 lock integrity 只锁**注册表内容**，锁不住「这个镜像给我的那份
 * 是不是官方那份」——一份第三方 lock 把包名指到别处也照样验得过（故另有
 * [LockSigner]）。npmmirror 没有 ECDSA 签名端点，验签这条路走不通；设计给出的
 * 替代是**同一 spec 取两个独立镜像的声明，一致才接受**：两个运营主体不同的镜像
 * 同时被投毒，比投毒一个难得多，而 registry.npmjs.org 是独立的第二意见。
 *
 * 校验对象 = packument 的 `dist.integrity`（tarball 的 sha512），即 npm 自己据以
 * 接受内容的那个判据；不「两个镜像各下一份 tarball 比字节」——那要多花一倍的
 * 下载量，而结论等价。边界的诚实口径：本类回答「这两家对这份包的摘要声明一致吗」，
 * 不回答「镜像 hardcode 的摘要是否真由上游产生」（要 sigstore/官方签名端点，
 * §10.5-1 记为未决项）。
 *
 * **不可静默降级**（§10.5-3）：副镜像不可达 / 该版本只在一侧 / 未提供 integrity /
 * 非 https 来源，一律 [Verdict.Unverifiable] 由调用方显式告知，绝不折成「通过」。
 */
class NpmRegistryVerifier(
    /** 首选注册表（本机配置的镜像，缺省 npmmirror，§10.2）。 */
    private val primary: String = MIRROR,
    /** 第二意见（npm 官方；与首选镜像运营主体独立，这是交叉校验成立的前提）。 */
    private val secondary: String = OFFICIAL,
    private val source: RegistrySource = HttpRegistrySource(),
) {

    /**
     * packument 获取缝：生产走 [HttpRegistrySource]，测试注入假源（零网络、可造分歧）。
     *
     * 取不到返回 null（不可达/超时/非 200/超限）——由本类折叠成 [Verdict.Unverifiable]，
     * **不**把 null 当「通过」。
     */
    fun interface RegistrySource {
        fun packument(registryBase: String, escapedName: String): String?
    }

    /**
     * 三分而非布尔：调用方必须能区分「验过且一致」与「没能验」，否则 UI 只能画同一个
     * 绿勾——那正是被批判的假装安全模型成立。
     */
    sealed interface Verdict {

        /** 两镜像对同一版本给出同一 integrity。 */
        data class Agreed(
            val name: String,
            val version: String,
            val integrity: String,
            val tarball: String?,
            /** true = 请求是范围/缺省，故按 `dist-tags.latest` 复核（漂移面），不是将要安装的确切版本。 */
            val viaLatestTag: Boolean,
        ) : Verdict

        /** 两镜像声明不一致（版本漂移或 integrity 对不上）：绝不接受。 */
        data class Disagreed(
            val name: String,
            val requested: String?,
            val primary: Resolved?,
            val secondary: Resolved?,
            val reason: String,
        ) : Verdict

        /** 没验成：副镜像不可达 / 只在一侧有 / 无 integrity / 来源非 https。 */
        data class Unverifiable(val name: String, val reason: String) : Verdict
    }

    /** 单个镜像对某 spec 的解析结果。 */
    data class Resolved(val version: String, val integrity: String?, val tarball: String?)

    /**
     * 校验 `name@version`（[version] null = latest；非精确范围按 `dist-tags.latest`，见
     * [Verdict.Agreed.viaLatestTag]）。
     *
     * @param primary 本次的首选注册表（调用方传自己的当前配置；缺省用构造时的）。
     *   跨校验的另一半恒为 [secondary]，不随首选变——否则用户把首选也改成 npmjs
     *   就变成自己跟自己比。
     * @throws IllegalArgumentException 包名形态非法（含路径/URL 注入企图；调用方按
     *   ERR_INVALID_PARAM 折叠，§7 诚实上报）
     */
    fun verify(name: String, version: String?, primary: String = this.primary): Verdict {
        requireName(name)
        val escaped = escapeName(name)
        val pBase = canonicalRegistry(primary)
            ?: return Verdict.Unverifiable(name, "首选注册表不是 https 来源：$primary（交叉校验不做明文来源）")
        val sBase = canonicalRegistry(secondary)
            ?: return Verdict.Unverifiable(name, "第二意见注册表不是 https 来源：$secondary")
        val p = resolve(pBase, escaped, name, version)
        val s = resolve(sBase, escaped, name, version)
        if (p == null && s == null) {
            return Verdict.Unverifiable(name, "两个注册表都取不到 $name 的 packument（主：$primary 副：$secondary）")
        }
        if (p == null) {
            return Verdict.Unverifiable(name, "首选注册表 $primary 未返回 $name@${version ?: "latest"} 的 packument")
        }
        if (s == null) {
            // 镜像同步有窗口期：副镜像暂无此版本 ≠ 投毒。如实说「没验成」，不报警也不放行。
            return Verdict.Unverifiable(name, "第二意见 $secondary 未返回该版本（不可达或尚未同步），无法交叉校验")
        }
        if (p.integrity == null || s.integrity == null) {
            return Verdict.Unverifiable(name, "该版本未提供 dist.integrity（npmmirror=${p.integrity != null} npmjs=${s.integrity != null}），无交叉校验锚点")
        }
        if (p.version != s.version) {
            return Verdict.Disagreed(name, version, p, s, "两注册表 latest 版本漂移：${primary}=${p.version} vs ${secondary}=${s.version}")
        }
        if (p.integrity != s.integrity) {
            return Verdict.Disagreed(name, version, p, s, "同一版本 $name@${p.version} 的 dist.integrity 不一致：$primary=$p.integrity vs $secondary=$s.integrity")
        }
        return Verdict.Agreed(
            name = name,
            version = p.version,
            integrity = p.integrity,
            tarball = p.tarball,
            viaLatestTag = version == null || !isExactVersion(version),
        )
    }

    /** 从一个注册表解析出目标版本；JSON 取不到/解析不出/无该版本 → null。 */
    private fun resolve(registryBase: String, escapedName: String, name: String, version: String?): Resolved? {
        val body = source.packument(registryBase, escapedName) ?: return null
        return parsePackument(body, version)
    }

    companion object {
        /** §10.2 默认镜像（国内实测存活）。 */
        const val MIRROR = "https://registry.npmmirror.com"

        /** 官方注册表（第二意见）。 */
        const val OFFICIAL = "https://registry.npmjs.org"

        /** 精确版本（可带 v 前缀）；带范围字符（^~><=* 空格）的都不算精确。 */
        private val EXACT = Regex("""^v?\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$""")

        /** 合法包名：可选 `@scope/` 前缀 + 名字段。挡住路径/URL/查询注入。 */
        private val NAME = Regex("""^(@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+$""")

        private fun isExactVersion(v: String): Boolean = EXACT.matches(v.trim())

        /** 与 npm npa 的 escapedName 同形（`@scope/name` → `@scope%2fname`，packument URL 用）。 */
        internal fun escapeName(name: String): String = name.trim().replace("/", "%2f")

        /**
         * 规整化 registry 基址：去尾斜杠、限 https。
         *
         * 在**缝边界**做而不是只在 [HttpRegistrySource] 里做：调用方的注册表字符串可能
         * 带尾斜杠/子路径（`https://harbor.example.com/registry/`），若不先统一，下一跳
         * 字符串拼接就会得出 `//dayjs` 这种双斜杠 URL（真机上多半 200——静默错更难查）；
         * 非 https 返回 null，由调用方折叠成 Unverifiable。
         */
        internal fun canonicalRegistry(base: String): String? {
            val u = try {
                URI(base.trim())
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (!u.scheme.equals("https", ignoreCase = true) || u.host.isNullOrEmpty()) return null
            val p = u.path?.trimEnd('/') ?: ""
            return u.scheme + "://" + u.authority + p
        }

        private fun requireName(name: String) {
            val n = name.trim()
            require(NAME.matches(n)) {
                "包名形态非法：$name（多镜像交叉校验只接受 npm 包名，不拼进 URL）"
            }
        }

        // ═══════════ packument 极简解析（手写 string-aware 扫描，不引 JSON 库） ═══════════
        //
        // 为什么不是 indexOf：packument 的 readme / scripts 都是**被转义的 JSON 串**，
        // 里面出现 `"integrity"` / `"dist-tags"` 这类字面量时，朴素 indexOf 会一头撞进
        // 字符串内容并从此解析错位（后果不是崩，是提取到别的对象的字段）。故先按 JSON
        // 词法跳过串值，再取成员——只实现本类要用的那点语法，不写通用解析器。

        /** 解析出目标版本的 [Resolved]；[requested] null/非精确 → 取 `dist-tags.latest`。 */
        internal fun parsePackument(json: String, requested: String?): Resolved? {
            val root = objectBoundsFrom(json, json.indexOf('{')) ?: return null
            val distTags = memberValue(json, root, "dist-tags")
            val latest = distTags?.let { (from, end) -> memberString(json.substring(from, end), "latest") }
            val target = if (requested != null && isExactVersion(requested)) requested.trim().removePrefix("v") else latest
            if (target.isNullOrEmpty()) return null
            val versions = memberValue(json, root, "versions") ?: return null
            val ver = memberValueWhere(json, versions) { it == target } ?: return null
            // 只认 dist 对象内的 integrity/tarball：版本对象里别处出现同名字段一概不算
            val dist = memberValue(json, ver, "dist") ?: return null
            val slice = json.substring(dist.first, dist.second)
            return Resolved(
                version = target,
                integrity = memberString(slice, "integrity"),
                tarball = memberString(slice, "tarball"),
            )
        }

        /**
         * 在对象内容区间 [from, end) 里找某个成员的值对象，返回 (内容起点, 闭合括号下标+1)。
         * [keyOf] 用原始字符串比较键名；命中第一个匹配。
         */
        private fun memberValueWhere(text: String, from: Int, end: Int, keyOf: (String) -> Boolean): IntPair? {
            var i = from
            while (i < end) {
                // 成员之间是逗号，不是直接相邻：先吃掉分隔符再认键，否则第二个成员起手
                // 就落在 "," 上、readKey 返回 null、整个对象被判「解析不了」（症状是
                // 第一个键永远读得到、之后全丢）。
                if (text[i] == ',' || text[i] == ';') i++
                val ws0 = skipWs(text, i)
                val key = readKey(text, ws0) ?: return null
                val colon = skipWs(text, key.second)
                if (colon >= end || text[colon] != ':') return null
                val vStart = skipWs(text, colon + 1)
                val v = readValue(text, vStart) ?: return null
                if (v.first < end && keyOf(key.first)) {
                    // OBJECT 时 v.first 即内容起点（同 [IntPair] 口径），直接透传；
                    // 别再 +1——多一位会把内容首字符连同成员名一起吃成空。
                    if (v.second == ValueKind.OBJECT) return IntPair(v.first, v.third)
                    return null   // 期望对象却是别的类型：视为无此成员（不是猜）
                }
                i = v.third
            }
            return null
        }

        /** 按谓词取成员值对象（键名要比较的内容不止一个时用，如版本号）。 */
        private fun memberValueWhere(text: String, root: IntPair, keyOf: (String) -> Boolean): IntPair? =
            memberValueWhere(text, root.first, root.second, keyOf)

        /** 按精确键名取（`"dist-tags"` / `"versions"` 这类）。 */
        private fun memberValue(text: String, root: IntPair, key: String): IntPair? =
            memberValueWhere(text, root.first, root.second) { it == key }

        /** 在对象内容区间里取 `"key": "value"` 的串值（键名精确）。 */
        private fun memberString(text: String, key: String): String? {
            var i = 0
            while (i < text.length) {
                if (text[i] == ',' || text[i] == ';') i++   // 同 memberValueWhere：先吃成员分隔符
                val ws0 = skipWs(text, i)
                val k = readKey(text, ws0) ?: return null
                val colon = skipWs(text, k.second)
                if (colon >= text.length || text[colon] != ':') return null
                val vStart = skipWs(text, colon + 1)
                val v = readValue(text, vStart) ?: return null
                if (k.first == key && v.second == ValueKind.STRING) return v.fourth
                i = v.third
            }
            return null
        }

        /** 从 [i] 起读一个 JSON 字符串，要求它在 [i] 处以引号开头；返回 (解码值, 收尾引号+1)。 */
        private fun readKey(text: String, i: Int): Pair<String, Int>? {
            if (i >= text.length || text[i] != '"') return null
            val sb = StringBuilder()
            var p = i + 1
            while (p < text.length) {
                val c = text[p]
                if (c == '\\') { p++; if (p >= text.length) return null; sb.append(unescape(text[p++])); continue }
                if (c == '"') return sb.toString() to (p + 1)
                sb.append(c); p++
            }
            return null
        }

        private fun unescape(c: Char): Char = when (c) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            // \b \f 在本类用不到（packument 的键值是包名/URL/摘要），按字面量字符返回
            else -> c
        }

        /**
         * 从 [i] 起读任意 JSON 值。
         *
         * [Quad] 四槽的含义随种类而变：STRING 时 first=起始引号下标、fourth=解码值；
         * OBJECT 时 first=内容起点、second=闭合括号下标+1（与 [IntPair] 同口径）；
         * ARRAY/SCALAR 时 first 仅作占位，调用方只该用 third 作游标推进。
         */
        private fun readValue(text: String, i: Int): Quad? {
            if (i >= text.length) return null
            when (text[i]) {
                '"' -> {
                    val kv = readKey(text, i) ?: return null
                    return Quad(kv.second - 1, ValueKind.STRING, kv.second, kv.first)
                }
                '{' -> {
                    val bounds = objectBoundsFrom(text, i) ?: return null
                    return Quad(bounds.first, ValueKind.OBJECT, bounds.second, null)
                }
                '[' -> {
                    val close = matchBracket(text, i, '[', ']') ?: return null
                    return Quad(i + 1, ValueKind.ARRAY, close + 1, null)
                }
                else -> {
                    var p = i
                    // 标量（true/false/null/数字）：读到分隔符为止，本类不需要其值，
                    // 只要求至少吃到一个非分隔符字符，否则游标不前进会死循环。
                    while (p < text.length && text[p] !in ",\r\n\t ") p++
                    if (p == i) return null
                    return Quad(i, ValueKind.SCALAR, p, null)
                }
            }
        }

        private fun skipWs(text: String, i: Int): Int {
            var p = i
            while (p < text.length && (text[p] == ' ' || text[p] == '\t' || text[p] == '\r' || text[p] == '\n')) p++
            return p
        }

        /** 配对括号匹配（不作串内/串外区分，调用前须确认 [open] 处不是字符串内部）。 */
        private fun matchBracket(text: String, open: Int, openCh: Char, closeCh: Char): Int? {
            var depth = 0
            var i = open
            while (i < text.length) {
                val c = text[i]
                if (c == '"') {
                    val e = readKey(text, i) ?: return null
                    i = e.second
                    continue
                }
                if (c == openCh) depth++
                if (c == closeCh) {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return null
        }

        private enum class ValueKind { STRING, OBJECT, ARRAY, SCALAR }

        /** readValue 的返回体（Kotlin 没 4 元组，用 data class 免得拆两层 Triple）。 */
        private data class Quad(val first: Int, val second: ValueKind, val third: Int, val fourth: String?)

        /** 定位 [brace] 处 `{` 的配对 `}`，返回内容区间（内容起点, 闭合下标+1）。 */
        private fun objectBoundsFrom(text: String, brace: Int): IntPair? {
            if (brace < 0 || brace >= text.length || text[brace] != '{') return null
            var depth = 0
            var i = brace
            while (i < text.length) {
                val c = text[i]
                if (c == '"') {
                    val e = readKey(text, i) ?: return null
                    i = e.second
                    continue
                }
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) return IntPair(brace + 1, i + 1)
                }
                i++
            }
            return null
        }

        private data class IntPair(val first: Int, val second: Int)
    }
}

/**
 * 生产 [NpmRegistryVerifier.RegistrySource]：`<registry>/<escapedName>` 取 packument。
 *
 * 三条硬规则（都为「第二意见」这个语义服务）：
 * - **只 https**：明文来源可被任意中间人改写，两份「一致」可能出自同一个攻击者，
 *   交叉校验就白做了（取不到 → Unverifiable，由上层显式告知，不静默通过）；
 * - **不跟随重定向**：跟随等于把第二意见拱手让给 Location 指向的任何主机；
 * - **字节上限 [maxBytes]**：packument 是镜像给的不可信数据，塞爆堆的攻击面 §10.6 已点名。
 */
class HttpRegistrySource(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 8_000,
    private val maxBytes: Long = 16L * 1024 * 1024,
) : NpmRegistryVerifier.RegistrySource {

    override fun packument(registryBase: String, escapedName: String): String? {
        val base = normalize(registryBase) ?: return null
        val conn = try {
            URI(base + "/" + escapedName).toURL().openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        try {
            conn.connectTimeout = connectTimeoutMillis
            conn.readTimeout = readTimeoutMillis
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            if (conn.contentLengthLong > maxBytes) return null
            val buf = conn.inputStream.use { it.readCapped(maxBytes + 1) } ?: return null
            if (buf.size > maxBytes) return null
            return buf.toString(StandardCharsets.UTF_8)
        } catch (e: IOException) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 规整化委托给 [NpmRegistryVerifier.Companion.canonicalRegistry]（播查同源：检验器
     * 与执行源对「合法注册表」的口径必须一致，否则会出现「校验通得过、下载却不去同一处」）。
     */
    private fun normalize(registryBase: String): String? =
        NpmRegistryVerifier.canonicalRegistry(registryBase)

    /** 读最多 [limit] 字节；中途 IO 出错返回 null（半份 packument 比没有更危险）。 */
    private fun java.io.InputStream.readCapped(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = try {
                read(buf)
            } catch (e: IOException) {
                return null
            }
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
