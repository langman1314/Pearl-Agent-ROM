# OS4 DSU package audit

## Input

- File: `D:\EdgDownloads\HarmonyOS4_DSU侧载尝鲜版12tp_by是天天吖.zip`
- Package SHA-256: `3b3173478b74ab194960770dca0d09e687ab980f5e4ee9f0cb935f76f775b1b8`
- Package bytes: `7,359,451,502`
- ZIP entries: 2, both stored without compression

## Contents

| Member | Bytes | SHA-256 | Format |
|---|---:|---|---|
| `system.img` | 5,342,986,240 | `09261054ace9f36d6d9b2ef1b5b68f110ee94870ba325f096dc8b287521828df` | EROFS |
| `vendor.img` | 2,016,464,896 | `d6adfeb22bbea1a101b8723707453e874ade8dd4af88494a1a577799f5356dbd` | EROFS |

Pinned `fsck.erofs` 1.9.4 completed integrity checks for both images with exit code 0. The original ZIP was not modified.

## Baseline decision

This is a DSU side-load payload, not a complete flashable OS package. It has no `boot.img`, `vendor_boot.img`, `vbmeta.img`, `dtbo.img`, `super.img`, partition map, firmware set or flash script. It therefore cannot provide the boot image needed for a matching Magisk patch and cannot independently replace the phone's current boot chain.

The `vendor.img` includes an AVB footer, but its metadata is not a trustworthy OS4 identity anchor:

- algorithm: `NONE` (no authenticated signature);
- vendor fingerprint: `Xiaomi/mihal/mihal:12/SP1A.210812.016/V14.0.10.0.TLHCNXM:user/release-keys`;
- vendor OS version: Android 12;
- vendor security patch: `2023-10-01`.

`system.img` has no standalone AVB footer according to the pinned AOSP avbtool probe. These properties are inconsistent with the current phone's Android 16 `WONCNXM` state and do not establish a flashable HarmonyOS 4 firmware baseline.

## Reuse decision

Reusable project components remain the generic archive/AVB/EROFS audit tools, Hermes rootfs, data-only Magisk module, Nexus source and exact APK identity gate. No OS4 image is copied into the project build or installer. The OS4 ZIP is retained only as an immutable audit input.

The previous `OS3.0.310.0.WAACNXM` ZIP remains rejected for the already documented mixed AVB/logical extent contradictions. Neither package is currently an acceptable firmware baseline. A future complete OS4 package must include a matching boot chain and pass independent provenance, AVB, partition-layout and device-compatibility checks before adaptation.

Status: **NO FLASH**.
