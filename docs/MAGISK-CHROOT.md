# Pearl Agent Magisk chroot module

## Scope

The module is a `/data` payload and process supervisor. It does **not** flash or modify:

- `boot` / `init_boot`;
- `vbmeta` / AVB descriptors;
- `super` or any logical partition;
- `preloader`;
- `efuse`.

Root installation and boot-image provenance remain separate gates. Magisk 30.7 and Vector 2.2 provenance is fixed in `PLATFORM-PROVENANCE.md`, but the module must not be installed until patched-boot AVB, real-device compatibility, and rollback gates pass.

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
   - minimal tmpfs `/dev` with only null/zero/full/random/urandom/tty plus a private `devpts` `newinstance`; host `/dev/block` is never exposed;
   - restricted `proc` and an isolated tmpfs `/run`; host `sysfs` is not mounted;
   - generated `/etc/resolv.conf` from Android DNS properties, with conservative fallbacks.
   - persistent mount-failure count; after three failed boots it tries `rootfs.previous` once, quarantines the failed tree, and disables the runtime if both versions fail.
2. `service.sh` waits for `sys.boot_completed=1`.
3. It launches only `pearl-hermes-bridge-wrapper` through `chroot` with a minimal explicit environment.
4. The bridge runs in its own process group; PID plus `/proc` start-time/cmdline identity prevents PID-reuse kills, and shutdown terminates descendants before unmount.
5. If the process exits, the supervisor backs off `2 → 5 → 15 → 30 → 60 → 120` seconds.
6. Three consecutive runs shorter than 15 minutes open a 30-minute fuse to protect battery and temperature; only a 15-minute healthy run resets the crash count. Fuse/backoff sleeps check the persistent disable marker at least every five seconds.
7. Supervisor and bridge logs rotate at 10 MiB with three retained generations.

No Android application partition is mounted into the chroot. The Hermes terminal is intentionally scoped to its persistent workspace unless a later, explicit Android-control bridge is approved.

## Installer safety

`customize.sh`:

- rejects devices whose product-device properties do not include `pearl`;
- requires Android SDK 35 or newer and Magisk 27.0 or newer;
- verifies `payload/manifest.sha256` and requires a statically linked ARM64 zstd decoder;
- verifies an integrity-covered uncompressed-size sidecar and requires 125% of that size plus 256 MiB free on `/data` before extraction;
- generates a 256-bit MCP Bearer token from `/dev/urandom`, stored only as `0600` under persistent data;
- runs zstd integrity validation before extraction;
- publishes a persistent maintenance marker before stopping/unmounting an old runtime, preventing the existing supervisor from restarting during upgrade;
- removes orphan `rootfs.new.*` stages before creating a new stage;
- re-scans the extracted tree and rejects Android partition, GPT, bootloader image payloads and known firmware/preloader/fastboot flashing-script names on-device;
- checks the extracted Hermes commit against `a2e19d484cb5591df8dafe667c93345b62d9bf06`;
- provisions a non-secret DeepSeek `config.yaml` only when absent and preserves user edits on upgrades;
- extracts into `rootfs.new.<pid>`;
- preserves the current rootfs as `rootfs.previous`;
- restores the previous rootfs if activation fails;
- clears maintenance only after activation metadata is published; a killed/aborted installer remains fail-closed and requires reinstall rather than starting a partial tree.

The module does not contain API keys. The first-boot provisioner must write `$HERMES_HOME/.env` with mode `0600`; `config.yaml` contains behavior only and is also forced to mode `0600`.

## Controls

From a root shell:

```sh
# Disable and stop
/data/adb/modules/pearl_agent/action.sh disable

# Enable marker; reboot is required so mounts are created in init namespace
/data/adb/modules/pearl_agent/action.sh enable

# Restore the previous rootfs, retaining the failed rootfs for forensics
/data/adb/modules/pearl_agent/action.sh rollback
```

The Magisk app action button invokes toggle mode. Uninstall removes the active/previous rootfs and runtime mounts but deliberately retains `data/`, because it may contain secrets and user work. The final stock rollback package will offer an explicit secure-purge choice.

## Build

The module assembler requires Linux/WSL tools `file`, `readelf`, `rsync`, `sha256sum`, `tar`, `unzip`, `wc`, `zip`, and `zstd`. It requires a verified static ARM64 zstd binary and hash:

```bash
scripts/build-magisk-module.sh \
  --rootfs artifacts/rootfs/pearl-hermes-bookworm-arm64-a2e19d484cb5.tar.zst \
  --zstd /secure/input/zstd-arm64-static \
  --zstd-sha256 <independently-verified-64-hex-hash>
```

The output ZIP and SHA-256 sidecar are generated under gitignored `artifacts/magisk/`. The assembler rejects a non-ARM64/dynamic decoder, mismatched rootfs/hash, corrupt zstd, missing module files, partition-image filenames and known firmware/preloader/fastboot flashing-script names both in the module ZIP and inside the rootfs archive.

Without local Linux/WSL, run the manually triggered GitHub workflow `.github/workflows/build-phone-artifacts.yml`. It has read-only repository permission, pins every GitHub Action/source commit plus an immutable Ubuntu apt snapshot, builds static ARM64 zstd twice and requires identical hashes with no ELF `INTERP`, builds the Debian rootfs/module, and uploads all binaries plus package/provenance/hash manifests for 14 days. Generated binaries remain outside Git.

## Remaining real-device gates

Before installation:

- confirm target Magisk source/version and patched-boot provenance;
- validate official Android 15 `OS3.0.3.0.VLHCNXM` SELinux behavior for `chroot`, mounts, loopback network, DNS and the private devpts instance;
- validate idle RAM, CPU, temperature, restart fuse, and Doze behavior;
- provision DeepSeek credentials without logging them;
- validate Nexus can enumerate the five MCP tools over `127.0.0.1:51338/mcp`;
- validate module disable and rootfs rollback before enabling always-on startup.
