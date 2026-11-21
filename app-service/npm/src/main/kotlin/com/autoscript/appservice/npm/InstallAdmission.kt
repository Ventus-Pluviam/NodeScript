package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallHandle
import com.autoscript.domain.scripts.ProjectId
import java.nio.file.Path

/** 普通操作和跨作用域迁移共用的磁盘准入；拒绝记事实，逼近下限只告警不拦截。 */
internal class InstallAdmission(
    private val layout: NpmProjectLayout,
    private val config: InstallConfig,
    private val freeSpaceProbe: (Path) -> Long,
    private val opLog: OpLogSink,
) {
    fun check(projectId: String, operation: String): Long {
        val free = freeSpaceProbe(layout.projectRoot(ProjectId(projectId)))
        if (free < config.minFreeBytes) {
            val why = "磁盘可用 ${free / 1024 / 1024}MB < 预检下限 ${config.minFreeBytes / 1024 / 1024}MB，拒绝安装"
            opLog.log(operation, projectId, false, why)
            throw AutojsException(ErrorCode.ERR_DISK_FULL, why)
        }
        return free
    }

    fun warning(handle: InstallHandle, free: Long): InstallEvent.Warning? =
        if (free < (config.minFreeBytes * InstallCoordinator.FREE_WARN_HEADROOM).toLong()) {
            InstallEvent.Warning(
                projectId = handle.projectId,
                handleId = handle.id,
                kind = InstallEvent.Kind.DISK_QUOTA,
                message = "磁盘可用 ${free / 1024 / 1024}MB 已逼近预检下限 " +
                    "${config.minFreeBytes / 1024 / 1024}MB：再装大包会被拒",
            )
        } else null
}
