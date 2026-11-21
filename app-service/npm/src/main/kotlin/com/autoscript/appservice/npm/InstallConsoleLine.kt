package com.autoscript.appservice.npm

import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind

/**
 * [InstallEvent] → 控制台行的投影（判读只在这一处，呈现层按 [NpmConsoleLineKind] 着色）。
 *
 * 事件环与控制台环是**两个视图**，谁也不替代谁：脚本侧的 `onProgress`/`onWarning`
 * 形状一个字不改（那是 §10.8 的契约），控制台只是宿主自己的界面。
 */
internal fun projectNpmConsoleLine(e: InstallEvent, atMillis: Long): NpmConsoleLine = when (e) {
    is InstallEvent.Progress -> NpmConsoleLine(
        kind = NpmConsoleLineKind.PHASE,
        text = phaseText(e.phase) + (e.pkg?.let { "  $it" } ?: ""),
        atMillis = atMillis,
    )
    is InstallEvent.Warning -> NpmConsoleLine(
        kind = NpmConsoleLineKind.WARNING,
        text = e.message,
        atMillis = atMillis,
    )
    is InstallEvent.Finished -> NpmConsoleLine(
        kind = NpmConsoleLineKind.RESULT,
        text = if (e.success) (e.detail ?: "完成") else "失败：${e.detail ?: "（无详情）"}",
        atMillis = atMillis,
        // 成败是**判读**，在宿主侧定：呈现层据此着色，不去猜那句中文怎么写。
        ok = e.success,
    )
}

/** 阶段的中文说法（控制台是给人看的；`bridge/js` 那边仍走 `phaseWire` 的连字符口径）。 */
private fun phaseText(p: InstallEvent.Phase): String = when (p) {
    InstallEvent.Phase.QUEUED -> "排队中"
    InstallEvent.Phase.RESOLVE -> "解析依赖"
    InstallEvent.Phase.DOWNLOAD -> "下载"
    InstallEvent.Phase.REIFY -> "写入 node_modules"
    InstallEvent.Phase.POST_CHECK -> "收尾校验"
    InstallEvent.Phase.DONE -> "完成"
}
