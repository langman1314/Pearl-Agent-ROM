# Magisk / Xposed 平台组件 provenance

记录日期：2026-09-16。

## Magisk 30.7 Release

### 官方来源

- 项目：`https://github.com/topjohnwu/Magisk`
- tag：`v30.7`
- commit：`e8a58776f1d7bdf852072ad0baa6eceb9a1e4aac`
- commit subject：`Release Magisk v30.7`
- 官方 APK：`Magisk-v30.7.apk`
- APK 字节数：`11,613,864`
- APK SHA-256：`e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5`
- APK signer：`CN=John Wu, L=Taipei, C=TW`
- signer certificate SHA-256：`b4cb83b4dad99f997dbe872f013aa16c14eec41d167021f371f7e1330f273ee6`

APK 已通过 Android build-tools 37 `apksigner verify --verbose --print-certs`。官方 APK、源码 checkout 和审计解包文件只放在被 Git 忽略的 `.tools/` / `artifacts/`，不提交二进制。

### 历史 patched boot 归因（拒绝作为 release）

历史实验镜像 ramdisk 的 `.backup/.magisk` 内容：

```text
KEEPVERITY=true
RECOVERYMODE=false
SHA1=2996ceec04554b151dd64c1bd6dfc6ba3ec0f7b8
```

该 SHA-1 已重新证明与官方 `OS3.0.3.0.VLHCNXM/images/boot.img` 完整文件精确匹配；官方 boot SHA-256 为 `8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82`。ramdisk 内二进制自报 `30.7:MAGISK:R`，且逐字节 provenance 为：

| payload | historical patched boot SHA-256 | 官方 v30.7 APK payload | 结果 |
|---|---|---|---|
| arm64 `magisk` | `2d8419018dda41f7d9aca94c0ca8f926f3b8447ca5cf7fb71faeb8d05e29694e` | 相同 | 精确匹配 |
| arm64 `magiskinit` / boot `init` | `383670a7ba3a6a4b79e5f3467e1da4b66a5df66a9b356ab9f70916854dd6b468` | 相同 | 精确匹配 |

这只证明历史实验的输入字节和 Magisk payload 来源，不批准其过程或产物。它在官方 baseline/process gate 建立前生成，flags=3 未重签且 boot hash descriptor 不匹配，也未经过目标设备 slot/rollback 验收，因此永久 **NO FLASH**。最终镜像必须从 fresh hash-checked official boot 重新生成。

## Vector 2.2（维护中的 LSPosed 后继）

旧 `LSPosed/LSPosed` 不作为新部署基线。当前候选是维护中的 JingMatrix Vector：它是 Zygisk ART hook framework，兼容 legacy Xposed API，源码声明 Android 8.1 到 Android 17。最终目标已固定为官方 Android 15；宽版本声明不能代替 pearl/Android 15 真机验证。

### 固定版本

- 项目：`https://github.com/JingMatrix/Vector`
- tag：`v2.2`
- commit：`88f8e1faa8b4e7ce20aefabe9c295cd746ea038e`
- commit subject：`Release Vector v2.2`
- release：`Vector-v2.2-3080-Release.zip`
- module id：`zygisk_vector`
- module version：`v2.2 (3080-88f8e1fa-JingMatrix-Vector)`
- ZIP 字节数：`9,316,843`
- ZIP SHA-256：`9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc`
- GitHub release digest：同上，下载后已精确匹配。

release ZIP 已通过：

- 路径穿越检查；
- 大小写不敏感重复路径检查；
- `module.prop`、`customize.sh`、`service.sh` 必需入口检查；
- module metadata 检查。

### 部署约束

1. 只使用 Release ZIP，不使用 Debug/PR artifact；
2. Magisk 30.7 中启用 Zygisk 后再安装 Vector；
3. Nexus Xposed module 只 scope 到 `com.miui.voiceassist`，不扩大到 `android` / `system_server`；
4. XiaoAi 版本不等于已支持的精确 `versionCode` 时，Nexus 必须 fail open，不安装 hook；
5. 必须在真机验证 Zygisk、Vector daemon、XiaoAi scope、重启恢复和卸载回滚后，才能进入成品包。

## 仍需真机验证

- Magisk app/daemon 均报告 30.7 Release；
- Zygisk 正常加载且没有 bootloop；
- Vector 2.2 manager/daemon 通信正常；
- Nexus 仅注入目标 XiaoAi 进程；
- 禁用 Nexus 或卸载 Vector 后 XiaoAi 原生能力完整恢复；
- Magisk 模块 crash fuse、Hermes chroot 与 MCP Bearer auth 在 Android SELinux 环境中成立。
