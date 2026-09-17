# AutoScript :node-runtime-build —— Node 24（arm64-android）自建镜像（docs §3/§16 门禁）
#
# 构建:
#   docker build -t autoscript/node-runtime-build node-runtime-build
# 取产物（本机无 Android SDK 时的验证门就是它）:
#   id=$(docker create autoscript/node-runtime-build)
#   docker cp "$id":/build/out ./out && docker rm "$id"
#
# 缓存策略：VERSIONS.env 在 COPY 指令中，任一版本变更即触发全链重跑（一次提交一次全量回归）。

# ── builder：完整工具链 + 源码 + NDK + work 目录，只服务构建，不进入最终镜像 ──
FROM ubuntu:24.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        curl \
        unzip \
        xz-utils \
        python3 \
        binutils \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# 版本矩阵是构建的单一事实来源：改了它 → 缓存失效 → 全量重跑（符合 RISKS 的升级纪律）
COPY VERSIONS.env scripts/ /build/

# fetch-and-build.sh 内含：下载校验 → 交叉编译 → strip → 16KB/平台/ABI 门禁 → sha256 基表
RUN bash /build/scripts/fetch-and-build.sh

# ── 最终阶段：只保留产物（工具链/NDK/源码/work 全部丢弃，镜像体积收敛） ──
FROM ubuntu:24.04 AS artifact

ENV LANG=C.UTF-8

COPY --from=builder /build/out /build/out

# 默认行为：列出产物与基表（CI 统计/发布审计用）
CMD ["/bin/bash", "-c", "ls -la /build/out && echo '---' && cat /build/out/SHASUMS256"]