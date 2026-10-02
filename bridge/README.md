# `bridge/` —— 三种构建体系并置（不是三个模块）

> 设计章节：`docs/design/07-bridge.md` §7（Java 侧 + native addon）、`docs/design/12-js-api.md` §12（facade）、
> `docs/design/09-capabilities.md` §9.2（图像面）。
> 本文件只回答一件事：**这五个子目录各是什么、怎么各自构建与测试**；跨模块契约以 `:domain` 为准。

`bridge/` 底下**五种东西**，其中只有三种是 Gradle 模块 —— 把它们当同一类会走错门：

| 子目录 | 是什么 | 构建体系 | 怎么构建 / 测 |
|---|---|---|---|
| `java/` | Gradle 模块 `:bridge:java`：Kotlin Router / RequestRegistry(TTL) / HandleRegistry(generation) / EventBus / transports（8 个文件） | Gradle（`autoscript.jvm` 纯 JVM） | `./gradlew :bridge:java:test` |
| `native/` | Gradle 模块 `:bridge:native`：C++ N-API addon 控制面（`src/main/cpp/bridge_addon.cc`，**零 Kotlin 源码**） | Gradle 模块壳 + NDK 交叉编译（产物不在 git） | 见下「native 两个模块怎么编」 |
| `image/` | Gradle 模块 `:bridge:image`：C++ 图像分析管线 `libopencv.so`（四个 TU，零 JNI 的计算核三件 + 装载面一件） | 同上 | 见下 |
| `js/` | **npm 包** `@autoscript/bridge-js`（TS facade SDK，脚本侧导入名 `auto`）—— **不是 Gradle 模块**，禁 Gradle 反向依赖 | npm（`tsc` + `node --test`） | `npm --prefix bridge/js ci && npm --prefix bridge/js test` |
| `schema/` | **生成物源**：`wire.schema.json`（wire 面单一事实来源）+ `generate.mjs`（双发射器） | Node 脚本（零依赖） | `npm --prefix bridge/js run gen:wire`（生成物入库，漂移即红） |

## native 两个模块怎么编（**本机只能交叉编译验证，CI 才是权威**）

两个 C++ 模块都**没有 Kotlin 源码**，产物（`.so` / `.node`）**不在 git**：

| 产物 | 由谁编 | 门禁 | CI |
|---|---|---|---|
| `bridge_native.node`（addon）+ `noden`（宿主） | `engine/node-process/scripts/build-native.sh`（读 `bridge/native/src/main/cpp/bridge_addon.cc`） | 16KB LOAD 对齐 + 偏移/虚拟地址同余 + `NEEDED` 闭包白名单 + `node::Start` 三方符号对表 | **CI 不编 addon**：本机按需跑（需 `ANDROID_NDK_HOME` / `NODE_SRC` / `LIBNODE` 三个 env，缺即报错） |
| `libopencv.so` | `node-runtime-build/scripts/build-opencv.sh` | 16KB 对齐 + AArch64/ET_DYN + NEEDED 白名单 + JNI 四符号在场 | `.github/workflows/image-native.yml`（20 分钟级，改 `bridge/image/**` 即跑） |

**别把 `:bridge:native` 的「被引擎宿主引用」读成 Gradle 依赖边**：那是运行期 `dlopen`/`.so` 装载，
`ModuleGraphTest` 的允许集里它和 `:bridge:image` 都是空集（见 §6 模块表脚注）。

## 图像面的宿主机语义门禁（**不在 `src/test` 惯例位**）

`image/test/cpp/`（`run-host-tests.sh` + 九个 `host_*_test.cpp`，**422 例**）把计算核三个 TU 与**同 commit**
的 OpenCV 4.14.0 静态库链成 x86_64 可执行文件跑像素断言 —— 它证明的是「判读对」，NDK `-fsyntax-only`
只证明「编得过」，两者互不替代。

- 位置说明：它**刻意不在 `image/src/test`**（那是 Gradle 的 JVM 测试源集位置，本模块没有 JVM 代码）；
  C++ 宿主门禁住 `test/cpp/`，由 `image-native.yml` 直接 `bash` 调用。
- 用法：`bash bridge/image/test/cpp/run-host-tests.sh <opencv 源码目录>`（另需 `OCV_HOST_BUILD`；两者无缺省）。
- 新算子落地时**先补它的 host 断言再上真机** —— 理由与教训见 `docs/design/09-capabilities.md` §9.2。

## 边界（一句话版）

- `:bridge:java` 只依赖 `:domain`，禁 UI；`js/` 只认 npm 依赖，**禁 Gradle 反向**；
- 两个 C++ 模块的「被引用」是运行期装载，不是编译期边；
- 生成物（`js/src/generated/wire-types.ts`、`domain/.../generated/WireMethods.kt`、`docs/api/**`）**入库**，
  改源不重跑生成器 = CI 的零 diff 门红。
