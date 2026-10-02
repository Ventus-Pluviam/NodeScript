# AutoScript 脚本 API（auto.*）

## Enumerations

| Enumeration | Description |
| ------ | ------ |
| [ErrCode](enumerations/ErrCode.md) | 机器可判错误码（与 :domain:core.ErrorCode.code 逐字一致）。 |

## Classes

| Class | Description |
| ------ | ------ |
| [AutojsError](classes/AutojsError.md) | 统一异常类型：脚本侧可 `catch (e) { if (e instanceof AutojsError) … }` 策略化。 由桥把 ErrPayload 还原为实例；模块封装层不得悄悄吞掉。 |
| [NotFoundError](classes/NotFoundError.md) | 未找到（UiSelector findOne 无匹配）——对齐 AutoJsPro v9 的 NotFoundError 语义（§7.6）。 |

## Interfaces

| Interface | Description |
| ------ | ------ |
| [AutoNamespace](interfaces/AutoNamespace.md) | `auto.*` 命名空间根：脚本 `require('auto')` 拿到的就是它（docs §12.1 唯一入口）。 |
| [CreateTimedTaskInput](interfaces/CreateTimedTaskInput.md) | 建任务输入（与 Kotlin `create` 载荷逐字段对齐；id 缺省服务端分配）。 |
| [ErrPayload](interfaces/ErrPayload.md) | 桥回包中的可序列化错误骨架（跨进程往返的唯一错误载体）。 |
| [TimedTaskInfo](interfaces/TimedTaskInfo.md) | 登记后的任务（与 Kotlin `list` 回显同形状）。 |

## Type Aliases

| Type Alias | Description |
| ------ | ------ |
| [ScreenGuarantee](type-aliases/ScreenGuarantee.md) | 屏幕契约（与 Kotlin ScreenGuarantee 逐字对齐：SCREEN_ON/ANY/SCREEN_OFF）。 |
| [TimedSchedule](type-aliases/TimedSchedule.md) | 调度计划（运行时不可变形态；cron 校验/推进以宿主 `CronTab` 为准，本文件只镜像）。 |
| [TimedScheduleInput](type-aliases/TimedScheduleInput.md) | - |

## Variables

| Variable | Description |
| ------ | ------ |
| [auto](variables/auto.md) | 命名空间根对象：挂各类能力；`install` 由 bootstrap/宿主在引擎就绪时注入桥 handler。 |
| [ERROR\_CODES](variables/ERROR_CODES.md) | 错误目录常量（与 enum 等值，供数组/字典场景）。 |
| [workManagerNS](variables/workManagerNS.md) | workManager 命名空间（scheduler 面：每日/一次性/cron 排期工具函数，运行态挂全局任务表）。 |

## Functions

| Function | Description |
| ------ | ------ |
| [errFromPayload](functions/errFromPayload.md) | - |

## References

### default

Renames and re-exports [auto](variables/auto.md)
