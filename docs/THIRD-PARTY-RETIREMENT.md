# Third-party ROM retirement boundary

## Decision

`pearl_Note12TPro_OS3.0.310.0.WAACNXM_16.0.zip` is preserved byte-for-byte only as an immutable audit input. It is not a firmware baseline, recovery source, partition source, installer source or flash-script source.

The package is a mixed `mytiantian` port whose signed AVB descriptor sizes cannot fit its own logical extents. Disabling verification cannot repair missing signed bytes. Its top-level vbmeta flags were also changed after signing. Status is permanently **REJECTED / NO FLASH**.

## Final payload dependency matrix

| Third-party item | Final use | Decision |
|---|---|---|
| `boot.img` / patched boot | none | reject; final Magisk work must start from exact official baseline boot |
| `vendor_boot.img`, `dtbo.img`, `vbmeta*.img` | none | reject |
| `super.zst` and every logical partition | none | reject |
| `product_a.img` XiaoAi APK | no APK payload | retain only version-exact static compatibility evidence for 507012002 |
| `cust.img` | none | reject; empty EROFS placeholder |
| `efuse`, preloader and partition images | none | reject and block from project installer |
| `flash_all*.bat/.sh` | none | never execute, copy or adapt |
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

## Remaining device-derived inputs

No final boot or installer can be approved until the physical device supplies read-only evidence for:

- product/codename/variant and bootloader state;
- current-slot and real boot/vbmeta partition map;
- hashes/backups of both available stock boot/vbmeta slots;
- anti-rollback variables where exposed;
- working fastboot/recovery rollback procedure.

These values must come from the target phone, not either ROM script. Until then the project remains **NO FLASH**.
