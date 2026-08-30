# Additive custom wake phrase design

## Non-negotiable behavior

A custom phrase is optional and additive. It must never replace, patch or retrain stock XiaoAi DSP assets, alter the XiaoAi APK, disable the original wake phrase, or intercept the power button. Removing Nexus/Vector or disabling the feature must immediately return to stock behavior.

The feature is disabled by default and is outside the first device-acceptance release.

## Why it is deferred

The official pearl/XiaoAi stack supplies low-power DSP wake, ASR, activity/UI and TTS. The project currently has no verified API for adding a second phrase to Xiaomi's proprietary DSP model. Guessing a private API or replacing its model would risk:

- breaking suspend-time wake and power-button activation;
- keeping the application processor/microphone awake continuously;
- conflicting with XiaoAi's own AudioRecord session;
- increasing idle drain and temperature;
- creating an always-listening privacy regression;
- making OTA/XiaoAi updates unsafe.

Static Dex hook compatibility does not establish audio-path compatibility. Stock voice/fail-open and 24-hour power gates therefore come first.

## Preferred decision order

After the stock acceptance matrix passes on the device:

1. **Use a stock-supported user setting if present.** Audit the actual official `507009011` UI/settings and Xiaomi system services for a documented custom-name/wake feature. Nexus only launches through that supported result; it does not modify its files.
2. **Use a public system callback if exposed.** Accept only a permissioned Android/Xiaomi API whose lifecycle, suspend behavior and removal can be verified. Exact package/version gating still applies.
3. **Optional local detector as a last resort.** A separately reviewed on-device detector may run only with explicit consent and a visible persistent status. It must be independently disableable, use an auditable local model, avoid cloud audio, and yield immediately when XiaoAi owns the microphone.
4. **Do not ship the feature** if none of the above meets idle-power, privacy and fail-open thresholds.

No Xposed hook into unknown DSP/native symbols is an acceptable shortcut.

## Local-detector constraints, if ever selected

- model and runtime source/version/hash recorded;
- model contains no fixed user recording or secret;
- microphone permission and purpose disclosed in Nexus UI;
- no raw audio stored or logged;
- no network access from the detector process;
- explicit enable/disable control and boot-persistent opt-in state;
- process crash leaves stock XiaoAi untouched;
- power-button and original phrase tests run on every enable/disable/reboot cycle;
- detector stops while on calls, recording, camera capture or another exclusive audio session;
- Doze behavior and DSP/application-processor wakeups measured against stock;
- configurable phrase data stored under private app data, never Magisk/rootfs config;
- uninstall removes phrase/model state unless the user requests export.

## Activation semantics

A custom phrase may only request the same public entry path as a normal user activation. It must not synthesize a query or mark a Nexus takeover before XiaoAi supplies a real dialog ID/query. If activity launch, ASR or response-target capture fails, Nexus clears state and XiaoAi remains native.

## Required acceptance extension

Before release, prove at least:

1. 100 original-phrase wakes with custom detector enabled;
2. 100 power-button activations with it enabled;
3. 100 custom-phrase attempts across screen-on/off and Doze;
4. false-positive measurement over 24 hours of normal ambient audio;
5. battery/wakeup comparison against accepted stock/Nexus baseline;
6. microphone contention tests during calls, camera, recorder and XiaoAi TTS;
7. disable/reboot/uninstall restore stock behavior;
8. network capture proves local detector sends no audio.

Until those pass, the official project promise remains power-button plus stock XiaoAi wake, not a custom phrase.
