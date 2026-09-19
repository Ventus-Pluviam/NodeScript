package com.autoscript

import android.app.Application

/**
 * 启动装配（docs/framework-design.md §4.1 Composition Root，手写 DI，不用 Hilt）：
 * P0 先给壳（Application 注册 + manifest 挂载），各装配（Router/handlers/
 * Scheduler/AlarmReceiver/FGS/PermissionCenter 注入）随 :app 落地逐步补齐。
 */
class AppShellApplication : Application()
