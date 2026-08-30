# XiaoAi compatibility audit

## Audited APK identity

The original third-party ROM input remains **NO FLASH**, but its extracted `product_a.img` is still useful as read-only evidence for the XiaoAi package that the original integration expected.

| Field | Value |
|---|---|
| EROFS path | `/app/VoiceAssistAndroidT/VoiceAssistAndroidT.apk` |
| package | `com.miui.voiceassist` |
| versionCode | `507012002` |
| versionName | `7.12.2.0318` |
| platform/compile SDK | Android 15 / 35 |
| APK bytes | `170,153,933` |
| ZIP entries | `8,730` |
| APK SHA-256 | `326fe0601b11698e70f96aca5dc05d1c783cf675ebc872024e96b405aaf64406` |
| signer certificate SHA-256 | `c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025` |
| signatures | v1=true, v2=true, v3=true; v3.1/v3.2/v4=false |

Validation required all ZIP entry CRCs, `aapt dump badging`, and `apksigner verify --verbose --print-certs`. The APK is not committed or copied into the ROM payload.

## EROFS extraction provenance

The earlier pure-Python parser does not support compact compressed layout 3. The third-party Ray extractor also proved unsuitable: its old liberofs mapping loop collapsed/overlapped compact extents and produced invalid ZIP offsets. Patching current fsck logic onto that old ABI either corrupted output or returned EIO, so that path was abandoned rather than weakened.

The valid extraction used:

- official `erofs-utils` repository;
- commit `f36cadb5c563995ab3aa8572a60ed6b721b9557d` (2026-08-19);
- `fsck.erofs 1.9.4` built in an isolated portable MSYS2 environment;
- decompressors: lz4, lz4hc, lzma, deflate, zstd;
- `fsck.erofs --extract` against the hash-recorded `product_a.img`;
- resulting file size exactly matches the inode size and produces a complete valid APK ZIP.

The prior Ray-based executable remains audit-only and is not allowed in the ROM. For compact layout extraction, official `fsck.erofs` is now the authority.

## Dex compatibility audit

JADX CLI `1.5.6` archive SHA-256:

`545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974`

The six hook owner surfaces were decompiled directly from the exact APK. Five old signatures remain unchanged; the response dispatcher was re-obfuscated:

| Action surface | 507012002 result |
|---|---|
| input capture | `OperationManager.setQueryInfo(String,String,JSONObject): void` exists |
| response/text/TTS stream dispatcher | old `cb0.eb.A0(Instruction)` does **not** exist |
| audited replacement dispatcher | `cb0.db.A0(Instruction): void` exists |
| native TTS playback | `cb0.n9.onProcessOp(g90.l): boolean` exists |
| float detach | `FloatViewRootLayout.onDetachedFromWindow(): void` exists |
| activity resume | `TabHostMainActivity.onResume(): void` exists |
| RN card TTS state | `TemplateReactNativeCard.p1(JSONObject): void` exists |

The replacement is not selected by name similarity alone. Decompiled `cb0.db` is `TemplateFrontendPageOperation`; its `A0(Instruction)`:

- consumes `Template.ToastStream` and appends markdown text;
- treats `<FINAL>` as stream completion;
- consumes `SpeechSynthesizer.SpeakStream` fragments;
- forwards stream instructions to `TemplateReactNativeCard`;
- is therefore the same semantic response target required by capture, native-stream blocking, whitelist filtering and manual stream-card injection.

By contrast, current `cb0.eb` is `TemplateGeneral2Operation` and has no compatible `A0` method.

## Bundled exact config

Nexus now includes:

`app/src/main/assets/hooks/com.miui.voiceassist/507012002/config.json`

Only the version identity and the five dispatcher owner references differ from the existing `507013003` config (`cb0.eb` -> `cb0.db`); all other surfaces were verified independently. A JVM test parses the asset and locks every audited owner/method/parameter/return signature.

Config resolution remains fail-closed:

- only an exact installed version may install XiaoAi hooks;
- nearest-version network fallback cannot install hooks;
- missing/malformed config leaves native XiaoAi untouched;
- response-target timeout clears the active turn and fails open to native XiaoAi;
- power-button/original wake flow is not replaced.

## Remaining real-device gate

Static APK/Dex compatibility is necessary but not sufficient. Before calling 507012002 production-supported, test on the actual pearl device must prove:

1. Zygisk scope contains only `com.miui.voiceassist`;
2. power-button and original XiaoAi wake phrase still work before Nexus provisioning;
3. input capture records the correct dialog/query once;
4. `cb0.db.A0` target capture occurs for text and TTS turns;
5. native stream blocking happens only for an active injected turn;
6. timeout/network/service failure returns to native XiaoAi without a blank card;
7. injected `<FINAL>` ends rendering and no duplicate TTS plays;
8. disabling Vector/Nexus immediately restores fully native behavior.

Until these pass, status is **static-compatible / device-validation-required**, not flash-ready.
