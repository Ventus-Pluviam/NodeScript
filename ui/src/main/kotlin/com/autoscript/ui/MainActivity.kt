package com.autoscript.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.domain.host.HostSummary
import kotlinx.coroutines.launch

/**
 * 启动入口（launcher，manifest 在本模块，随库合并进 :app）。
 *
 * 接线方式（§6「UI 拆独立模块」的落点）：不 import `:app` 的任何类型 ——
 * 把 `application` 现转 `as? HostSummary`（`:domain` 读口，`AppShellApplication`
 * 实现），未实现即 [HomeState.UNWIRED] / [CapabilityCenterState.NOT_LOADED] 如实显示。
 *
 * 三个页签：首屏（壳/保活/漏投）、任务中心（§8.6 排期 + §8.5 档案/恢复账）、
 * 能力中心（§9.5 三态）。刷新时机分两种，**不能混**：
 * - 首屏状态是**同步**读（`shellSummary()`）：`onCreate` 首读 + 每次 `onResume` 重读；
 * - 能力态与任务态都是**挂起**的（`capabilityCenter()` 每次现问系统，含 root 探测的 IO 切换；
 *   `taskCenter()` 要读两个持久寄存器）：由 `LaunchedEffect(resumeTick, tab)` 驱动 ——
 *   回前台、或切到该页签时重取一次。
 *   这样用户从系统设置页授完权回来，看到的是**刚问过**的结论，而不是离开时那份缓存
 *   （后者正是"授权了但界面还说没授权"的来源）。
 *
 * 这里也是本模块唯一直接持有 [HostSummary] 的类：`HomeScreen`/`CapabilityScreen`
 * 只收纯状态 DTO，因此它们各自可 JVM 测（见 `HomeStateTest`/`CapabilityCenterStateTest`）。
 */
class MainActivity : ComponentActivity() {

    /** 首屏状态：compose 可观察单槽（Activity 持有，配置变更随重建重读，无跨进程共享诉求）。 */
    private var homeState: HomeState by mutableStateOf(HomeState.UNWIRED)

    /** 能力中心状态（同上）。 */
    private var capabilityState: CapabilityCenterState by mutableStateOf(CapabilityCenterState.NOT_LOADED)

    /** 任务中心状态（同上）。 */
    private var taskState: TaskCenterState by mutableStateOf(TaskCenterState.NOT_LOADED)

    /** 当前页签。 */
    private var tab: Tab by mutableStateOf(Tab.HOME)

    /** 每次 `onResume` +1：驱动 [LaunchedEffect] 重问系统（回前台即重读）。 */
    private var resumeTick: Int by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeState = HomeState.read(hostSummary())
        setContent {
            val scope = rememberCoroutineScope()
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Tabs(tab) { tab = it }
                        when (tab) {
                            Tab.HOME -> HomeScreen(
                                state = homeState,
                                onRefresh = { homeState = HomeState.read(hostSummary()) },
                            )
                            Tab.TASKS -> TaskCenterScreen(
                                state = taskState,
                                onRefresh = { scope.launch { reloadTasks() } },
                            )
                            Tab.CAPABILITIES -> CapabilityScreen(
                                state = capabilityState,
                                onRefresh = { scope.launch { reloadCapabilities() } },
                                onOpenSettings = { hostSummary()?.openCapabilitySettings(it) },
                            )
                        }
                    }
                }
            }
            // 键里带 tab：切到本页签本身就该现取，而不是显示上次离开时的快照。
            // 重读的触发权只在这两条（回前台/切页签）与手动刷新手里 —— 读失败不会
            // 反过来改 resumeTick 形成自激（见 reloadCapabilities）。
            LaunchedEffect(resumeTick, tab) {
                when (tab) {
                    Tab.HOME -> Unit
                    Tab.TASKS -> reloadTasks()
                    Tab.CAPABILITIES -> reloadCapabilities()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        homeState = HomeState.read(hostSummary())
        resumeTick++
    }

    /**
     * 现问系统（挂起；只写 [capabilityState]，不触发重读）。
     *
     * 三种落点都不撒谎：
     * - 读口未接线（宿主没实现 `HostSummary`）→ 留 [CapabilityCenterState.NOT_LOADED]，
     *   **不冒充**"一个能力都没有"（那是"读成功且清单为空"，而 `Capability.entries`
     *   恒非空 —— 空行集只可能是没读到）；
     * - 查询抛错（ROM 奇异实现）→ [CapabilityCenterState.failed]，原异常文案带上
     *   （现场要靠它区分"ROM 查询崩了"与"装配没接线"）；
     * - 成功 → 全量行 + 降级任务账。
     */
    private suspend fun reloadCapabilities() {
        val host = hostSummary()
        if (host == null) {
            capabilityState = CapabilityCenterState.NOT_LOADED
            return
        }
        capabilityState = try {
            CapabilityCenterState.of(host.capabilityCenter())
        } catch (t: Throwable) {
            CapabilityCenterState.failed(t)
        }
    }

    /**
     * 现取任务中心（挂起；只写 [taskState]，不触发重读）。
     *
     * 三种落点都不撒谎（与 [reloadCapabilities] 同构）：
     * - 读口未接线 → 留 [TaskCenterState.NOT_LOADED]，**不冒充**"没有任务"；
     * - 读取抛错（壳未装配/寄存器读崩）→ [TaskCenterState.failed]，原异常文案带上；
     * - 成功 → 任务 + 未结算执行 + 恢复账。
     *
     * 取 `nowMillis` 一次传进去（不在状态类里现取）：同一帧里所有相对时间共用同一个 now。
     */
    private suspend fun reloadTasks() {
        val host = hostSummary()
        if (host == null) {
            taskState = TaskCenterState.NOT_LOADED
            return
        }
        taskState = try {
            TaskCenterState.of(host.taskCenter(), nowMillis = System.currentTimeMillis())
        } catch (t: Throwable) {
            TaskCenterState.failed(t)
        }
    }

    private fun hostSummary(): HostSummary? = application as? HostSummary

    enum class Tab { HOME, TASKS, CAPABILITIES }
}

/** 页签条（P0 两个：首屏/能力中心）。选中态用前缀点标出，不引入额外图标依赖。 */
@Composable
private fun Tabs(current: MainActivity.Tab, onSelect: (MainActivity.Tab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (candidate in MainActivity.Tab.entries) {
            Button(onClick = { onSelect(candidate) }) {
                Text(if (candidate == current) "· ${candidate.label()}" else candidate.label())
            }
        }
    }
}

private fun MainActivity.Tab.label(): String = when (this) {
    MainActivity.Tab.HOME -> "首屏"
    MainActivity.Tab.TASKS -> "任务中心"
    MainActivity.Tab.CAPABILITIES -> "能力中心"
}
