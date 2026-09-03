#!/usr/bin/env python3
"""Secret-safe MCP acceptance probe executed inside the Pearl Debian chroot."""
from __future__ import annotations

import argparse
import asyncio
import http.client
import json
import os
import re
import stat
from pathlib import Path
from urllib.parse import urlsplit

EXPECTED_TOOLS = {
    "hermes_health",
    "hermes_submit",
    "hermes_task_status",
    "hermes_cancel",
    "hermes_run",
}
TOKEN_RE = re.compile(r"[0-9a-f]{64}")


def load_token(path: str | Path) -> str:
    token_path = Path(path)
    if not token_path.is_file():
        raise RuntimeError("MCP token file is missing")
    if stat.S_IMODE(token_path.stat().st_mode) != 0o600:
        raise RuntimeError("MCP token file mode is not 0600")
    token = token_path.read_text(encoding="utf-8").strip()
    if not TOKEN_RE.fullmatch(token):
        raise RuntimeError("MCP token is not exactly 64 lowercase hex characters")
    return token


def validate_url(url: str) -> tuple[str, int, str]:
    parsed = urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "::1"}:
        raise RuntimeError("MCP probe URL must be numeric loopback HTTP")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise RuntimeError("MCP probe URL contains forbidden authority/query components")
    if parsed.path != "/mcp":
        raise RuntimeError("MCP probe path must be /mcp")
    return parsed.hostname, parsed.port or 80, parsed.path


def unauthenticated_status(url: str, authorization: str | None) -> int:
    host, port, path = validate_url(url)
    body = json.dumps(
        {"jsonrpc": "2.0", "id": "auth-probe", "method": "tools/list", "params": {}},
        separators=(",", ":"),
    ).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Connection": "close",
    }
    if authorization is not None:
        headers["Authorization"] = authorization
    connection = http.client.HTTPConnection(host, port, timeout=10)
    try:
        connection.request("POST", path, body=body, headers=headers)
        response = connection.getresponse()
        response.read(1_048_577)
        return response.status
    finally:
        connection.close()


async def authenticated_tools(url: str, token: str) -> tuple[str, set[str]]:
    from mcp.client.session import ClientSession
    from mcp.client.streamable_http import streamable_http_client
    from mcp.shared._httpx_utils import create_mcp_http_client

    client = create_mcp_http_client(headers={"Authorization": f"Bearer {token}"})
    async with client:
        async with streamable_http_client(url, http_client=client) as streams:
            async with ClientSession(*streams) as session:
                initialized = await session.initialize()
                listed = await session.list_tools()
                return initialized.protocol_version, {tool.name for tool in listed.tools}


def run_probe(url: str, token: str) -> list[str]:
    no_auth = unauthenticated_status(url, None)
    if no_auth not in {401, 403}:
        raise RuntimeError(f"Unauthenticated request was not rejected: HTTP {no_auth}")
    wrong = "0" * 64 if token != "0" * 64 else "1" * 64
    wrong_auth = unauthenticated_status(url, f"Bearer {wrong}")
    if wrong_auth not in {401, 403}:
        raise RuntimeError(f"Wrong-token request was not rejected: HTTP {wrong_auth}")
    protocol, tools = asyncio.run(authenticated_tools(url, token))
    missing = EXPECTED_TOOLS - tools
    unexpected = tools - EXPECTED_TOOLS
    if missing or unexpected:
        raise RuntimeError(
            f"Hermes MCP tool set mismatch: missing={sorted(missing)} unexpected={sorted(unexpected)}"
        )
    return [
        f"unauthenticated_status={no_auth}",
        f"wrong_token_status={wrong_auth}",
        "authenticated=true",
        f"protocol={protocol}",
        f"tools={','.join(sorted(tools))}",
        "secrets_output=false",
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description="Probe authenticated Pearl Hermes MCP")
    parser.add_argument("--url", default="http://127.0.0.1:51338/mcp")
    parser.add_argument(
        "--token-file", default="/data/pearl-agent/config/mcp-token"
    )
    args = parser.parse_args()
    validate_url(args.url)
    token = load_token(args.token_file)
    lines = run_probe(args.url, token)
    output = "\n".join(lines) + "\n"
    if token in output:
        raise RuntimeError("Internal error: token reached probe output")
    print(output, end="")


if __name__ == "__main__":
    main()
