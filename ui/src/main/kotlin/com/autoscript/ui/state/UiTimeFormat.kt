package com.autoscript.ui.state

import java.time.format.DateTimeFormatter

/**
 * 日志行的时刻格式（`HH:mm:ss`）。
 *
 * 全仓**只有这一份**：控制台行、悬浮日志窗、依赖页日志大框、日志管理页四处的
 * 行首时间戳必须是同一个写法 —— 同一个 App 里不该有两种时间格式。
 */
internal val HH_MM_SS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
