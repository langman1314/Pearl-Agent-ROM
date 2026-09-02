# Physical pearl observation 01

## Scope

This is a read-only ADB observation of the connected target before any project installation or partition write. The full evidence directory remains outside Git because it contains the device serial and command ledger:

`D:\PearlAgentBuild\device-evidence\adb-connected-02`

Its `manifest.sha256` covers 18 evidence files. The collector reported `mutation_commands=none` and `partition_contents_collected=false`.

## Observed identity

| Field | Observed value |
|---|---|
| product device | `pearl` |
| vendor device | `pearl` |
| system device | `generic` |
| retail model reported by ADB | `23054RA19C` |
| fingerprint | `Redmi/pearl/pearl:16/BP2A.250605.031.A3/OS3.0.300.3.WONCNXM:user/release-keys` |
| incremental | `OS3.0.300.3.WONCNXM` |
| SDK | `36` |
| security patch | `2026-01-01` |
| active Android slot suffix | `_a` |
| verified boot state | `orange` |
| flash locked property | `0` |
| Magisk CLI | absent (`127`, inaccessible/not found) |
| XiaoAi package | `com.miui.voiceassist`, versionCode `507009011` |

The running build is neither the rejected input identity `OS3.0.310.0.WAACNXM` nor the approved official recovery baseline `OS3.0.3.0.VLHCNXM`. The `generic` system identity, Android 16 build and unlocked/orange state make it unsuitable as a provenance anchor. It must be treated only as the phone's current state.

The installed XiaoAi versionCode matches the official-baseline config, but a read-only `adb pull` proved its APK bytes do not. The physical APK is 158,593,505 bytes with SHA-256 `343cab12b54bcd3de34abe8da767c784f8d7308eb9b85a4b24dd0e0c0c00e7d6` and 8,396 ZIP entries, versus the approved official APK's 161,303,814 bytes, SHA-256 `dd75a0d9b1c0803906eafe72126dc44a150f16337e26c8fcf53304f13d74140b` and 9,697 entries. Both report `507009011` / `7.9.11.1910` and signer SHA-256 `c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`; all physical APK CRCs and its v3 signature pass.

This demonstrates why versionCode-only hook selection is insufficient on ported firmware. Hook activation on the current installation is prohibited unless its own Dex targets are separately audited and a hash-bound config policy is added. The preferred release path remains installing the AVB-verified official baseline first.

## Observed partition links

Unprivileged read-only listing exposed slot-specific links:

| Name | Target |
|---|---|
| `boot_a` | `/dev/block/sdc43` |
| `boot_b` | `/dev/block/sdc72` |
| `vbmeta_a` | `/dev/block/sdc14` |
| `vbmeta_b` | `/dev/block/sdc17` |

There is no unsuffixed `boot` or `vbmeta` by-name entry. This confirms A/B naming but does not prove partition capacity or permit choosing a flash target. Android reports slot `_a`; fastboot must independently confirm `current-slot`, `slot-count`, `has-slot` and sizes.

## Collector correction discovered on device

The first capture showed Git Bash/MSYS rewriting `/dev/block/...` arguments into host `D:/app/Git/dev/block/...` paths. That capture was rejected for partition-link evidence. Commit `9845ad7` exports `MSYS_NO_PATHCONV=1` and `MSYS2_ARG_CONV_EXCL=*`; mocked regression coverage now requires literal Android paths. The corrected second capture produced the links above.

## Bootloader evidence

Two ADB reboot requests did not transition the current firmware, so the user manually entered fastboot and explicitly authorized the fixed read-only `getvar` allowlist. The corrected collector published a second external evidence directory:

`D:\PearlAgentBuild\device-evidence\fastboot-connected-01`

Observed values:

| Fastboot variable | Value |
|---|---|
| product | `pearl` |
| current-slot | `a` |
| slot-count | `2` |
| unlocked | `yes` |
| secure | `no` |
| anti | `1` |
| has-slot:boot | `yes` |
| has-slot:vbmeta | `yes` |
| boot_a / boot_b size | `0x4000000` each (64 MiB) |
| vbmeta_a / vbmeta_b size | `0x800000` each (8 MiB) |

Unsuffixed boot/vbmeta size values were empty, matching the Android-observed absence of unsuffixed by-name entries. The active slot and partition geometry now agree across Android and fastboot. After collection, the only state-changing command was the pre-authorized `fastboot reboot`; Android and authorized ADB returned successfully. No partition read, flash, erase, unlock, format or slot change occurred.

## Read-only partition backup attempt

After mocked validation of `scripts/backup-device-boot-chain.sh`, the phone was manually returned to fastboot and the script attempted its first allowlisted `fetch boot_a`. The bootloader rejected it before transferring data. One direct read-only retry produced the conclusive platform-tools error:

```text
fastboot: error: Unable to get max-fetch-size. Device does not support fetch command.
```

No output image was published, all temporary host files were removed, and no device mutation command ran. The phone was then returned to Android with `fastboot reboot`.

This closes the standard fastboot-readback route. Current boot/vbmeta backups now require a separately built and reviewed ephemeral read-only recovery/ramdisk or another provenance-controlled acquisition mechanism. The project must not substitute a flash operation, an unknown recovery image, the historical Magisk patch or an unreviewed MediaTek tool.

## Gate decision

Passed:

- physical product/vendor report pearl;
- bootloader unlocked/secure/anti state confirmed by fastboot;
- A/B slot count, active slot, boot/vbmeta link names and capacities are device-derived and cross-consistent;
- XiaoAi version/signer/signature/CRC are known, and its mismatch from the official APK is proven;
- no existing Magisk state needs preservation.

Not passed:

- current boot/vbmeta content backup and hashes;
- independent recovery path;
- official Android 15 baseline installation;
- Magisk 30.7/Vector 2.2 compatibility;
- device acceptance and rollback rehearsal.

Status remains **NO FLASH**.
