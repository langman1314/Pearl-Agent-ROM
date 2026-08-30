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
4. official baseline deployment has completed before expecting Android 15, build `OS3.0.3.0.VLHCNXM`, and XiaoAi `507009011`;
5. both available stock boot/vbmeta images can be backed up and hash-identified through a separately approved read-only root/recovery procedure;
6. fastboot/recovery access survives an Android boot failure;
7. anti-rollback information does not contradict the proposed official baseline.

This collector does not perform item 5 because partition-content acquisition needs separate root/recovery review. It also never authorizes flashing by itself.

## Absolute stop conditions

Keep **NO FLASH** if any of the following is true:

- product/variant is not conclusively pearl China;
- slot variables conflict or partition names are ambiguous;
- bootloader state is unexpected;
- no independent recovery path exists;
- stock boot/vbmeta backups or their hashes are missing;
- a command plan includes efuse, preloader, super, userdata, metadata or vbmeta bypass;
- any evidence manifest fails verification.
