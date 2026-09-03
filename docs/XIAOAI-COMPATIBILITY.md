# XiaoAi compatibility audit

## Official recovery-baseline APK

The final ROM baseline is the hash-verified official pearl fastboot package `OS3.0.3.0.VLHCNXM`. Its sparse `super.img` was strictly expanded, its populated logical partitions were extracted read-only, and the complete top-level/chained AVB graph verified before XiaoAi was inspected.

| Field | Official baseline value |
|---|---|
| source partition | AVB-verified `product_a.img` |
| EROFS path | `/app/VoiceAssistAndroidT/VoiceAssistAndroidT.apk` |
| package | `com.miui.voiceassist` |
| versionCode | `507009011` |
| versionName | `7.9.11.1910` |
| platform/compile SDK | Android 15 / 35 |
| APK bytes | `161,303,814` |
| ZIP entries | `9,697` |
| APK SHA-256 | `dd75a0d9b1c0803906eafe72126dc44a150f16337e26c8fcf53304f13d74140b` |
| signer certificate SHA-256 | `c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025` |
| signatures | v1=true, v2=true, v3=true; v3.1/v3.2/v4=false |

Validation required all ZIP entry CRCs, `aapt dump badging`, and `apksigner verify --verbose --print-certs`. The APK is not committed or copied into the ROM payload.

## Original third-party input APK

The original third-party ROM remains AVB-inconsistent and is not an official/production baseline. Under the user's explicit experimental exception, its controlled 310 carrier is now the selected first boot path; the independently extracted `product_a.img` therefore supplies the exact XiaoAi identity and static hook evidence for that experimental carrier:

| Field | Original-input value |
|---|---|
| versionCode / versionName | `507012002` / `7.12.2.0318` |
| APK bytes / ZIP entries | `170,153,933` / `8,730` |
| APK SHA-256 | `326fe0601b11698e70f96aca5dc05d1c783cf675ebc872024e96b405aaf64406` |
| signer certificate SHA-256 | `c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025` |
| signatures | v1=true, v2=true, v3=true; v3.1/v3.2/v4=false |

The matching Xiaomi signer does not make the rejected third-party ROM or its logical AVB layout acceptable. Exact-version configs for the two APKs remain isolated.

## Sparse super and EROFS provenance

The first pinned third-party `lpunpack.py` pass exposed a correctness defect: its sparse `FILL` branch extended a zero-filled hole and ignored the four-byte fill pattern. This changed only `vendor_a.img` in this official image. The flawed vendor failed AVB, while product/system/system_ext already passed, proving the failure was localized enough to investigate rather than mislabel the official package.

`scripts/unsparse-android-image.py` now strictly validates and expands Android sparse v1 images:

- RAW payload size must equal declared output size;
- non-zero FILL patterns are repeated exactly;
- DONT_CARE produces logical zeros;
- CRC32 chunks must contain four bytes and zero output blocks;
- header sizes, chunk sizes, total blocks, declared chunk count and input EOF are checked;
- output remains `.partial`, is fsynced, size-checked and atomically published;
- malformed input removes partial output.

Three regression tests cover non-zero FILL, RAW, DONT_CARE, CRC32, malformed FILL, trailing input and cleanup. The official sparse super expanded to a raw logical device with SHA-256:

`038483b5afccb91c843cd3143380b332750b26c0130bfbb3eb87aa02d088a1f0`

Raw LP extraction changed only `vendor_a.img` relative to the flawed sparse conversion. The strict logical-partition manifest SHA-256 is:

`dacfb6bb19c39f347a2af27ce28266c56175d569823c1d1c16dc5357dd0acf77`

The canonical LF file is tracked as `manifests/official-pearl-logical.sha256`; all eight lines were rehashed against the strict outputs.

Both XiaoAi APKs were then extracted using:

- official `erofs-utils` repository;
- commit `f36cadb5c563995ab3aa8572a60ed6b721b9557d` (2026-08-19);
- `fsck.erofs 1.9.4` built in an isolated portable MSYS2 environment;
- lz4, lz4hc, lzma, deflate and zstd decompressors;
- `fsck.erofs --extract` against each hash-recorded `product_a.img`.

The earlier pure-Python EROFS parser does not support compact compressed layout 3. The Ray extractor's old liberofs mapping loop collapsed/overlapped compact extents; grafting current fsck mapping logic onto that old ABI either corrupted output or returned EIO. It is rejected and must not be built, bundled or used as an audit authority. Official `fsck.erofs` is the compact EROFS extraction authority.

## Complete official AVB result

Pinned AOSP `avbtool.py` commit `c5066a96caa7bf4150c0a8cc8cc14ab81733fdc7` was run under portable MSYS2/POSIX semantics. Native Windows failure was only OpenSSL temporary-file sharing and is not used as evidence.

`verify_image --follow_chain_partitions` succeeded for all 15 emitted checks:

- top-level `vbmeta` SHA256_RSA2048;
- chained `boot` footer/vbmeta and boot hash;
- chained `vbmeta_system` plus product/system/system_ext hashtrees;
- chained `vbmeta_vendor` plus vendor hashtree;
- dtbo and vendor_boot hashes;
- mi_ext, odm, odm_dlkm and vendor_dlkm hashtrees.

The chain uses public-key SHA-1 `b2a02f1e56e366d727a1a8e089762fe0b91bbc84` for top-level and child vbmeta images, with top-level AVB flags `0`.

## Dex compatibility audits

JADX CLI `1.5.6` archive SHA-256:

`545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974`

### Official 507009011

| Action surface | Exact Dex result |
|---|---|
| input capture | `OperationManager.setQueryInfo(String,String,JSONObject): void` |
| response/text/TTS stream dispatcher | `qb0.ua.z0(Instruction): void` |
| native TTS playback | `qb0.f9.onProcessOp(v90.l): boolean` |
| float detach | `FloatViewRootLayout.onDetachedFromWindow(): void` |
| activity resume | `TabHostMainActivity.onResume(): void` |
| RN card TTS state | `fa0.ac.y1(JSONObject): void` |

`qb0.ua` is `TemplateFrontendPageOperation`. Its `z0(Instruction)` consumes `Template.ToastStream`, appends markdown, handles `<FINAL>`, collects `SpeechSynthesizer.SpeakStream`, processes style-stream start/finish, and forwards the instruction to the RN card. `qb0.f9` is the native `SpeakAudioOperation`; `fa0.ac` identifies itself as `TemplateReactNativeCard`, and `y1(JSONObject)` updates illegal-content, display-complete and total-text state. Targets were selected from semantics and exact method descriptors, not name similarity.

### Original-input 507012002

| Action surface | Exact Dex result |
|---|---|
| input capture | `OperationManager.setQueryInfo(String,String,JSONObject): void` |
| response/text/TTS stream dispatcher | `cb0.db.A0(Instruction): void` |
| native TTS playback | `cb0.n9.onProcessOp(g90.l): boolean` |
| float detach | `FloatViewRootLayout.onDetachedFromWindow(): void` |
| activity resume | `TabHostMainActivity.onResume(): void` |
| RN card TTS state | `TemplateReactNativeCard.p1(JSONObject): void` |

For this version old `cb0.eb.A0(Instruction)` does not exist: `cb0.eb` is `TemplateGeneral2Operation`. Decompiled `cb0.db` is the matching `TemplateFrontendPageOperation` and has the required stream semantics.

## Bundled exact configs

Nexus includes independent version-exact assets:

- `app/src/main/assets/hooks/com.miui.voiceassist/507009011/config.json` — mandatory official recovery baseline;
- `app/src/main/assets/hooks/com.miui.voiceassist/507012002/config.json` — exact static compatibility config for the selected experimental 310 carrier;
- existing `507013003` — legacy target metadata only; it is now diagnostic-only until its exact APK SHA-256 is independently recovered and bound.

JVM tests parse the official and original-input assets and lock every audited owner, method, parameter, return descriptor and APK hash. Config resolution remains fail-closed:

- XiaoAi requires both exact installed version and exact full-APK SHA-256 before hooks may install;
- official `507009011` is bound to `dd75a0d9...d74140b`, and original-input `507012002` to `326fe060...64406`;
- nearest-version network fallback cannot install XiaoAi hooks;
- missing, malformed, unreadable or mismatched APK identity leaves native XiaoAi untouched;
- response-target timeout clears the active turn and fails open to native XiaoAi;
- power-button and original wake flow are not replaced.

## Remaining real-device gate

Static APK/Dex compatibility is necessary but not sufficient. Before enabling takeover for the selected carrier's `507012002` (or calling official-recovery `507009011` production-supported), the actual pearl device must prove:

1. Zygisk scope contains only `com.miui.voiceassist`;
2. power-button and original XiaoAi wake phrase still work before Nexus provisioning;
3. input capture records the correct dialog/query once;
4. the exact response target is captured for text and TTS turns: `cb0.db.A0` on carrier `507012002`, or `qb0.ua.z0` on official-recovery `507009011`;
5. native stream blocking happens only for an active injected turn;
6. timeout/network/service failure returns to native XiaoAi without a blank card;
7. injected `<FINAL>` ends rendering and no duplicate TTS plays;
8. disabling Vector/Nexus immediately restores fully native behavior.

Until these pass, status is **static-compatible / device-validation-required**: the carrier may proceed only through its separate stock-boot flash gate, but Nexus takeover is not yet device-accepted or eligible to remain enabled.
