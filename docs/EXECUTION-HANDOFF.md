# Physical execution handoff

## Prepared state

Host preparation is complete at Git HEAD `8536225` or later. No partition has been written during this project session. The phone was last safely returned to Android after unsupported `fastboot fetch` and `fastboot boot` probes, and is currently disconnected from both ADB and fastboot.

Required host-only directories:

- carrier: `D:\PearlAgentBuild\carrier-310-staging`
- Agent artifacts: `D:\PearlAgentBuild\controlled-release`
- official recovery anchor: preserved `OS3.0.3.0.VLHCNXM` fastboot archive/extraction

Do not run `flashl.bat` from the 310 ZIP. Do not add preloader, efuse, GPT, slot B or low-level firmware commands.

## Gate 1 — physical carrier dry run

Put the target phone in bootloader fastboot and connect it directly by reliable USB. From Git Bash run only:

```bash
cd '/d/生活问答/Pearl-Agent-ROM'
FASTBOOT_BIN='/d/生活问答/Pearl-Agent-ROM/.tools/android-sdk/platform-tools/fastboot.exe' \
  bash scripts/flash-experimental-310-carrier.sh \
  --staging /d/PearlAgentBuild/carrier-310-staging \
  --serial 7TV8X4RS8DAAIN4P
```

The final line must be `DRY_RUN_ONLY`. It must print exactly the 11 operations documented in `docs/EXPERIMENTAL-310-CARRIER.md`. Stop on any hash, product, unlock, anti, slot, geometry or capacity mismatch. A clean dry run still does not itself authorize execution.

## Gate 2 — destructive carrier phase

This phase destroys userdata and the current shared logical system. It is eligible only after reviewing the live dry-run transcript. The script requires all three deliberate controls: `--wipe`, `--execute`, and the exact `PEARL_ACCEPT_UNVERIFIED_310` value in its usage text.

On any command failure, do not manually reboot. Preserve the exact console transcript and keep the phone in fastboot for diagnosis. A successful run reboots only after stock boot-A, vendor_boot-A, dtbo-A, shared super, the three slot-A vbmeta images, both clean-install erases and active-slot A all succeed.

## Gate 3 — clean carrier acceptance

Before root, confirm and record:

- boot completion, lock screen and launcher;
- ADB authorization, `pearl`, SDK 36, build identity and active slot A;
- display, touch, buttons, charging and thermal behavior;
- Wi-Fi, Bluetooth, modem/SIM/data and calls;
- speaker, microphone, cameras and sensors;
- stock XiaoAi launch, power-button behavior, ASR, UI and TTS;
- XiaoAi package `com.miui.voiceassist`, versionCode `507012002`, full-APK SHA-256 `326fe0601b11698e70f96aca5dc05d1c783cf675ebc872024e96b405aaf64406`.

Stop and recover instead of adding Magisk if any base function fails.

## Gate 4 — fresh Magisk boot and rollback proof

Run `scripts/switch-experimental-310-boot.sh` first without `--execute`, mode `magisk`. After its physical dry run passes, execution requires the exact `PEARL_ACCEPT_MAGISK_BOOT` value in the script. The only allowed mutation is fresh patched `boot_a` SHA-256 `f3cb3ca...72005`, active slot A and reboot.

Confirm boot, ADB, Magisk 30.7 and `su`. Then reboot to fastboot and prove `--mode stock` rollback using its independent `PEARL_CONFIRM_STOCK_RESTORE` acknowledgement. Confirm stock boot again. Only after that proof, install the fresh Magisk boot a second time and revalidate root.

## Gate 5 — data-only Agent deployment

Run `scripts/deploy-agent-after-root.sh` against `D:\PearlAgentBuild\controlled-release` without `--execute`. It requires pearl SDK 36 and exact hashes for Nexus pearl.2, stable Vector v2.2 and the Agent module. After review, its execution path requires the exact `PEARL_ACCEPT_AGENT_DEPLOY` acknowledgement.

The script installs only an APK and two Magisk modules, cleans temporary files and reboots. It never flashes a partition and intentionally does not enable Xposed scope. After reboot:

1. confirm Magisk and `zygisk_vector` are healthy;
2. open the Vector manager and enable Nexus only for the exact XiaoAi package/process scope required by the compatibility config;
3. verify Nexus signer/version and exact XiaoAi hash gate before enabling takeover;
4. dry-run then execute `scripts/provision-hermes-after-root.sh`; it prompts for the Hermes `DEEPSEEK_API_KEY` without echo, transfers it only over ADB stdin, atomically writes mode-0600 `.env`, refuses overwrite and reboots;
5. open Nexus, grant its one-time Magisk root request so it can read the generated MCP token and install the localhost Bearer header, then enter the Nexus main-Agent DeepSeek endpoint/model/key in its Android UI (this is separate from Hermes `.env`);
6. validate authenticated localhost MCP, Hermes submit/status/cancel/run, reboot persistence, fail-open native XiaoAi, crash fuse/backoff and one-version rollback.

Hermes credential dry-run and execution commands are:

```bash
bash scripts/provision-hermes-after-root.sh --serial 7TV8X4RS8DAAIN4P
PEARL_ACCEPT_SECRET_PROVISION=YES_PROVISION_HERMES_DEEPSEEK_SECRET \
  bash scripts/provision-hermes-after-root.sh \
  --serial 7TV8X4RS8DAAIN4P --execute
```

The second command displays a hidden prompt; paste the key there. Do not append the key to the command or save it in this repository.

## Read-only post-install evidence

After each corresponding gate, publish a new evidence directory with the read-only collector:

```bash
bash scripts/collect-post-install-acceptance.sh --phase carrier \
  --serial 7TV8X4RS8DAAIN4P \
  --output-dir /d/PearlAgentBuild/device-evidence/310-carrier-accepted

bash scripts/collect-post-install-acceptance.sh --phase magisk \
  --serial 7TV8X4RS8DAAIN4P \
  --output-dir /d/PearlAgentBuild/device-evidence/310-magisk-accepted

bash scripts/collect-post-install-acceptance.sh --phase agent \
  --serial 7TV8X4RS8DAAIN4P \
  --output-dir /d/PearlAgentBuild/device-evidence/310-agent-accepted
```

`carrier` enforces pearl/SDK 36/boot-complete/slot-A/orange state and exact XiaoAi version plus full APK hash. `magisk` additionally enforces root and Magisk 30.7. `agent` additionally verifies the Nexus APK, Vector/Agent module identities, pinned ARM64 Hermes build, secret file metadata without secret contents, supervisor/bridge PID identity, localhost listener, and non-secret Nexus provisioning shape. Every capture is atomically published with `manifest.sha256`, refuses overwrite, records no secret value, and contains no device mutation command. It complements rather than replaces manual hardware, voice, rollback, fail-open and endurance tests.

## OS4 and recovery boundary

The OS4 ZIP remains DSU-only and is not part of these five gates. Test it later only as a disposable DSU after the carrier/Agent stack is stable.

The official Android 15 fastboot package remains the recovery anchor. `scripts/restore-official-minimal.sh` now provides a separate dry-run/acknowledgement-gated clean restore from the exact audited official extraction. It writes only slot-A boot/vendor_boot/dtbo/vbmeta, shared official super, unslotted official cust, erases metadata/userdata, activates A and reboots; `-S 256M` is enforced for super/cust, and preloader, efuse, GPT, slot B plus all low-level firmware remain excluded. Its full-size real-image mocked dry run and regression test passed, but it too requires physical live-variable dry run before any use.

This MediaTek device must not be described as guaranteed Qualcomm 9008 recoverable; severe-brick BROM/Preloader/DA recovery depends on compatible tooling and authorization.
