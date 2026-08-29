# Nexus Pearl Debug APK 验收

- 路径：`D:\生活问答\Pearl-Agent-ROM\artifacts\nexus-1.0.1-pearl.1-debug.apk`
- 字节数：`28143496`
- SHA-256：`24f703aa93e45e2203a0f7e176da1d65eb8b06ed5ff527ec8a62da661ff58881`
- 包名：`com.niki914.nexus.agentic`
- versionCode：`7`
- versionName：`1.0.1-pearl.1`
- minSdk：`26`
- targetSdk：`34`
- ABI：`arm64-v8a, x86_64`
- 签名证书 SHA-256：``

## 已通过

- `apksigner verify --verbose --print-certs`；
- `zipalign -c -P 16 4`；
- Xposed 入口 `assets/xposed_init` 存在；
- 内置 XiaoAi `507013003/config.json` 存在；
- APK 内配置 SHA-256 与仓库源配置一致；
- ASCII 构建 APK 与仓库 artifacts 副本 SHA-256 一致；
- 完整 Gradle `testDebugUnitTest assembleDebug` 已显示 `BUILD SUCCESSFUL`。

## 用途限制

这是开发阶段 Debug 签名 APK，只用于离线验证和首轮 LSPosed 实机联调。最终刷机成品必须使用独立 Pearl release keystore 重新签名，并另行记录证书和 APK 哈希；keystore 与密码不得进入 Git。