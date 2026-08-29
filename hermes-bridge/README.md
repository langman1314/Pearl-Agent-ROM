# Pearl Hermes Bridge

标准 MCP 2.0 Streamable HTTP bridge：Nexus 连接 `http://127.0.0.1:51338/mcp`，bridge 在 Debian ARM64 chroot 内调用官方 Hermes Agent。

## 安全边界

- 仅允许 numeric loopback 地址，不能监听局域网或公网；
- bridge JSON 只存非秘密配置；MCP 必须使用 Magisk 首次安装生成的 256-bit Bearer token；
- token 文件和 `DEEPSEEK_API_KEY` 都是 `0600`，不得进入 ROM/Git 或日志；
- SQLite 使用 WAL + FULL synchronous；
- 单 worker 执行长任务，避免手机内存/温度失控；
- bridge 重启时，仅恢复未开始的 queued 任务；已运行任务因副作用完成状态不明而 fail closed，不自动重放；
- 每个 Nexus session 映射到独立、规范化 Hermes session。

## MCP 工具

- `hermes_health`：健康和非秘密配置；
- `hermes_submit`：持久后台提交，立即返回 task id；
- `hermes_task_status`：查询状态和结果；
- `hermes_cancel`：取消排队任务或中断运行任务；
- `hermes_run`：短任务同步执行，长任务应使用 submit/status。

## Debian 安装

Hermes 固定到审计过的上游 commit：

```text
a2e19d484cb5591df8dafe667c93345b62d9bf06
```

建议在 chroot 内使用 Python 3.11 和 uv：

```bash
uv venv /opt/pearl-agent/venv --python 3.11
source /opt/pearl-agent/venv/bin/activate
uv pip install '/opt/pearl-agent/hermes-agent[mcp]'
uv pip install /opt/pearl-agent/hermes-bridge
install -Dm600 /dev/null /data/pearl-agent/hermes-home/.env
install -Dm600 config.example.json /data/pearl-agent/config/hermes-bridge.json
umask 077
python3 -c 'import secrets; print(secrets.token_hex(32))' \
  > /data/pearl-agent/config/mcp-token
```

Nexus 的 Hermes MCP server headers 必须保存为 `{"Authorization":"Bearer <mcp-token>"}`。Magisk 安装器会自动生成该 token；不得在 UI、日志或备份中明文展示。

Hermes 密钥文件：

```dotenv
DEEPSEEK_API_KEY=首次启动后写入
```

启动：

```bash
export HERMES_HOME=/data/pearl-agent/hermes-home
export HOME=/data/pearl-agent/hermes-home/home
pearl-hermes-bridge --config /data/pearl-agent/config/hermes-bridge.json
```

单测不需要模型密钥：

```bash
PYTHONPATH=src python -m unittest discover -s tests -v
```
