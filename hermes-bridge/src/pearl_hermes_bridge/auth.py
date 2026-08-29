from __future__ import annotations

import secrets
import stat
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from mcp.server.auth.provider import AccessToken


class SharedSecretVerifier:
    """MCP bearer verifier backed by one root-provisioned local token."""

    def __init__(self, token: str):
        token = token.strip()
        if len(token) < 43:
            raise ValueError("MCP shared secret must carry at least 256 bits of entropy")
        self._token = token

    @classmethod
    def from_file(cls, path: str | Path) -> "SharedSecretVerifier":
        token_path = Path(path)
        if not token_path.is_file():
            raise FileNotFoundError(f"MCP shared secret file not found: {token_path}")
        mode = stat.S_IMODE(token_path.stat().st_mode)
        if mode & 0o077:
            raise PermissionError("MCP shared secret file must not be group/world accessible")
        return cls(token_path.read_text(encoding="utf-8"))

    async def verify_token(self, token: str) -> "AccessToken | None":
        if not secrets.compare_digest(self._token, token):
            return None
        from mcp.server.auth.provider import AccessToken

        return AccessToken(
            token=token,
            client_id="pearl-nexus",
            scopes=["pearl.hermes"],
            subject="local-nexus",
            claims={"iss": "pearl-local"},
        )
