# Nexus Pearl historical Debug APK audit

- 路径：`D:\生活问答\Pearl-Agent-ROM\artifacts\nexus-1.0.1-pearl.1-debug.apk`
- 字节数：`28290818`
- SHA-256：`6b597a0b611ebab0de31ab98ea342d23dc8b902c876406d33bf76d1515ac9982`
- 包名：`com.niki914.nexus.agentic`
- versionCode：`7`
- versionName：`1.0.1-pearl.1`
- minSdk：`26`
- targetSdk：`34`
- ABI：`arm64-v8a, x86_64`
- 签名证书 SHA-256：`127234f950598c6aed298f7f1d938c089c92ef0d31728d85f0da194a05f7eddb`

## 已通过

- `apksigner verify --verbose --print-certs`；
- `zipalign -c -P 16 4`；
- Xposed 入口 `assets/xposed_init` 存在；
- 当时内置 XiaoAi `507013003/config.json` 存在；该历史 APK 不包含后来独立审计的官方 `507009011` config；
- APK 内配置 SHA-256 与仓库源配置一致；
- Hermes token provisioner 的 64-hex 校验与 header 合并单测通过；
- ASCII 构建 APK 与仓库 artifacts 副本 SHA-256 一致；
- 完整 Gradle `testDebugUnitTest assembleDebug` 已显示 `BUILD SUCCESSFUL`。

## 用途限制

这是已被取代的开发阶段 Debug 签名 APK，只保留历史离线审计记录，**不得再安装或用于真机联调**。当前设备测试必须使用 `docs/NEXUS-RELEASE.md` 唯一批准 hash 的 Pearl release-key 签名 APK；keystore 与密码不得进入 Git。