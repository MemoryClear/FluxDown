# syntax=docker/dockerfile:1
# FluxDown headless 服务器镜像：Web SPA（web/）+ fluxdown-server（native/server）。
#
# 构建上下文 = 仓库根目录（依赖根 .dockerignore 收窄上下文）：
#   docker build -f docker/server.Dockerfile -t fluxdown-server .
#
# 运行（首次启动 stderr 会打印管理 token，务必保存）：
#   docker run -d -p 17800:17800 -v fluxdown-data:/data fluxdown-server

# ── Stage 1: Web 前端（Vite SPA，bun 锁文件）──
FROM oven/bun:1 AS web
WORKDIR /src/web
COPY web/package.json web/bun.lock ./
RUN bun install --frozen-lockfile
COPY web/ ./
RUN bun run build

# ── Stage 2: Rust 服务器（workspace 成员，仅编译 fluxdown_server）──
# Linux 侧全 rustls（无 openssl），SQLite 由 sqlx 捆绑编译，无额外系统依赖。
FROM rust:1-bookworm AS server
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY native/ native/
# cache mount：本地重复构建增量编译；registry 缓存避免重复下载
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    cargo build --release --locked -p fluxdown_server \
    && cp target/release/fluxdown-server /usr/local/bin/fluxdown-server

# ── Stage 3: 运行时（debian-slim + ca-certificates，rustls 读系统根证书）──
FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=server /usr/local/bin/fluxdown-server /app/fluxdown-server
COPY --from=web /src/web/dist /app/web
# FLUXDOWN_BIND / FLUXDOWN_DATABASE_URL / FLUXDOWN_DEMO 等见 native/server/src/config.rs
ENV FLUXDOWN_BIND=0.0.0.0:17800 \
    FLUXDOWN_WEBROOT=/app/web \
    FLUXDOWN_DATA_DIR=/data
VOLUME /data
EXPOSE 17800
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD curl -fsS "http://127.0.0.1:${FLUXDOWN_BIND##*:}/ping" || exit 1
ENTRYPOINT ["/app/fluxdown-server"]
