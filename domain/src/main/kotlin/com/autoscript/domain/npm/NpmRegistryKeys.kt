package com.autoscript.domain.npm

import java.net.URI
import java.net.URISyntaxException

/**
 * registry 基址的**唯一一份**判据（docs §10.2 / §10.9 第 8 条）。
 *
 * **为什么住 `:domain`**：判据有两个消费方 —— 缝边界（`NpmRegistryVerifier` 的
 * `canonicalRegistry`，规范化后才拼 packument URL）与界面（`:ui` 的输入校验，
 * 用户打错一个字母要当场被告知）。抄两份必然漂，而漂的方向恰好最坏：
 * 界面放行的串在缝边界被折成 `Unverifiable`，用户看到的是「校验不通过」而不是
 * 「你写的地址有问题」。批 82 的 `ScriptEnvKeys` 是同一条理由的先例。
 *
 * **本对象不依赖 `NpmRegistryVerifier`**：`OFFICIAL`/`MIRROR` 的**字面量**住这里，
 * 那边改成指向本对象的别名 —— 依赖方向只能是「实现 → 契约」，反过来就成环了。
 *
 * 只认 https（与 [NpmRegistryVerifier] 的缝边界同口径）：明文来源可被任意中间人改写，
 * 而交叉校验的全部意义就是「两个独立运营主体说同一件事」—— 一个可改写的来源让这句话
 * 不成立。**不跟随重定向**那条在 `HttpRegistrySource`，不在本对象。
 */
object NpmRegistryKeys {

    /** 官方注册表（**出厂首选**，§18 第 7 项 2026-09-26 拍板；也是别家首选时的第二意见）。 */
    const val OFFICIAL: String = "https://registry.npmjs.org"

    /** 镜像（国内实测存活；出厂时做**第二意见**，也可被用户提成首选）。 */
    const val MIRROR: String = "https://registry.npmmirror.com"

    /**
     * 规整化 registry 基址：去首尾空白、去**尾**斜杠；非 https / 无 host / 形态非法 → null。
     *
     * 在**缝边界**做而不是只在拼 URL 那一处做：调用方的注册表字符串可能带尾斜杠或子路径
     * （`https://harbor.example.com/registry/`），若不先统一，下一跳字符串拼接就会得出
     * `//dayjs` 这种双斜杠 URL（真机上多半 200 —— 静默错更难查）。
     *
     * **子路径保留**（企业自建 registry 常在子路径上）；**query 与 fragment 丢弃** ——
     * 与拆分前的 `NpmRegistryVerifier.canonicalRegistry` 逐字一致（拼 packument URL 时
     * 本来就只能接路径，留着 query 会拼出 `.../npm?token=x/dayjs` 这种废串）。
     *
     * 返回 null 的三种情形：URI 形态非法 / scheme 不是 https / host 为空。
     *
     * 两处 catch 是必要的、也是**有意吞掉**的：本函数是「判据」，它的契约就是
     * 「任何字符串都得到一个结论」—— 让 `URISyntaxException` 漏出去，等于让界面在
     * 用户打错一个字时崩掉。（`URI` 对形态非法抛受检的 `URISyntaxException`，
     * 对 null 参数抛 `IllegalArgumentException`；Kotlin 不强制受检异常，
     * 所以原实现只 catch 后者是个**只在畸形输入上才暴露**的漏网。）
     */
    fun canonicalize(raw: String): String? {
        val u = try {
            URI(raw.trim())
        } catch (_: URISyntaxException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!u.scheme.equals("https", ignoreCase = true) || u.host.isNullOrEmpty()) return null
        val p = u.path?.trimEnd('/') ?: ""
        return u.scheme + "://" + u.authority + p
    }

    /**
     * 合法 → null；不合法 → **拒收原文**（点名用户输入的那个串、说清为什么）。
     *
     * 点名是硬要求：界面上只显示这一句话，不点名就等于让用户自己猜是哪个字符不对。
     * 空白输入（null / 空串 / 全空白）**不算不合法** —— 那是「恢复出厂缺省」的表达，
     * 由调用方按 null 处理；本函数对它返回 null（放行）。
     */
    fun reject(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null                       // 空白 = 恢复出厂，不是错误
        if (s.any { it.isWhitespace() || it.isISOControl() }) {
            return "镜像源地址含空白或控制字符：$s"
        }
        val why = when {
            !s.startsWith("https://", ignoreCase = true) ->
                "镜像源必须是以 https:// 开头的地址（明文来源可被中间人改写，交叉校验就白做了）：$s"
            canonicalize(s) == null -> "镜像源地址解析不出主机名：$s"
            else -> null
        }
        return why
    }
}
