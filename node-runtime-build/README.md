# :node-runtime-build —— Node 24 源码构建管线（非 Gradle 模块，CI 产物）

目标（docs/framework-design.md §3/§19 M1）：

1. Node 24.x LTS 源码自建 `libnode.so`（fork/自持 nodejs-mobile 的 recipe 管线）。
2. **16KB 页对齐门禁**：ELF p_align 16KB，否则现代 16KB 页设备 dlopen 直接崩。
3. NDK r27d/r28、jar 剥离、zlib/gzip 静态化（约 26MB ABI 预算目标）。
4. 产物哈希登记（树哈希门禁）供 CI 复用与审计。

本目录只放**构建脚本、recipe 文档、产物校验工具**；不参与 Gradle 构建。

当前状态：占位 —— 由 `node-runtime-build` 模块 agent 填充（见任务分派）。

## 验收（M1 垂直切片）
- 在构建镜像上产出 `libnode.so`（arm64-v8a，p_align=16384，哈希登记）。
- 最小 `:node` 进程（:engine:node-process 的 main.cpp）执行 `console.log('hi')` 并把输出经桥回传 `:main`。

## 参考
- nodejs-mobile（已停更 18.20.4）: fork 其 recipe 管线并自行维护 LTS。
- 16KB 页对齐：Android 16+ / Pixel 16KB 页设备要求 ELF LOAD segments 对齐 16KB。