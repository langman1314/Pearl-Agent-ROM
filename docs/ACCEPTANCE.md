# Pearl Agent staged acceptance plan

Every production stage is fail-stop. Passing later software work does not silently waive an earlier gate. For the separate unverified 310 experiment, the user's explicit current-partition backup waiver and data-loss acceptance are recorded in `docs/EXPERIMENTAL-310-CARRIER.md`; waived items remain visibly unpassed rather than being marked successful. Store evidence outside Git and record SHA-256 for every binary/image used.

## Stage 0 — host-only release evidence

Required before connecting the phone:

- [x] official fastboot archive MD5/SHA-256 and safe extraction verified;
- [x] all 56 official image hashes recorded;
- [x] strict Android sparse expansion tested;
- [x] complete official boot/vbmeta/logical AVB graph verified;
- [x] official XiaoAi APK identity/signatures/CRC and exact Dex targets audited;
- [x] Nexus release built from source, all unit tests passed, release signer fixed;
- [x] Hermes rootfs, static ARM64 zstd and Magisk module CI artifact fully green and downloaded manifest independently rechecked (`run 33319865389`);
- [ ] external backup of Nexus release key confirmed;
- [x] approved Magisk 30.7, Vector 2.2, Nexus pearl.2 and Agent module artifacts copied to the controlled release directory with one tracked hash manifest.

Exit criterion: one release manifest identifies every non-stock artifact. Status remains NO FLASH.

## Stage 1 — read-only target identity

Run both modes of `scripts/collect-device-preflight.sh` and verify each manifest.

- [x] ADB product/vendor/system identity collected;
- [x] current fingerprint/SDK/security patch/XiaoAi version recorded;
- [x] verified-boot and lock properties recorded;
- [x] fastboot product/unlocked/secure/anti variables recorded;
- [x] current-slot/slot-count/has-slot and boot/vbmeta sizes reconciled;
- [ ] recovery and fastboot remain reachable independently of Android.

Stop on a non-pearl identity, ambiguous slots, unexpected lock state, evidence hash failure or anti-rollback conflict.

## Stage 2 — stock backup and official recovery rehearsal

This stage requires a separately reviewed root/recovery method; the generic collector intentionally does not read partition contents.

- [ ] export every available stock boot and vbmeta slot without writing partitions;
- [ ] hash and copy backups to two independent host locations;
- [ ] compare currently installed stock images with the approved official package where applicable;
- [x] prepare minimal stock-boot restore commands using the actual partition map;
- [x] review the stock/Magisk boot-switch command transcript and prove it has no efuse/preloader/super/userdata/metadata side effect;
- [ ] rehearse recovery entry and device detection without flashing (fastboot entry passed; independent recovery remains unproven).

Exit criterion: Android can fail to boot without losing an independently accessible stock restore route.

## Stage 3 — controlled Magisk boot experiment

- [x] regenerate the patch from the exact official/310-identical boot SHA-256, never reuse the historical patched image;
- [x] record Magisk asset identity and patch logs without secrets;
- [x] pull, hash and unpack the result; compare stock/patched components with pinned `magiskboot`;
- [x] validate header/geometry/kernel/bootconfig and document embedded AVB flags honestly;
- [x] test temporary boot support (bootloader returned `unknown command`) and provide a separately gated slot-A plan plus exact stock rollback;
- [ ] verify Android reaches boot completion, ADB, Wi-Fi, telephony and stock XiaoAi;
- [ ] immediately execute stock rollback and prove it works before continuing.

Do not install Vector or Nexus in this stage.

## Stage 4 — data-only Hermes module

- [ ] reinstall the accepted patched boot state only after Stage 3 rollback succeeds;
- [ ] install the hash-approved Magisk module ZIP;
- [ ] verify installer device/SDK/Magisk/free-space/hash gates;
- [ ] confirm no partition is written by the module;
- [ ] inspect private `/dev`, devpts, `/proc`, `/run`, absence of `/dev/block` and absence of sysfs;
- [ ] run `scripts/provision-hermes-after-root.sh` and verify hidden-input/ADB-stdin/mode-0600 Hermes credential provisioning without terminal, argument or host-file exposure;
- [ ] launch Nexus, grant its one-time root request, and verify it reads the generated MCP token into the local Bearer header; provision the Nexus main-Agent DeepSeek credential separately through Android UI;
- [ ] validate authenticated `127.0.0.1:51338/mcp` and reject missing/wrong Bearer tokens;
- [ ] exercise submit/status/cancel/run and reboot persistence;
- [ ] force three short crashes and verify fuse/backoff; test disable and one-version rootfs rollback.

Exit criterion: Hermes survives reboot and fails closed without token while Android/XiaoAi remain native.

## Stage 5 — Vector and Nexus scope

- [ ] install hash-approved Vector 2.2 and the approved Nexus release APK;
- [ ] enable Zygisk/Vector scope only for `com.miui.voiceassist`;
- [ ] prove no other package is in module scope;
- [ ] on the selected 310 carrier, verify XiaoAi exact version `507012002` and full-APK SHA-256 `326fe0601b11698e70f96aca5dc05d1c783cf675ebc872024e96b405aaf64406` before enabling hooks (the official Android 15 recovery path instead uses its separately bound `507009011` identity);
- [ ] verify unsupported or fallback config does not install hooks;
- [ ] reboot and capture Nexus/Vector/module logs without API keys or MCP token.

Exit criterion: disabling Nexus or removing its scope restores fully stock XiaoAi after reboot.

## Stage 6 — voice and fail-open behavior

- [ ] power-button activation works before and after provisioning;
- [ ] original XiaoAi wake phrase and native answers remain functional;
- [ ] exact query/dialog capture occurs once;
- [ ] `qb0.ua.z0(Instruction)` response target is captured;
- [ ] native stream/TTS blocking occurs only for an active injected turn;
- [ ] Nexus text chunks render and `<FINAL>` terminates once;
- [ ] no duplicate native/injected TTS occurs;
- [ ] Hermes delegation works for a complex task;
- [ ] disable network, kill Nexus service, stop Hermes and supply wrong token separately; every case returns to native XiaoAi without blank UI or wake breakage;
- [ ] response-target timeout after 8 seconds clears injection state.

Custom wake phrase remains disabled until this stock-flow matrix passes. It must be additive and removable, never a destructive replacement for XiaoAi DSP models.

## Stage 7 — endurance and power

- [ ] 24-hour idle test with screen off and Doze;
- [ ] record battery drain, idle RAM/CPU, temperature and wakeups against stock baseline;
- [ ] 100 sequential voice turns without leaked turn state;
- [ ] network handover, airplane-mode cycle and DNS recovery;
- [ ] reboot loop and crash-fuse behavior;
- [ ] storage-pressure/log-rotation behavior;
- [ ] no thermal or battery regression beyond the approved threshold.

Thresholds must be chosen from measured stock data, not invented before the device run.

## Stage 8 — final rollback acceptance

- [ ] disable Nexus scope and prove native XiaoAi;
- [ ] uninstall Nexus/Vector and prove native XiaoAi;
- [ ] disable/uninstall Magisk module while preserving user data as documented;
- [ ] securely purge retained Hermes secrets/data only after explicit approval;
- [ ] restore stock boot using the rehearsed path;
- [ ] verify official boot hash/state and normal reboot;
- [ ] retain official fastboot package and all evidence externally.

Only after all applicable boxes pass may the release be described as device-accepted. Even then, project installers must never include efuse, preloader or generic Xiaomi `flash_all` behavior.
