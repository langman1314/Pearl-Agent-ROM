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
| `pearl-agent-magisk-0.1.0-1.zip` (original CI) | `240,788,005` | `b4cbb87957798043a2c427eb6dc0431877d9f214b19646b29a2604f2c81bdf7c` |
| `pearl-agent-magisk-0.1.0-1.zip` (device-install symlink hotfix, retired) | `240,788,073` | `948a6c1ce70854a9167ca9bf1874e19e3bf08ea243f3be499215940a67cfe941` |
| `pearl-agent-magisk-0.1.1-2.zip` (pre-release persistence draft, retired) | `242,125,393` | `6785e0ad777dd5fa5bad56d12a4c520dce0b09bc23ab3fcfce8fcde1e3ac6754` |
| `pearl-agent-magisk-0.1.2-3.zip` (ZIP64 packaging draft, rejected on device) | `242,125,385` | `386bcc1b4a383389343d65c8323250a2b03eba50a4c6d7e035ba5f33be10a7d8` |
| `pearl-agent-magisk-0.1.3-4.zip` (F2FS fix; retained-config repair missing) | `242,125,124` | `a38124389d0791cecc93cece4ec2495572f9067e04bb9dea8b9e8d39ec450a60` |
| `pearl-agent-magisk-0.1.4-5.zip` (config repair; token validation missing) | `242,125,563` | `0ea3add349725f2e9d57b602ea11c669f597f624750b725a7bfc110689aa87ea` |
| `pearl-agent-magisk-0.1.5-6.zip` (current token-repair release) | `242,125,753` | `6c1b03633c0e69d35558e94cf2e9590129ab3f91120f99c423c286cf42304a0c` |
| `pearl-hermes-bookworm-arm64-a2e19d484cb5.tar.zst` | `240,465,642` | `53f59ea09bb065643a0bc8de49b727fbf1b386a3b2cef2c564e329037b70cb87` |
| `zstd-arm64-static` | `1,647,144` | `a24c13c263518fc5b565407ecf0661b4cb03f724f77f0fe185be854b9a96bc09` |

The rootfs sidecar, dependency freeze, dpkg list, build JSON, host package list and static-decoder sidecars are all separately covered by the tracked aggregate manifest.

## Experimental device deployment set

The current non-stock deployment set is copied to the host-only `D:\PearlAgentBuild\controlled-release-v7` directory and tracked by `manifests/experimental-agent-release.sha256`. Earlier release and pre-release directories through `controlled-release-v6` remain unchanged for provenance and none of their Agent ZIPs may be installed. Device testing rejected v4 before Agent activation because every entry was forced to ZIP 4.5/ZIP64 and Magisk stopped after the large rootfs without extracting `payload/zstd`; v5 and later use ordinary ZIP 2.0 entries and phone-side `unzip -p` reproduced the exact zstd SHA-256 before installation. Device reboot then proved the F2FS repair: the build record and Python standard-library hashes remained exact and Hermes imports passed. It also revealed that two persistent configuration files inherited from the original failed install were all NUL; v6 validates retained JSON/YAML and atomically repairs only invalid files from verified defaults. That boot exposed one final inherited writeback casualty: the MCP token had the expected 64-byte size but consisted entirely of a control byte. v7 accepts only exactly 64 lowercase hex characters with a minimum distinct-character floor, atomically regenerates only invalid tokens, and preserves valid existing tokens. Physical testing proved the original package incorrectly resolved the chroot venv's absolute `/usr/bin/python3` link against Android. The v2 installer fixed that check, but its multi-gigabyte extraction and metadata publication returned without `sync`; an immediate host reboot then left some F2FS files with correct sizes but zero-filled data, including Python `re/__init__.py` and the separately copied `installed-build.json`.

| Artifact | SHA-256 | Provenance |
|---|---|---|
| `Magisk-v30.7.apk` | `e0d32d21...9ebd5` | exact official APK bytes, independently matched to the app already installed on the phone |
| `Vector-v2.2-3080-Release.zip` | `9ee83235...79cc` | `JingMatrix/Vector` stable `v2.2` GitHub release; GitHub asset size/digest matched |
| `nexus-1.0.1-pearl.2-release.apk` | `99778de7...988a` | project RSA-4096 release signer; exact XiaoAi APK hash gate included |
| `pearl-agent-magisk-0.1.5-6.zip` | `6c1b0363...304a0c` | v6 persistence/ZIP/config hardening plus strict format/entropy validation and atomic replacement of only invalid MCP bearer tokens |

Vector's 69-entry ZIP passed CRC/path/case-collision inspection, identifies module id `zygisk_vector`, version `v2.2 (3080-88f8e1fa-JingMatrix-Vector)`, declares Android 8.1–17 support, internally verifies extracted payload hashes, and contains no partition image or flashing script. Device compatibility is still an acceptance test, not assumed from the declaration.

## Gates passed in the workflow

- Ubuntu host packages came from the pinned `20260801T000000Z` snapshot and exact versions were recorded.
- Hermes source was the clean pinned commit `a2e19d484cb5591df8dafe667c93345b62d9bf06`.
- Debian rootfs was Bookworm ARM64 from snapshot `20250601T000000Z`, Python 3.11, uv 0.12.7.
- The complete bridge unittest suite ran inside the actual QEMU ARM64 rootfs environment before packaging.
- ARM64 imports for Hermes `AIAgent`, MCP server and the installed bridge passed; bridge CLI startup to `--help` passed.
- Static ARM64 zstd was built in two independent source paths with source/debug/macro prefix normalization; both builds produced exact SHA-256 `a24c13c...` and no ELF interpreter.
- Rootfs archive contents, Hermes commit, ARM64 decoder identity, dynamic-link absence, uncompressed-size metadata, partition/flashing-script policies and final module contents passed the assembler gates.
- The immutable CI payload still comes from source commit `e0dca95`; module `0.1.5-6` layers the source-controlled device persistence, ZIP compatibility, retained-config and MCP-token repair fixes on that exact rootfs and decoder. Its portable assembly is pinned to the audited rootfs/decoder/unpacked-size identities, refuses output overwrite, and ZIP CRC, script/source identity plus internal payload hashes are rechecked before deployment.
- The runtime validation constants are intentionally payload-specific. Before any future rootfs payload upgrade, regenerate and bind the constants at build time and teach upgrade rollback to validate the old tree against its own installed build identity; never apply new-payload constants to delete an otherwise healthy old rollback tree.

## Independent module ZIP inspection

After download, the module ZIP was inspected independently of its outer manifest:

- 15 entries, no absolute/traversal path and no case-insensitive duplicate;
- all six lifecycle scripts (`customize`, `post-fs-data`, `service`, `action`, `uninstall`, `lib/common`) are byte-identical to the current reviewed source tree;
- all four files named by `payload/manifest.sha256` match their internal hashes;
- no prohibited firmware/preloader/fastboot flashing-script name exists.

## Approval boundary

These artifacts are **host-approved build outputs**, not device-approved and not a flash package. In particular:

- for the experimental 310 path, the module may be installed only after the clean stock-boot carrier reaches Phase-B acceptance and the fresh Magisk boot succeeds; the user's current-partition backup waiver is recorded separately and is not a production-safety claim;
- Vector 2.2 still requires pearl/310 Android 16 real-device validation before Nexus scope is enabled;
- the module writes `/data` only and contains no boot/vbmeta/super/preloader payload;
- the historical patched boot remains rejected; the independently reproduced host-only fresh patch is exactly `f3cb3ca...72005` and is separately gated by `scripts/switch-experimental-310-boot.sh`;
- no command here authorizes flashing.

Current overall state remains **NO FLASH**.
