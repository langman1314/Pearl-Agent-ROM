# Generated payload directory

`build-magisk-module.sh` copies two ignored release artifacts here in a staging tree:

- `rootfs.tar.zst` — verified Debian ARM64 rootfs;
- `zstd` — audited static ARM64 zstd decoder.

It also generates `manifest.sha256`. Binaries must never be committed to Git.
