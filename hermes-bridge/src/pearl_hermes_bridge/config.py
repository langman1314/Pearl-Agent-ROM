from __future__ import annotations

import ipaddress
import json
from dataclasses import dataclass, field, fields
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class BridgeConfig:
    host: str = "127.0.0.1"
    port: int = 51338
    path: str = "/mcp"
    provider: str = "deepseek"
    model: str = "deepseek-v4-pro"
    max_iterations: int = 80
    run_budget_seconds: float = 900.0
    max_goal_chars: int = 32_000
    max_context_chars: int = 64_000
    max_result_chars: int = 120_000
    workdir: str = "/data/pearl-agent/workspace"
    state_db: str = "/data/pearl-agent/state/hermes-bridge.db"
    shared_secret_file: str = "/data/pearl-agent/config/mcp-token"
    session_prefix: str = "pearl-nexus"
    enabled_toolsets: list[str] = field(
        default_factory=lambda: [
            "terminal", "file", "web", "skills", "memory", "delegation", "todo"
        ]
    )
    disabled_toolsets: list[str] = field(default_factory=list)

    @classmethod
    def from_file(cls, path: str | Path) -> "BridgeConfig":
        config_path = Path(path)
        if not config_path.is_file():
            raise FileNotFoundError(f"Bridge config not found: {config_path}")
        raw: Any = json.loads(config_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise ValueError("Bridge config root must be a JSON object")
        allowed = {item.name for item in fields(cls)}
        unknown = sorted(set(raw) - allowed)
        if unknown:
            raise ValueError(f"Unknown bridge config keys: {', '.join(unknown)}")
        return cls(**raw).validated()

    def transport_options(self) -> dict[str, object]:
        validated = self.validated()
        return {
            "transport": "streamable-http",
            "host": validated.host,
            "port": validated.port,
            "streamable_http_path": validated.path,
            "stateless_http": True,
        }

    def validated(self) -> "BridgeConfig":
        try:
            address = ipaddress.ip_address(self.host)
        except ValueError as error:
            raise ValueError("Bridge host must be a numeric loopback address") from error
        if not address.is_loopback:
            raise ValueError("Bridge may only listen on a loopback address")
        if not 1 <= self.port <= 65535:
            raise ValueError("Bridge port must be between 1 and 65535")
        if not self.path.startswith("/"):
            raise ValueError("MCP path must start with /")
        if not self.provider.strip() or not self.model.strip():
            raise ValueError("Hermes provider and model are required")
        if not self.shared_secret_file.strip():
            raise ValueError("MCP shared_secret_file is required")
        if not 1 <= self.max_iterations <= 500:
            raise ValueError("max_iterations must be between 1 and 500")
        if not 10 <= self.run_budget_seconds <= 86_400:
            raise ValueError("run_budget_seconds must be between 10 and 86400")
        for value, name in (
            (self.max_goal_chars, "max_goal_chars"),
            (self.max_context_chars, "max_context_chars"),
            (self.max_result_chars, "max_result_chars"),
        ):
            if value < 1:
                raise ValueError(f"{name} must be positive")
        return self
