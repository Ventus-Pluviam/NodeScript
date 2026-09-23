package com.autoscript.appservice.packager

import java.nio.file.Files
import java.nio.file.Path

/**
 * `Files.writeString`/`readString` 的测试专用等价物（JDK11 API，**android.jar 桩面不提供**
 * —— gradle/CI 的 `testDebugUnitTest` 编译期以 android.jar 为据，直接引用编译不过）。
 * 语义与 JDK 版一致：默认 UTF-8。主源同纪律见 `ProjectIndex` 的同名注释。
 */
internal fun writeString(path: Path, text: String): Path =
    Files.write(path, text.toByteArray(Charsets.UTF_8))

internal fun readString(path: Path): String =
    String(Files.readAllBytes(path), Charsets.UTF_8)
