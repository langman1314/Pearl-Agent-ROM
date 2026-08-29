from __future__ import annotations

import argparse
import atexit
import json
from pathlib import Path

from mcp.server import MCPServer
from mcp.server.auth.settings import AuthSettings

from . import __version__
from .auth import SharedSecretVerifier
from .config import BridgeConfig
from .runtime import HermesRuntime, TaskWorker, normalize_session_id, task_json
from .store import TaskStore


def _loopback_url(config: BridgeConfig, include_path: bool = False) -> str:
    authority = f"[{config.host}]" if ":" in config.host else config.host
    suffix = config.path if include_path else ""
    return f"http://{authority}:{config.port}{suffix}"


def create_server(config: BridgeConfig) -> tuple[MCPServer, TaskStore, TaskWorker]:
    config = config.validated()
    token_verifier = SharedSecretVerifier.from_file(config.shared_secret_file)
    auth = AuthSettings(
        issuer_url=_loopback_url(config),
        resource_server_url=_loopback_url(config, include_path=True),
        required_scopes=["pearl.hermes"],
    )
    runtime = HermesRuntime(config)
    store = TaskStore(config.state_db)
    worker = TaskWorker(config, store, runtime)
    worker.start()

    server = MCPServer(
        "pearl-hermes",
        version=__version__,
        instructions=(
            "Background Hermes specialist for Pearl Nexus. Submit long tasks, then poll status. "
            "Use hermes_run only for short work that can finish in the current MCP call."
        ),
        log_level="INFO",
        auth=auth,
        token_verifier=token_verifier,
    )

    @server.tool()
    def hermes_health() -> str:
        """Return bridge health and the configured non-secret Hermes runtime settings."""
        return json.dumps(
            {
                "ok": True,
                "version": __version__,
                "provider": config.provider,
                "model": config.model,
                "queue_mode": "single-worker-durable-sqlite",
                "state_db": config.state_db,
            },
            ensure_ascii=False,
        )

    @server.tool()
    def hermes_submit(goal: str, session_id: str = "main", context: str = "") -> str:
        """Submit a durable background task. Poll hermes_task_status with the returned task_id."""
        return task_json(worker.submit(session_id, goal, context), include_result=False)

    @server.tool()
    def hermes_task_status(task_id: str) -> str:
        """Return queued/running/completed/failed/cancelled state and final result for a task."""
        try:
            return task_json(store.get(task_id.strip()))
        except KeyError:
            return json.dumps({"error": "task_not_found", "task_id": task_id})

    @server.tool()
    def hermes_cancel(task_id: str) -> str:
        """Cancel a queued task or request a hard interrupt for a running Hermes task."""
        try:
            return task_json(worker.cancel(task_id.strip()))
        except KeyError:
            return json.dumps({"error": "task_not_found", "task_id": task_id})

    @server.tool()
    def hermes_run(goal: str, session_id: str = "main", context: str = "") -> str:
        """Run a short Hermes task synchronously. Prefer hermes_submit for long work."""
        if not goal.strip():
            raise ValueError("goal must not be blank")
        if len(goal) > config.max_goal_chars or len(context) > config.max_context_chars:
            raise ValueError("goal or context is too long")
        resolved = normalize_session_id(config.session_prefix, session_id)
        return runtime.run(resolved, goal, context)

    closed = False

    def shutdown() -> None:
        nonlocal closed
        if closed:
            return
        # Never close the shared SQLite connection under a still-running
        # worker. If a Hermes tool ignores interruption, the OS will reclaim
        # the connection when the process exits and restart recovery will mark
        # that in-flight task ambiguous rather than replaying it.
        if worker.stop():
            store.close()
            closed = True

    atexit.register(shutdown)
    return server, store, worker


def main() -> None:
    parser = argparse.ArgumentParser(description="Pearl Nexus to Hermes MCP bridge")
    parser.add_argument(
        "--config",
        default="/data/pearl-agent/config/hermes-bridge.json",
        help="Path to non-secret bridge JSON configuration",
    )
    args = parser.parse_args()
    config = BridgeConfig.from_file(Path(args.config))
    server, _, _ = create_server(config)
    server.run(
        transport="streamable-http",
        host=config.host,
        port=config.port,
        streamable_http_path=config.path,
    )


if __name__ == "__main__":
    main()
