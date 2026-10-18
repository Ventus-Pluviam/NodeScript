package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.npm.NpmCacheReclaimReport
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.domain.npm.NpmTargetScope
import kotlinx.coroutines.CancellationException

/**
 * 依赖维护动作（§10.9 第 5 条那几颗按钮 + 缓存回收）的写操作（挂起；**顶层函数**）。
 *
 * 搬出 `MainActivity` 的理由与 [loadRegistry] 逐字相同：Activity 的函数数
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
 * 回执按动作分别说清**接下来会发生什么**：这些动作都入队走安装会话（几十秒量级），
 * 回一句「已完成」会让用户以为已经好了 —— 那是这个页面最容易撒的谎。
 *
 * **本函数是"按枚举转手"的那条通用路**：2026-10-17 之后界面上只有「清理未声明依赖」
 * （`PRUNE`）走它 —— 「依赖去重」改走 [runDedupeAndPromoteOp]（它要做两件事），
 * 「按 lock 重装」整颗下线。枚举三个取值一个没少、这条路的用例也照测：
 * 将来哪颗按钮回来，接上就行，不必重新推一遍这段编排。
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
 * 「**去重并提升到全局**」（2026-10-17 用户口径：「依赖去重执行后让它把依赖自动放进全局
 * 作用域中」）—— 一次点击两件事，所以它有一条自己的入口，而不是给 [NpmMaintenanceAction]
 * 加第四个枚举值：那两步的产物落在**两个不同的作用域**上（先去重当前这棵，再把声明的依赖
 * 装一份到 `files/scripts/node_modules`），收成一个动作名会让"这颗按钮动的是谁"在枚举上
 * 读不出来。
 *
 * **两段各自的成败分开说**，三条刻意：
 * - 前段（去重）失败 → 整颗报失败，**不做后段**：依赖树还乱着就往外提升，等于把一个
 *   半成品抄成全局的基准。
 * - 前段成功、后段失败 → **两个都给**：`opNotice` 说去重已经入队（那是真的跑了），
 *   `opError` 说提升失败的原因为什么。只报失败会让用户以为整颗按钮什么也没干，
 *   于是又点一次 —— 而去重会被重跑一遍。
 * - 作用域本来就是**全局**时不提升（也没有可提升的"别处"），回执里明说这一句：
 *   否则用户会以为"提升"悄悄失败了。
 *
 * 回执**不替用户宣布结果**（与三颗维护按钮同）：`dedupe` 要几十秒，
 * 回一句「已提升」而不说"已入队"会让人以为现在就能去全局那栏看到。
 */
internal suspend fun runDedupeAndPromoteOp(host: HostSummary?, state: NpmState): NpmState {
    val projectId = state.selectedProjectId
        ?: return state.copy(opError = "还没有选中的作用域：依赖维护跑在某一棵 node_modules 上", opNotice = null)
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    val busy = state.copy(maintenance = NpmMaintenanceAction.DEDUPE, opError = null, opNotice = null)
    return try {
        host.runNpmMaintenance(projectId, NpmMaintenanceAction.DEDUPE)
        var notice = "已入队 npm dedupe：拍平当前作用域的重复依赖"
        var promoteError: String? = null
        if (NpmTargetScope.isGlobal(projectId)) {
            notice += "。当前作用域就是全局，没有可提升的对象"
        } else {
            try {
                host.promoteNpmToGlobal(projectId)
                notice += "；已把「$projectId」声明的依赖提升到全局（别的项目也能用）"
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                promoteError = "提升到全局失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
        loadNpmSnapshot(host, busy).copy(
            maintenance = null,
            opNotice = notice,
            opError = promoteError,
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
 * 现取一轮依赖面板读数 + 日志大框那一段（[loadNpmMaintenanceOp] 用）。
 *
 * **为什么复制 `reloadNpm` 那段而不是让 `:ui` 去够 `MainActivity` 的私有方法**：
 * 那段逻辑住在 Activity 里（它要写 `mutableStateOf` 字段），而本文件是顶层函数、
 * 拿不到那个字段。抽成这个纯函数后两边读的是同一份三落点口径 —— 复制的那份只做
 * 「调用读口 + 算下一份状态」，失败语义仍在 [NpmState.failed] 里（唯一一份）。
 *
 * **日志那一段跟着一起现取**（2026-10-17 用户令「最下面弄个显示日志的大框」）：
 * 凡是"刷新一下这一屏"的路径（进页面 / 点刷新 / 安装轮询的每一跳 / 维护与回收之后）
 * 都走这里 —— 于是装依赖时那个框是**活的**：npm 说什么、什么时候说的，跟着进度条一起走。
 */
internal suspend fun loadNpmSnapshot(host: HostSummary, previous: NpmState): NpmState = try {
    loadNpmLog(host, NpmState.of(host.npmSnapshot(), previous))
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    // 操作已经成功了，只是回读失败：保留旧清单 + 把失败如实挂上（与 reloadNpm 同）。
    NpmState.failed(t, previous)
}

/**
 * 拉一段这个项目的 npm 输出进日志大框。
 *
 * 三条纪律（与控制台页 [loadConsoleCmd] 那份累积同源，各写各的一条）：
 * - **失败不动已有的行与游标**：一次瞬时失败把用户刚看到的输出抹掉，比报错更糟；
 *   游标清零下次还会把环里最旧那批重放一遍。
 * - **读不到日志 ≠ 这次操作失败**：它不进 [NpmState.opError] —— 那是两条账
 *   （输出是装饰性的读数，成败在事件流与清单里）。
 * - **没有选中的项目就没有日志可拉**（不是失败）：如实返回原状态。
 */
private suspend fun loadNpmLog(host: HostSummary, state: NpmState): NpmState {
    val projectId = state.selectedProjectId ?: return state
    return try {
        NpmState.withConsole(state, host.consoleOutput(projectId, state.consoleSeq, LOG_PAGE))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 异常对象刻意不接（`_`）：这句里没有它的位置 —— 接了只是为了 detekt 闭嘴。
        state
    }
}
