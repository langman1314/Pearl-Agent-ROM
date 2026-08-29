# Pearl Agent Magisk chroot module

## Scope

The module is a `/data` payload and process supervisor. It does **not** flash or modify:

- `boot` / `init_boot`;
- `vbmeta` / AVB descriptors;
- `super` or any logical partition;
- `preloader`;
- `efuse`.

Root installation and boot-image provenance remain separate gates. The module must not be installed until the final Magisk/LSPosed compatibility and rollback review is complete.

## Persistent layout

```text
/data/adb/pearl-agent/
├── rootfs/                 active Debian ARM64 rootfs
├── rootfs.previous/        one-version rollback anchor
├── data/
│   ├── config/
│   ├── hermes-home/
│   ├── logs/
│   ├── state/
│   └── workspace/
├── run/
├── disabled                optional runtime kill switch
└── installed-build.json
```

The rootfs can be replaced atomically without overwriting user data, API keys, Hermes sessions, memory, or work files.

## Boot lifecycle

1. `post-fs-data.sh` validates the rootfs and prepares bind mounts:
   - persistent data at `/data/pearl-agent` inside chroot;
   - `/dev`, `proc`, `sysfs`, `devpts`, and an isolated tmpfs `/run`;
   - generated `/etc/resolv.conf` from Android DNS properties, with conservative fallbacks.
2. `service.sh` waits for `sys.boot_completed=1`.
3. It launches only `pearl-hermes-bridge-wrapper` through `chroot` with a minimal explicit environment.
4. If the process exits quickly, the supervisor backs off `2 → 5 → 15 → 30 → 60 → 120` seconds.
5. More than five short crashes in ten minutes opens a 30-minute fuse to protect battery and temperature.
6. A runtime that stays alive for five minutes resets crash counters.

No Android application partition is mounted into the chroot. The Hermes terminal is intentionally scoped to its persistent workspace unless a later, explicit Android-control bridge is approved.

## Installer safety

`customize.sh`:

- rejects devices whose product-device properties do not include `pearl`;
- requires Android SDK 35 or newer and Magisk 27.0 or newer;
- verifies `payload/manifest.sha256`;
- runs zstd integrity validation before extraction;
- checks the extracted Hermes commit against `a2e19d484cb5591df8dafe667c93345b62d9bf06`;
- extracts into `rootfs.new.<pid>`;
- preserves the current rootfs as `rootfs.previous`;
- restores the previous rootfs if activation fails.

The module does not contain API keys. The first-boot provisioner must write `$HERMES_HOME/.env` with mode `0600`.

## Controls

From a root shell:

```sh
# Disable and stop
/data/adb/modules/pearl_agent/action.sh disable

# Enable; reboot or invoke service.sh afterward
/data/adb/modules/pearl_agent/action.sh enable

# Restore the previous rootfs, retaining the failed rootfs for forensics
/data/adb/modules/pearl_agent/action.sh rollback
```

The Magisk app action button invokes toggle mode. Uninstall removes the active/previous rootfs and runtime mounts but deliberately retains `data/`, because it may contain secrets and user work. The final stock rollback package will offer an explicit secure-purge choice.

## Build

The module assembler requires Linux/WSL tools `file`, `rsync`, `sha256sum`, `unzip`, and `zip`. It requires an independently verified static ARM64 zstd binary and hash:

```bash
scripts/build-magisk-module.sh \
  --rootfs artifacts/rootfs/pearl-hermes-bookworm-arm64-a2e19d484cb5.tar.zst \
  --zstd /secure/input/zstd-arm64-static \
  --zstd-sha256 <independently-verified-64-hex-hash>
```

The output ZIP and SHA-256 sidecar are generated under gitignored `artifacts/magisk/`. The assembler rejects a non-ARM64 decoder, mismatched rootfs/hash, missing module files, and partition image payloads.

## Remaining real-device gates

Before installation:

- confirm target Magisk source/version and patched-boot provenance;
- validate Android 16 SELinux behavior for `chroot`, mounts, loopback network, and DNS;
- validate idle RAM, CPU, temperature, restart fuse, and Doze behavior;
- provision DeepSeek credentials without logging them;
- validate Nexus can enumerate the five MCP tools over `127.0.0.1:51338/mcp`;
- validate module disable and rootfs rollback before enabling always-on startup.
