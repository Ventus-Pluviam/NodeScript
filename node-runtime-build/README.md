# :node-runtime-build —— Node 24（arm64·Android）自建管线

非 Gradle 模块（docs §3）：产出 **16KB 对齐的 `libnode.so`** 与 `node` 可执行，供 `:bridge:native`（dlopen + `node::Start`）与 §844 垂直切片使用。**本机无 Android SDK**，构建+门禁都在本管线内完成（Docker 或 CI），不占 Gradle assemble。

## 为什么自建（短版）

| 上游方案 | 结论 |
|---|---|
| 官方 nodejs-mobile | 停更于 2021（最后推送 2021-10-27），Node 18.x 已 EOL，ELF 4KB 对齐 → 16KB 页设备 dlopen 崩 |
| 自建（本管线） | Node 24 LTS 长期支持 + `--dest-os=android` + r28 默认 16KB 对齐 + 全链校验 |

## 冻结版本矩阵（`VERSIONS.env`，单一事实来源）

| 项 | 值 | 校验 |
|---|---|---|
| Node.js | **v24.21.0**（Krypton，24 LTS 当前最新） | 官方 SHASUMS256.txt（sha256 逐字） |
| Android NDK | **r28c** = `28.2.13676358` | repository2-3.xml（size + sha1，与 sdkmanager 同源） |
| 目标 ABI | `aarch64-linux-android26`（= 根 catalog 冻结 minSdk 26） | — |
| 共享库 ABI | `libnode.so.137`（NODE_MODULE_VERSION=137） | 门禁断言 `.so.<137>` 存在 |
| 构建工具 | ubuntu:24.04 + build-essential/python3/curl/unzip/xz | Dockerfile 固化 |

## 快速开始

```bash
docker build -t autoscript/node-runtime-build node-runtime-build
id=$(docker create autoscript/node-runtime-build)
docker cp "$id":/build/out ./out && docker rm "$id"
ls out/          # node  libnode.so.137*  SHASUMS256  config.gypi  config.mk
```

## 交付物

| 产物 | 用途 |
|---|---|
| `out/node` | 垂直切片：adb push 到设备 `LD_LIBRARY_PATH=. ./node -e 'console.log(process.platform, process.arch, process.versions.modules)'` |
| `out/libnode.so.137`（+软链） | `:bridge:native` dlopen 宿主引擎；**16KB LOAD 对齐** |
| `out/config.gypi` + `out/config.mk` | 平台契约审计（config.gypi 的 JSON `variables.OS=android` / `node_shared=true`） |
| `out/SHASUMS256` | 产物基表（发布/增量比对） |

## 硬门禁（`check-alignment.sh`，build 尾部必过）

1. **16KB**：每个 `LOAD` 段 `align >= 2**14`（`llvm-objdump -p`；未对齐在新设备 dlopen 即崩）——r28 链接器默认满足，门禁是最终仲裁。
2. **平台**：`config.gypi` 的 `variables.OS == "android"` 且 `node_shared == true`（`config.mk` 只含 `BUILDTYPE`/`NODE_TARGET_TYPE`，CLI 参数不回显；JSON 键才是契约证据）。
3. **ABI**：存在 `libnode.so.<137>`（预编译 `.node` addon 必须同号，见 RISKS §4）。

## 升级流程（一次提交）

1. 改 `VERSIONS.env`（新 `NODE_VERSION` / `NODE_SHA256` 取自 `nodejs.org/dist/<v>/SHASUMS256.txt`；必要时一并评估 NDK）。
2. 跑 Docker 全量重建，门禁 + 基表变化即回归证据。
3. 交垂直切片验证（§844 里程碑：最小 `:node` 进程 `console.log` 回传）后合入。

## 风险与决策记录

见 [RISKS.md](RISKS.md)：上游停更自持、r28c 锁版（r27d 备选需手工对齐旗标但 configure 不读 LDFLAGS）、ICU 面、ABI 锁步 137、16KB 排查路径、`--openssl-no-asm` 取舍、minSdk 对齐纪律。