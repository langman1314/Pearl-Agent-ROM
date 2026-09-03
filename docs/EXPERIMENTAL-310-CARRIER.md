# OS3.0.310.0 experimental carrier plan

## Status and risk acceptance

The `OS3.0.310.0.WAACNXM` ZIP remains cryptographically inconsistent and must never be described as an official or verified firmware baseline. The user has nevertheless confirmed that this package is known to boot on the target phone, has no user data requiring preservation, accepts recovery risk, and explicitly requested that work continue with it when the OS4 DSU package proved incomplete.

This changes its operational classification from "never consume" to **unverified experimental carrier**. It does not repair its AVB defects:

- top-level vbmeta flags were changed to `3` after signing;
- child descriptors cannot describe several logical extents in the supplied super payload;
- verified boot integrity is therefore intentionally unavailable for this carrier;
- Redmi Note 12T Pro is MediaTek, so Qualcomm EDL/9008 is not a recovery guarantee; severe recovery requires a compatible MediaTek BROM/Preloader/DA path.

No command in this document is approval to execute a flash. Actual execution remains separately gated.

## Why the package can boot despite failing verification

The prior rejection answered "is this a coherent signed firmware set?", not "can an unlocked bootloader run it?" An unlocked/orange device can boot a deliberately verification-disabled port even when its signed descriptors no longer match the logical payload. Successful historical flashing is therefore compatible with the audit findings; it proves practical bootability on at least one device state, not AVB integrity or universal recoverability.

## Reuse analysis

A fresh streaming comparison hashed every 310 image member against the accepted official `OS3.0.3.0.VLHCNXM` package:

- 25 low-level images are byte-identical to the official package, including stock `boot`, `vendor_boot`, `dtbo`, modem/DSP/TEE/LK and other firmware;
- differing same-name images are `cust`, `logo`, `vbmeta`, `vbmeta_system`, and `vbmeta_vendor`;
- `preloader_raw.img` and `super.zst` have no direct same-name official peer;
- 310 stock `boot.img` is exactly the accepted official boot SHA-256 `8526d0ff...a6f82`;
- 310 `vendor_boot.img` and `dtbo.img` are also exact official bytes.

Because those 25 low-level images are already represented by the verified official package, the experimental installer does not rewrite them. In particular it never writes preloader, efuse, GPT, LK, TEE, modem, DSP, logo, rescue or slot B.

## Minimal carrier staging

The isolated staging directory is `D:\PearlAgentBuild\carrier-310-staging` (not tracked by Git). Exact image hashes are tracked in `manifests/experimental-310-carrier.sha256`.

The supplied `super.zst` was:

1. verified and decompressed with the controlled MSYS2 `zstd`, not the ROM-bundled executable;
2. measured as 9,126,805,504 raw bytes with SHA-256 `f059a5802773e794823f620c46194f4d6a8fc0d8fbc5e591c8ebd2df85a9cc43`;
3. converted with the tested in-repository `scripts/raw-to-android-sparse.py`, using explicit zero FILL chunks so target bytes cannot remain stale as they could with DONT_CARE and capping every RAW chunk at 256 MiB;
4. published as a 7,223,956,348-byte sparse image, SHA-256 `89eb4b4d0a97119c0564a72e138cd1fd2f33d58aff5bf57b6179e708b1d9db6f`;
5. independently parsed as 65 valid chunks (44 RAW, 21 zero FILL, 0 DONT_CARE), maximum RAW chunk exactly 268,435,456 bytes and no trailing bytes;
6. re-expanded with the strict independent `scripts/unsparse-android-image.py` and required to reproduce the exact raw SHA-256.

No executable from the third-party ROM ZIP was run.

Host acceptance has passed: the full staging manifest rehashed successfully; the fixed carrier script processed all real 7.2 GB staging files and complete mocked pearl geometry in dry-run mode; both real Magisk/stock boot artifacts passed their dry runs; and the real Nexus/Vector/Hermes set passed the post-root dry run. Repository regressions cover dry-run non-mutation, exact acknowledgements, allowlists and failure boundaries. The remaining pre-flash gate is the same carrier dry run against the physical phone's live fastboot variables.

## Staged installation

### Phase A — stock-boot carrier

`scripts/flash-experimental-310-carrier.sh` is dry-run-only by default. Its fixed successful execution sequence is:

1. stock `boot_a`;
2. official-identical `vendor_boot_a`;
3. official-identical `dtbo_a`;
4. shared experimental `super`;
5. `vbmeta_system_a`, `vbmeta_vendor_a`, then verification-disabled `vbmeta_a`;
6. erase `metadata` and `userdata` for the cross-build clean install;
7. set active slot A;
8. reboot only after every prior operation succeeds.

The script validates product, unlocked state, anti value, A/B geometry, exact boot/vbmeta capacities, super capacity, sparse expanded size and all SHA-256 values. It invokes the pinned platform-tools capability `fastboot -S 256M flash super` so the host also resplits sparse transport units conservatively. A failed command stops before reboot. Execution requires `--wipe`, `--execute`, and the exact risk acknowledgement environment value.

### Phase B — carrier acceptance before root

Before installing Magisk or Agent components, the clean carrier must reach Android boot completion and prove ADB, display/touch, Wi-Fi, telephony, audio, power-button XiaoAi and the exact bundled XiaoAi APK identity. Failure returns directly to the official recovery package plan; no Agent component is added to a broken carrier.

### Phase C — fresh Magisk boot and data-only Agent

Only after Phase B passes, `scripts/switch-experimental-310-boot.sh --mode magisk` may replace `boot_a` with the independently reproduced fresh Magisk 30.7 image SHA-256 `f3cb3ca...72005`. The historical patched boot remains prohibited. The same script's separately acknowledged `--mode stock` path restores exact staged `boot-stock.img`; both modes are dry-run-first and permit only `boot_a`, `set_active a`, and reboot before any Vector, Nexus or Hermes acceptance proceeds.

After rooted Android itself passes, `scripts/deploy-agent-after-root.sh` provides a third independent dry-run/acknowledgement gate. It hash-verifies and installs only the approved Nexus APK, official Vector v2.2 Magisk module and data-only Hermes module, then reboots; it has no fastboot or partition-writing path and deliberately does not enable Vector/Xposed scope. Scope is enabled only after the reboot and exact Nexus/XiaoAi identity checks.

Hermes, Nexus and the Agent Magisk module remain data-only payloads. The 310 XiaoAi config is already bound to its independently audited exact APK SHA-256, so same-version/different-bytes packages fail closed.

## OS4 DSU relationship

The HarmonyOS4 DSU ZIP cannot "complete" this carrier: it supplies only large `system.img` and `vendor.img`, lacks a matching boot chain, and is designed for DSU allocation rather than the carrier's signed logical partition map. It may be tested later through DSU as a disposable secondary environment, but it is not merged into this carrier and cannot preserve the XiaoAi-based target architecture by itself.
