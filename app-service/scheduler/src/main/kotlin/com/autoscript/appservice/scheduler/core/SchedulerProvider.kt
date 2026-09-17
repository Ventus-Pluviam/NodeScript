package com.autoscript.appservice.scheduler.core

/**
 * 触发源注册 SPI（docs/framework-design.md §8.6 / §13 Provider·Strategy）：
 * 同一接口可平切 WorkManager 之外的实现（保活场景自持 alarm + 注册 receiver）。
 * Android 实现（AlarmManager setExactAndAllowWhileIdle → receiver）在 :app 装配层，
 * 本模块只依赖 :domain，不引入 android.*。
 */
interface SchedulerProvider {

    /** 在 targetFireAtMillis 触发（实现方自含 wakeAheadMs 预拉语义）。返回可取消句柄。 */
    suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle

    /** 取消已注册触发。 */
    suspend fun cancelTrigger(handle: TriggerHandle)

    /** 预热线宽：诚实守时契约的提前量（§8.6：scheduledAt - 60s 先拉起进程）。 */
    val wakeAheadMillis: Long
        get() = 60_000
}

/** 注册返回的触发句柄（实现内部是 requestCode / component 引用）。 */
fun interface TriggerHandle {
    /** 幂等取消：重复 cancel 不抛错。 */
    suspend fun cancel()
}