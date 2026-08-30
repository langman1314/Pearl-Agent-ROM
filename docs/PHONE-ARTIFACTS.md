# Host-approved phone artifacts

## Final workflow

| Field | Value |
|---|---|
| repository source | `e0dca9501ee6865b58712d1f7920edd9511ac3ad` |
| GitHub Actions run | [33319865389](https://github.com/langman1314/Pearl-Agent-ROM/actions/runs/33319865389) |
| result | success |
| downloaded GitHub artifact ZIP bytes | `482,914,969` |
| downloaded artifact ZIP SHA-256 | `de2a734fb57d6c78b79cd62f17a31ebc4a43169d11364258656e03200d6368f4` |
| aggregate entries | `11` |
| local verified copy | `artifacts/ci/run-33319865389/` (gitignored) |

The GitHub artifact was downloaded only after the workflow completed successfully. PowerShell's initial download stalled at zero bytes; the accepted copy was downloaded with curl after a Schannel revocation-service error, with normal TLS certificate-chain/hostname verification retained and only unavailable online revocation lookup disabled. Every extracted file was then rehashed locally against the workflow's aggregate manifest. The GitHub container ZIP hash is transport evidence; release identity is the per-file manifest tracked at `manifests/phone-artifacts-e0dca95.sha256`.

Exact workflow provenance is tracked at `manifests/phone-artifacts-e0dca95.provenance.txt`.

## Installable/data payload identities

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `pearl-agent-magisk-0.1.0-1.zip` | `240,788,005` | `b4cbb87957798043a2c427eb6dc0431877d9f214b19646b29a2604f2c81bdf7c` |
| `pearl-hermes-bookworm-arm64-a2e19d484cb5.tar.zst` | `240,465,642` | `53f59ea09bb065643a0bc8de49b727fbf1b386a3b2cef2c564e329037b70cb87` |
| `zstd-arm64-static` | `1,647,144` | `a24c13c263518fc5b565407ecf0661b4cb03f724f77f0fe185be854b9a96bc09` |

The rootfs sidecar, dependency freeze, dpkg list, build JSON, host package list and static-decoder sidecars are all separately covered by the tracked aggregate manifest.

## Gates passed in the workflow

- Ubuntu host packages came from the pinned `20260801T000000Z` snapshot and exact versions were recorded.
- Hermes source was the clean pinned commit `a2e19d484cb5591df8dafe667c93345b62d9bf06`.
- Debian rootfs was Bookworm ARM64 from snapshot `20250601T000000Z`, Python 3.11, uv 0.12.7.
- The complete bridge unittest suite ran inside the actual QEMU ARM64 rootfs environment before packaging.
- ARM64 imports for Hermes `AIAgent`, MCP server and the installed bridge passed; bridge CLI startup to `--help` passed.
- Static ARM64 zstd was built in two independent source paths with source/debug/macro prefix normalization; both builds produced exact SHA-256 `a24c13c...` and no ELF interpreter.
- Rootfs archive contents, Hermes commit, ARM64 decoder identity, dynamic-link absence, uncompressed-size metadata, partition/flashing-script policies and final module contents passed the assembler gates.
- The final Magisk module contains the maintenance-mode upgrade quiescing and race-safe uninstall/reinstall lifecycle from source commit `e0dca95`.

## Independent module ZIP inspection

After download, the module ZIP was inspected independently of its outer manifest:

- 15 entries, no absolute/traversal path and no case-insensitive duplicate;
- all six lifecycle scripts (`customize`, `post-fs-data`, `service`, `action`, `uninstall`, `lib/common`) are byte-identical to source commit `e0dca95`;
- all four files named by `payload/manifest.sha256` match their internal hashes;
- no prohibited firmware/preloader/fastboot flashing-script name exists.

## Approval boundary

These artifacts are **host-approved build outputs**, not device-approved and not a flash package. In particular:

- the module may be installed only after official-stock boot patch provenance, actual-device identity/slot/backup/recovery gates and Magisk 30.7 validation pass;
- Vector 2.2 still requires pearl/official Android 15 real-device validation;
- the module writes `/data` only and contains no boot/vbmeta/super/preloader payload;
- the historical patched boot remains rejected, and no final patched boot exists;
- no command here authorizes flashing.

Current overall state remains **NO FLASH**.
