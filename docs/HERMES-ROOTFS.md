# Hermes Debian ARM64 rootfs

## 固定基线

| 项目 | 固定值 |
|---|---|
| Debian | 12 Bookworm / ARM64 minbase |
| Debian snapshot | `20250601T000000Z` |
| Python | Debian 原生 Python 3.11 |
| Hermes Agent | commit `a2e19d484cb5591df8dafe667c93345b62d9bf06` |
| Hermes package version | `0.20.6` |
| MCP SDK | `2.0.0`，由 Hermes `uv.lock` 固定 |
| Pearl Hermes Bridge | `0.1.0` |
| uv bootstrap | `0.8.11` / ARM64 wheel SHA-256 `0a7fcbe71cc5402b7c3d4c381f9b970a455d8ccc2a43ee2ce5ac2b617ec0534c` |

选择 Bookworm 是为了使用发行版原生 Python 3.11；Hermes 官方要求 Python `>=3.11,<3.14`。构建过程中必须使用 Hermes 上游仓库的 `uv.lock --frozen`，禁止无锁解析依赖。

## 主机构建要求

构建必须在 Linux 或 WSL2 中以 root 运行。Ubuntu/Debian 主机需要：

```bash
sudo apt-get update
sudo apt-get install -y \
  arch-test binutils curl debian-archive-keyring mmdebstrap qemu-user-static \
  binfmt-support rsync zstd git ca-certificates
```

构建命令：

```bash
cd /mnt/d/生活问答/Pearl-Agent-ROM
sudo bash scripts/build-hermes-rootfs.sh
```

可选参数：

```text
--hermes-source PATH  指定已校验 Hermes checkout
--output-dir PATH     指定产物目录
--keep-rootfs         保留临时 rootfs 供人工审查
```

没有本地 Linux/WSL 时，手动运行 `.github/workflows/build-phone-artifacts.yml`。该 workflow：

1. 只有 `contents: read` 权限；
2. 固定 checkout/upload action SHA、Hermes commit、zstd commit 和 Ubuntu apt snapshot；
3. 在 Ubuntu 24.04 runner 上只从固定 snapshot 安装 host toolchain，并保存精确包版本；
4. 在两个独立目录交叉编译 static ARM64 zstd，要求 SHA-256 一致且 ELF 无 `INTERP`；
5. 组装 Magisk module，上传 rootfs、module、依赖清单、provenance 和全量哈希；
6. artifact 保留 14 天，二进制仍不进入 Git。

## 构建门禁

脚本会在开始前验证：

1. Hermes checkout 的 `HEAD` 必须精确等于固定 commit；
2. Hermes tracked files 必须干净；
3. `uv.lock` 与 bridge `pyproject.toml` 必须存在；
4. ARM64 `uv` wheel 必须匹配固定 SHA-256，并只安装到可删除的 bootstrap venv；不得修改 PEP 668 管理的系统 Python；
5. 监听服务依赖、Hermes `AIAgent` 与 MCP 2.0 必须能在 ARM64 chroot 中真实导入；
6. bridge CLI 必须能启动到 `--help`；
7. 压缩包内必须包含 Python 3.11 和 `pearl-hermes-bridge`。

## 输出

默认输出到 gitignored 的 `artifacts/rootfs/`：

```text
pearl-hermes-bookworm-arm64-a2e19d484cb5.tar.zst
pearl-hermes-bookworm-arm64-a2e19d484cb5.tar.zst.sha256
pearl-hermes-bookworm-arm64-a2e19d484cb5.build.json
pearl-hermes-bookworm-arm64-a2e19d484cb5.dpkg.tsv
pearl-hermes-bookworm-arm64-a2e19d484cb5.python-freeze.txt
```

rootfs 二进制、依赖清单和生成哈希不进入 Git；最终 ROM 发布时将它们纳入离线 release manifest。

## 重启与副作用策略

SQLite 中从未开始的 `queued` 任务可以在 bridge 重启后继续执行。进程崩溃时已经处于 `running` 的任务不得自动重放，因为 Hermes 可能已经写文件、执行命令或发起网络请求；bridge 会把这种任务标成 failed/ambiguous，由 Nexus 展示状态并在用户或规划器确认后重新提交。

## `/data` 布局

rootfs 自带空模板，实际运行时 Magisk 模块将持久目录绑定到 chroot 内：

```text
/data/pearl-agent/config/hermes-bridge.json   非秘密 bridge 配置
/data/pearl-agent/hermes-home/.env            API key，仅 0600
/data/pearl-agent/hermes-home/config.yaml     Hermes 行为配置
/data/pearl-agent/state/hermes-bridge.db       持久任务队列
/data/pearl-agent/workspace/                   Hermes 工作目录
/data/pearl-agent/logs/                        supervisor/bridge 日志
```

`DEEPSEEK_API_KEY` 只能由首次启动 provisioner 写入 `.env`，不能预置进 rootfs、Magisk ZIP 或 Git。
