package com.autoscript.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import com.autoscript.domain.host.HostSummary

/**
 * 启动入口（launcher，manifest 在本模块，随库合并进 :app）。
 *
 * 接线方式（§6「UI 拆独立模块」的落点）：不 import `:app` 的任何类型 ——
 * 把 `application` 现转 `as? HostSummary`（`:domain` 读口，`AppShellApplication`
 * 实现），未实现即 [HomeState.UNWIRED] 如实显示「未接线」。
 * 刷新时机：onCreate 首读 + 每次 onResume 重读 —— 装配在 IO 域异步完成，
 * 冷启动首读大概率还是「装配中」，回前台/亮屏即是最自然的重读点；
 * 另有手动刷新按钮兜底（见 [HomeScreen]）。
 */
class MainActivity : ComponentActivity() {

    /** 首屏状态：compose 可观察单槽（Activity 持有，配置变更随重建重读，无跨进程共享诉求）。 */
    private var homeState: HomeState by mutableStateOf(HomeState.UNWIRED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeState = HomeState.read(hostSummary())
        setContent {
            MaterialTheme {
                HomeScreen(
                    state = homeState,
                    onRefresh = { homeState = HomeState.read(hostSummary()) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        homeState = HomeState.read(hostSummary())
    }

    private fun hostSummary(): HostSummary? = application as? HostSummary
}
