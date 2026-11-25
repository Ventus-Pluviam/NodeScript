package com.autoscript.appservice.npm

import com.autoscript.domain.npm.InstallHandle
import com.autoscript.domain.npm.NpmTargetScope
import com.autoscript.domain.npm.PackageSpec
import com.autoscript.domain.scripts.ProjectId
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

/** 锁和事务归协调器；这里只负责全局准备 → 重读核验 → 项目卸载的顺序，不另开安装会话。 */
internal class NpmPromotionRunner(
    private val layout: NpmProjectLayout,
    private val verifyRegistry: suspend (String, List<PackageSpec>, List<String>) -> Unit,
    private val runHeavy: suspend (String, List<String>, (Path, Path) -> Unit) -> InstallHandle,
) {
    suspend fun run(project: ProjectId): PromotionResult {
        val projectTree = PromoteTree.read(layout.projectRoot(project))
        val globalRoot = layout.projectsRoot
        val plan = PromotePlan(projectTree, PromoteTree.read(globalRoot, allowEmpty = true))
        val globalId = NpmTargetScope.GLOBAL
        if (plan.additions.isNotEmpty()) {
            val specs = plan.additions.map { "${it.name}@${it.version}" }
            // 覆盖 npmrc 的 no-save，确保全局清单记录精确版本，下次 prune 仍保留。
            val args = listOf("install", "--save", "--save-prod", "--save-exact", "--package-lock", "--prefer-offline") + specs
            verifyRegistry(globalId, plan.additions, args)
            runHeavy(globalId, args) { root, modules -> plan.verifyGlobal(PromoteTree.read(root, nodeModules = modules)) }
        }

        val handle = if (plan.packages.isEmpty()) null else {
            // 全局事务已完成且签名；重读真实树。取消或校验失败均不能进入卸载。
            plan.verifyGlobal(PromoteTree.read(globalRoot))
            plan.verifySource(PromoteTree.read(layout.projectRoot(project)))
            coroutineContext.ensureActive()
            val args = listOf("uninstall", "--save", "--package-lock") + plan.packages.keys
            runHeavy(project.value, args) { root, modules -> plan.verifyProject(PromoteTree.read(root, nodeModules = modules)) }
        }
        coroutineContext.ensureActive()
        return PromotionResult(handle, plan.summary())
    }
}

internal data class PromotionResult(val handle: InstallHandle?, val summary: String)
