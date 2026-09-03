# Target pearl read-only preflight

First physical ADB findings and remaining bootloader gates are recorded in `docs/DEVICE-OBSERVATION-01.md`.

## Purpose

No boot image or installer command may be approved from ROM-script assumptions alone. `scripts/collect-device-preflight.sh` records selected target-phone facts through fixed read-only ADB or fastboot command lists. It does not read partition contents and contains no flash, erase, format, reboot, boot, set_active, update, dd, push or remount operation.

Run this only when the target device is available. Use a new output directory outside the Git tree for every capture.

## Android userspace capture

```bash
bash scripts/collect-device-preflight.sh \
  --mode adb \
  --serial DEVICE_SERIAL \
  --output-dir /d/PearlAgentBuild/device-evidence/adb-before-baseline
```

Collected fields:

- product/vendor/system device properties;
- build fingerprint, incremental version, SDK and security patch;
- Android-reported slot suffix and verified-boot/lock state;
- installed `com.miui.voiceassist` versionCode;
- Magisk version if present;
- visible boot/vbmeta by-name links, without reading their contents.

ADB authorization and exactly selected serial remain operator-controlled. An unavailable/unauthorized target aborts before publishing evidence.

## Bootloader capture

```bash
bash scripts/collect-device-preflight.sh \
  --mode fastboot \
  --serial DEVICE_SERIAL \
  --output-dir /d/PearlAgentBuild/device-evidence/fastboot-before-baseline
```

Collected `getvar` fields include:

- product;
- current-slot and slot-count;
- unlocked/secure/anti values where exposed;
- has-slot for boot/vbmeta;
- reported boot/vbmeta partition sizes for unsuffixed, slot-a and slot-b names.

Unsupported variables may return non-zero; each command's output and exit code are retained rather than guessed. Without `--serial`, exactly one fastboot device is required.

## Evidence integrity

The collector writes to `<output>.partial.<pid>`, refuses to overwrite existing evidence, records `commands.tsv`, and publishes the final directory only after generating `manifest.sha256`. Verify after capture:

```bash
cd /d/PearlAgentBuild/device-evidence/fastboot-before-baseline
sha256sum -c manifest.sha256
```

The evidence may contain a device serial/fingerprint and must not be committed. POSIX mode 0700/0600 is applied where supported; on Windows, the containing directory's ACL remains the privacy boundary.

## Pass criteria before boot patching

Human review must reconcile ADB and fastboot captures and prove:

1. product is `pearl`, not just a flash-script label;
2. bootloader state permits the intended controlled test;
3. actual slot and partition naming is unambiguous;
4. reconcile the selected path explicitly: the experimental carrier expects Android SDK 36 and XiaoAi `507012002` with its bound full-APK hash, while an official recovery boot expects Android 15 `OS3.0.3.0.VLHCNXM` and XiaoAi `507009011`;
5. both available stock boot/vbmeta images are backed up and hash-identified for production acceptance; this bootloader's missing `fetch` support and the user's narrow backup waiver remain recorded only for the unverified 310 experiment;
6. fastboot/recovery access survives an Android boot failure;
7. anti-rollback information does not contradict the proposed official baseline.

For bootloaders that implement Android platform-tools' read-only `fetch`, the separately tested `scripts/backup-device-boot-chain.sh` can acquire `boot_a`, `boot_b`, `vbmeta_a` and `vbmeta_b`. It repeats product/slot/unlocked/has-slot/size gates, validates exact byte counts plus `ANDROID!`/`AVB0` headers, hashes every output and atomically publishes a new directory. Its command allowlist is only `devices`, `getvar` and `fetch`; it never reboots or mutates a slot. If the bootloader rejects `fetch`, stop and use a separately approved root/recovery read-only path—never substitute flashing or the historical patched image.

Neither collector nor backup script authorizes flashing by itself.

## Absolute stop conditions

Keep **NO FLASH** if any of the following is true:

- product/variant is not conclusively pearl China;
- slot variables conflict or partition names are ambiguous;
- bootloader state is unexpected;
- no independent recovery path exists;
- stock boot/vbmeta backups or their hashes are missing for production acceptance; the separately recorded user waiver applies only to the unverified 310 experiment and never converts this item to PASS;
- a command plan includes efuse, preloader, GPT, slot B or generic flash-all behavior; `super`/clean-install erases/slot-A vbmeta are eligible only inside the separately reviewed exact-hash carrier or official-recovery allowlists and never as an improvised bypass;
- any evidence manifest fails verification.
