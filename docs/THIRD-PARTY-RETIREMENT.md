# Third-party ROM retirement boundary

## Decision

`pearl_Note12TPro_OS3.0.310.0.WAACNXM_16.0.zip` remains rejected as an official, AVB-verifiable firmware baseline or recovery source. Its original ZIP and original flash script remain immutable audit inputs.

The package is a mixed `mytiantian` port whose signed AVB descriptor sizes cannot fit its own logical extents. Disabling verification cannot repair missing signed bytes, and its top-level vbmeta flags were changed after signing. After this finding, the user separately confirmed prior practical bootability on the target and explicitly accepted using it as an **unverified experimental carrier** when the OS4 candidate proved DSU-only. That narrow operational exception is defined in `docs/EXPERIMENTAL-310-CARRIER.md`; it does not make the AVB chain valid or approve the original flash script.

## Final payload dependency matrix

| Third-party item | Final use | Decision |
|---|---|---|
| stock `boot.img`, `vendor_boot.img`, `dtbo.img` | experimental carrier only | byte-identical to the accepted official package; staged with exact hashes |
| historical patched boot | none | permanently reject; use only the fresh independently reproduced Magisk patch |
| `vbmeta*.img` | experimental carrier only | required by the known-bootable port but explicitly verification-disabled/inconsistent |
| `super.zst` and logical payload | experimental carrier only | trusted-tool decompression/sparse conversion and exact round-trip passed; not AVB coherent |
| `product_a.img` XiaoAi APK | no APK payload | retain only version-exact static compatibility evidence for 507012002 |
| `cust.img` | none | reject; empty EROFS placeholder |
| `efuse` and `preloader_raw.img` | none | permanently reject and block from every project installer |
| original `flashl.bat` / any `flash_all*.bat/.sh` | none | never execute, copy or adapt; only the independent fixed-allowlist installer is eligible |
| bundled Nexus debug APK | none | reject; project release is rebuilt from source and signed with the dedicated project key |
| Nexus source lineage | reviewed source only | project source is independently built/tested; no third-party binary is copied |

## Sole accepted firmware source

The only recovery/rebuild firmware anchor is official pearl fastboot `OS3.0.3.0.VLHCNXM`:

- archive SHA-256 `99e166422be4bd17237df9b70030b5f0ff1871d7b7bd858cbb62b683f070ea64`;
- all 56 extracted file hashes recorded;
- strict Android sparse expansion used before LP extraction;
- complete top-level/chained AVB verification passed;
- official XiaoAi is `507009011`, not the input package's `507012002`.

## Enforced build boundary

The phone artifact workflow builds only:

1. a Debian/Hermes rootfs from pinned Debian snapshots and pinned Hermes source;
2. a source-built static ARM64 zstd binary;
3. a data-only Magisk module assembled from tracked scripts/config plus those two artifacts.

`build-magisk-module.sh` rejects partition-image names both inside the rootfs and in the final ZIP. The module must contain no boot, init_boot, vendor_boot, recovery, dtbo, vbmeta, super, system, vendor, product, odm, preloader, efuse, GPT, LK/ABL/XBL image or flashing script.

Nexus is rebuilt from tracked source, tested and signed independently. XiaoAi APKs are never included; exact configs contain only method/class metadata.

## Device-derived status and waiver boundary

Physical product, bootloader, anti, slot, partition-name and capacity evidence has been collected. This bootloader supports neither `fastboot fetch` nor `fastboot boot`, so current boot/vbmeta contents could not be exported. The user explicitly waived that backup requirement for the unverified experiment after confirming that no personal data needs preservation.

That waiver permits preparation of a dry-run-gated experimental installer; it does not make the carrier production-verified and does not authorize execution by itself. The accepted official fastboot package remains the recovery anchor, while MediaTek BROM/DA recovery is not treated as guaranteed. Actual flashing still requires the fixed script's device/hash/capacity gates and exact acknowledgement. Status remains **NO FLASH** until a separate execute decision is made.
