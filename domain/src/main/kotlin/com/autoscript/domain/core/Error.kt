package com.autoscript.domain.core

/**
 * 错误目录（草案，对齐 docs §7.6）。
 * 完整清单随各模块实现补充，补充需提交 + 更新 §7.6。
 */
enum class ErrorCode(val code: String, val summary: String) {
    ERR_TIMEOUT("ERR_TIMEOUT", "操作超时"),
    ERR_STALE_HANDLE("ERR_STALE_HANDLE", "句柄已失效（generation 不匹配/已 dispose）"),
    ERR_PERMISSION_DENIED("ERR_PERMISSION_DENIED", "能力未授权或被降级"),
    ERR_SERVICE_DISABLED("ERR_SERVICE_DISABLED", "底层系统服务未启用"),
    ERR_SCREEN_LOCKED("ERR_SCREEN_LOCKED", "屏幕锁定，无法截取"),
    ERR_BLACK_FRAME("ERR_BLACK_FRAME", "FLAG_SECURE 窗口，返回黑帧"),
    ERR_CAPTURE_DENIED("ERR_CAPTURE_DENIED", "截屏授权被拒"),
    ERR_ENGINE_STOPPED("ERR_ENGINE_STOPPED", "引擎被停止"),
    ERR_ENGINE_CRASHED("ERR_ENGINE_CRASHED", "引擎进程崩溃"),
    ERR_NOT_IMPLEMENTED("ERR_NOT_IMPLEMENTED", "本平台不支持该能力"),
    ERR_INVALID_PARAM("ERR_INVALID_PARAM", "参数非法"),
    ERR_FILE_NOT_FOUND("ERR_FILE_NOT_FOUND", "文件不存在"),
    ERR_FILE_EXISTS("ERR_FILE_EXISTS", "文件/产物已存在"),
    ERR_DISK_FULL("ERR_DISK_FULL", "磁盘空间不足"),
    ERR_NOT_FOUND("ERR_NOT_FOUND", "未找到（如 UiSelector 无匹配）"),

    // §10.8 npm 专用码（P0 依赖管理）
    ERR_NPM_SPAWN_BLOCKED("ERR_NPM_SPAWN_BLOCKED", "非批准 spawn 被拦截"),
    ERR_NOT_SUPPORTED("ERR_NOT_SUPPORTED", "本平台不支持该特性（如 git: 依赖）"),
    ERR_REGISTRY_UNAVAILABLE("ERR_REGISTRY_UNAVAILABLE", "包注册表不可用"),
    ERR_NPM_LOWMEM("ERR_NPM_LOWMEM", "安装会话内存不足"),
    ERR_IO("ERR_IO", "文件系统/归档读写失败"),
    /**
     * 宿主内部错误（2026-10-11 批 97，外审 M4）：**代码有 bug**，不是调用方参数错。
     *
     * 为什么必须有这一档：此前桥路由把任何未预料的 `Exception` 折成
     * `ERR_INVALID_PARAM` —— 脚本 `catch (e) { e.is('ERR_INVALID_PARAM') }` 会把
     * 「宿主崩了」当成「你参数写错了」去改参数，而真相在宿主堆栈里。这是「响亮失败」
     * 纪律的反面：不是把 bug 说清，是把 bug 说成别人的错。
     *
     * 与 [ERR_INVALID_PARAM] 的分工：前者是**能读懂的事实**（参数、时序、状态不对），
     * 后者是**读不懂的事实**（宿主内部抛了说不清的东西）。脚本侧应把后者当
     * 「宿主有问题，详情找日志」而不是「重试我的代码」。
     */
    ERR_INTERNAL("ERR_INTERNAL", "宿主内部错误（代码缺陷，非调用方问题）");

    fun message(detail: String? = null): String =
        "$code: $summary" + (detail?.let { " —— $it" } ?: "")
}

/** 领域统一异常类型：可被脚本侧 instanceof AutojsError，可策略化 try/catch（§12.1）。 */
class AutojsException(
    val error: ErrorCode,
    detail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(error.message(detail), cause)