package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.npm.NpmCacheReclaimReport
import com.autoscript.domain.npm.NpmMaintenanceAction
import kotlinx.coroutines.CancellationException

/**
 * 依赖维护动作（§10.9 第 5 条那三颗按钮 + 缓存回收）的写操作（挂起；**顶层函数**）。
 *
 * 搬出 `MainActivity` 的理由与 [loadRegistry]/[loadAudit] 逐字相同：Activity 的函数数
 * 贴着 detekt 的 `TooManyFunctions` 线，且这段是"拿读口算下一份状态"的纯逻辑 ——
 * 放这里能用 `FakeHost` 直接测（`:ui` 的单测门跑 JVM，`MainActivity` 构造不出来）。
 *
 * 三落点（未接线原文透传 / 抛了原文透传 / 成功现取）不另立口径：
 * - 未接线 → `opError` 原文（**不冒充**「已清理」）；
 * - 抛（`ci` 的验签拒绝、磁盘/配额预检）→ `opError` 原文、**不清依赖清单**；
 * - 成功 → `opNotice` 回执 + 现取一次（清单变没变是宿主的账，界面不许自己先抹掉）。
 *
 * **失败原文原样透传**（与 [runConsoleCmd] 同一条纪律）：`ci` 的 `lock.sig` 缺失那句里
 * 已经写清了为什么拒，界面再译一遍就是第二份判据，而漂掉的那份正好是用户看到的那份。
 */

/**
 * 跑一个维护动作。
 *
 * `ci` 会在宿主侧**先验 lock 签名**（`InstallCoordinator.ci` 第一行）—— 界面不预判、
 * 也不为它另编话术：验签失败是一条 `ERR_PERMISSION_DENIED` 原文，与别的失败走同一条路。
 *
 * 回执按动作分别说清**接下来会发生什么**：三颗按钮都入队走安装会话（几十秒量级），
 * 回一句「已完成」会让用户以为已经好了 —— 那是这个页面最容易撒的谎。
 */
internal suspend fun runNpmMaintenanceOp(
    host: HostSummary?,
    state: NpmState,
    action: NpmMaintenanceAction,
): NpmState {
    val projectId = state.selectedProjectId
        ?: return state.copy(opError = "还没有选中的项目：依赖维护跑在某个项目的 node_modules 上", opNotice = null)
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    val busy = state.copy(maintenance = action, opError = null, opNotice = null)
    return try {
        host.runNpmMaintenance(projectId, action)
        loadNpmSnapshot(host, busy).copy(
            maintenance = null,
            opNotice = noticeFor(action),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        busy.copy(maintenance = null, opError = t.message ?: t.javaClass.simpleName, opNotice = null)
    }
}

/**
 * 按 lock 闭包回收缓存（§10.9 第 5 条那颗 cache clean）。
 *
 * 与三颗维护按钮的两处不同，都写在这里免得被"统一"掉：
 * - **不占安装会话**（不动依赖树，只是删几个缓存文件），所以没有"排队中"这一段，
 *   回执是**一次算出来的读数**而不是"入队了"；
 * - 回执要带上**删了多少 / 留了多少**：这个动作的产物是「磁盘上少了东西」，
 *   一句「已清理」在用户那里等于什么都没说（他按下去就是为了看那个数字变没变）。
 */
internal suspend fun reclaimNpmCacheOp(host: HostSummary?, state: NpmState): NpmState {
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    val busy = state.copy(reclaimingCache = true, opError = null, opNotice = null)
    return try {
        val report = host.reclaimNpmCache()
        // 回收之后**必须**再取一次读数：配额条上那个数字就是用户按这颗按钮的原因，
        // 不回读的话屏幕上还是旧值，看起来像"没生效"。
        loadNpmSnapshot(host, busy).copy(
            reclaimingCache = false,
            opNotice = reclaimNotice(report),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        busy.copy(reclaimingCache = false, opError = t.message ?: t.javaClass.simpleName, opNotice = null)
    }
}

/** 三颗按钮各自的"接下来会发生什么"（**不替用户宣布结果** —— 结果在清单里）。 */
private fun noticeFor(action: NpmMaintenanceAction): String = when (action) {
    NpmMaintenanceAction.PRUNE -> "已入队 npm prune：删掉 lock 里不需要的包，完成后清单会更新"
    NpmMaintenanceAction.DEDUPE -> "已入队 npm dedupe：拍平重复依赖，完成后清单会更新"
    NpmMaintenanceAction.CI -> "已入队 npm ci：按 lock 严格重建 node_modules（会先验 lock 签名）"
}

/**
 * 回收回执。
 *
 * 「留了多少」是**主句**、「删了多少」是副句：用户按下去是为了腾地方，但缓存不是越空越好
 * （§10 的离线能力全建在它上面），所以留下来的那部分要说得像是**结果**而不是**残留**。
 */
internal fun reclaimNotice(r: NpmCacheReclaimReport): String = buildString {
    append("缓存已按各项目 lock 回收：删了 ").append(r.removedEntries).append(" 条（")
    append(r.removedBytes / 1024 / 1024).append("MB）")
    append("；保留 ").append(r.keptEntries).append(" 条（").append(r.keptBytes / 1024 / 1024).append("MB）")
    if (r.keptEntries == 0) {
        append(" —— 缓存已空，下次安装必须联网")
    } else {
        append("，离线重装仍可用")
    }
    if (r.indexRebuilt) append("；顺带修掉了缓存索引里的悬空引用")
}

/**
 * 现取一轮依赖面板读数（[loadNpmMaintenanceOp] 用）。
 *
 * **为什么复制 `reloadNpm` 那段而不是让 `:ui` 去够 `MainActivity` 的私有方法**：
 * 那段逻辑住在 Activity 里（它要写 `mutableStateOf` 字段），而本文件是顶层函数、
 * 拿不到那个字段。抽成这个纯函数后两边读的是同一份三落点口径 —— 复制的那份只做
 * 「调用读口 + 算下一份状态」，失败语义仍在 [NpmState.failed] 里（唯一一份）。
 */
internal suspend fun loadNpmSnapshot(host: HostSummary, previous: NpmState): NpmState = try {
    NpmState.of(host.npmSnapshot(), previous)
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    // 操作已经成功了，只是回读失败：保留旧清单 + 把失败如实挂上（与 reloadNpm 同）。
    NpmState.failed(t, previous)
}
