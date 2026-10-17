package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Path

/**
 * 「提升到全局」这一趟要装的东西。
 *
 * @property specs 包说明符（`name@range`）—— `crossCheckRegistry` 的装前多镜像校验吃这个。
 * @property args 重操作执行体的 argv（`install … --prefer-offline`）。
 *   两份而不是一份，是因为校验器要的是包说明符、执行体要的是整条 argv，
 *   让调用方自己从 argv 里往回剥（`drop(1).dropLast(1)`）等于把这一层的形状泄漏出去。
 */
internal data class PromotePlan(val specs: List<String>, val args: List<String>)

/**
 * 从项目的 `package.json` 声明拼出提升到全局的 argv。
 *
 * 取的是**声明的**依赖（`dependencies` + `devDependencies`，见
 * [NpmScriptResolver.declaredSpecs]），**不是** lock 的闭包：闭包是几百个包，
 * 每次点这颗按钮都把整棵树抄一遍全局是不合理的；声明才是"这个项目要什么"。
 * 范围也照声明原样带（`axios@^1.7.0`），不锁死到 lock 里那个具体版本 ——
 * 全局那份跟着项目一起走小版本，两边不会分家。
 *
 * 两种"没有"各是各的话（调用方原样透传到界面）：
 * - **没有 `package.json`** → `ERR_FILE_NOT_FOUND`：缺的是那份清单本身；
 * - **有清单但两个段都空** → `ERR_NOT_FOUND`：清单在，里面没有可提升的东西。
 */
internal fun promotePlanOf(projectRoot: Path, projectId: String): PromotePlan {
    val specs = NpmScriptResolver.declaredSpecs(projectRoot)
        ?: throw AutojsException(
            ErrorCode.ERR_FILE_NOT_FOUND,
            "项目 $projectId 没有 package.json，没有可提升到全局的依赖声明",
        )
    if (specs.isEmpty()) {
        throw AutojsException(
            ErrorCode.ERR_NOT_FOUND,
            "项目 $projectId 的 package.json 里 dependencies/devDependencies 都是空的，" +
                "没有可提升到全局的依赖",
        )
    }
    // `--prefer-offline` 与面板那条安装行同款（2026-10-17 起缺省开启）：提升的往往正是
    // 刚在项目里装过的那几包，缓存里十有八九已经有了。
    return PromotePlan(specs, listOf("install") + specs + listOf("--prefer-offline"))
}
