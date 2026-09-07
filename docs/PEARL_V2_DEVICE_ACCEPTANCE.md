# Pearl V2 真机验收

## 候选版本

- Nexus：`1.0.1-pearl.14`，versionCode `20`
- APK：`D:\PearlAgentBuild\releases\nexus-1.0.1-pearl.14-v20\Nexus-1.0.1-pearl.14-v20.apk`
- APK SHA-256：`9a0c522d3fe2b229b5aea82b6406a623d0d5c81eaabaddaec31189ed896f0309`
- 签名：APK Signature Scheme v2，单 signer，RSA-4096
- 证书 SHA-256：`01e17b4c40f9b87973dc0c4bd05b7e36bcf2585eac64a5558e8973ed06f482d4`

不得覆盖或修改历史 APK。安装前再次计算 hash 和读取 package/version/signature。

## 设备前置检查

1. `adb devices -l` 必须出现预期 serial；仅有 USB/MTP 不算 ADB 可用。
2. 读取设备型号、codename、SDK、当前 Nexus versionCode、XiaoAi versionCode 和 APK path。
3. 本地官方 ROM 缓存中的 XiaoAi 507012002 APK 已按 SHA-256 `326fe0601b11698e70f96aca5dc05d1c783cf675ebc872024e96b405aaf64406` 精确确认，并完成目标 API/混淆类反编译。真机恢复后仍需读取手机实际 APK hash；不一致时不得启用精确 Hook。
4. 不卸载 Nexus、不清数据、不修改 boot/vbmeta/super/preloader/GPT/slot B。

## 安装与启动

1. 使用 `adb install -r` 安装候选 APK；必须保持签名和应用数据。
2. 验证设备报告 versionCode 20、versionName `1.0.1-pearl.14`。
3. 确认 Vector/LSPosed scope 包含 `com.miui.voiceassist`，重启宿主进程或设备使 Hook 生效。
4. 确认 Agent module、Hermes bridge 和 loopback MCP listener 健康；不得打印 bearer token。

## 固定文本输出实验

在调用外部模型前先用固定文本证明输出生命周期：

- 同 dialog 文本卡出现；
- final 能结束 loading；
- unrelated dialog/系统提示不被阻断；
- 关闭、停止、再次唤醒均可恢复；
- 当前版本不宣称独立 TTS，除非另有真机固定文本播报证据。

## 输入实验

每类至少重复 3 次并保存 logcat 时间线：

1. 唤醒词 + 普通问答；
2. 长按唤醒 + 普通问答；
3. 连续两轮相同文本（必须都执行）；
4. 快速连续不同问题；
5. partial ASR（不得创建任务）与 final ASR（创建一次任务）；
6. 可用时测试离线路径。

证据必须包含 `SpeechRecognizer.RecognizeResult isFinal=true`、Nexus request source `final_asr`、reservation/commit、同 dialog 原生 action 被阻断、Nexus 回答 final。

## 普通回答验收

- Hermes MCP discovery 不得阻塞首轮普通回答。
- 模型回答必须来自 Nexus Agent；不能只看到 XiaoAi 原生 timeout card。
- 记录首个 final ASR、模型请求、首字和 final 的时间。
- 连续 10 轮无永久 loading、无跨 dialog 文本、无下一轮被旧 owner 阻断。

## 手机控制验收

至少各完成一个原生 App 和第三方 App 工作流：

1. Nexus 获得 request ownership；
2. 原生 XiaoAi 未并发执行；
3. Nexus 调用受约束本机工具；
4. `screen_content/search_nodes` 返回 snapshot_id；
5. `node_action` 使用同一 snapshot_id，过期 snapshot 必须返回 `SNAPSHOT_STALE`；
6. 每次动作后重新观察并验证前台 package/window 与最终状态。

仅有 HTTP 200、模型声称成功或 App 被启动都不算完成。发送、提交、下单等副作用结果不确定时标记 unknown，禁止自动重放。

## Fail-open / 故障验收

- Nexus runtime 未 reserve 成功：原生 XiaoAi 正常继续。
- takeover rules 读取异常：release reservation，原生继续。
- final ASR payload 不兼容：不接管，Template.Query/setQueryInfo 兼容兜底。
- Nexus 已开始副作用后发生超时：不得把原始请求盲目交给原生重放。
- old timeout/final 不得清理新 turn。

## 稳定性与回滚

- 重启后 Agent module、bridge、Nexus Hook 持续可用。
- 升级 Agent rootfs 时，旧版本按自身 installed BUILD record 自验证并保留为 rollback；版本不同不得误报 corruption。
- 回滚仅使用已验证的历史 Nexus APK/Agent rootfs，保留应用数据和签名身份。

## 当前阻塞记录

候选构建完成时，Windows 未枚举任何 Android/Xiaomi/MTP/ADB 设备，`adb devices -l` 为空。因此安装和以上真机验收尚未执行；不得把源码测试或签名校验表述为最终手机交付完成。
