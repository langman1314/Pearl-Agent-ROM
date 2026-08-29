# Pearl 常驻手机 Agent 架构

## 已验证事实

- 设备代号：`pearl`，Redmi Note 12T Pro。
- 上游 ROM 输入标称 HyperOS `OS3.0.310.0.WAACNXM` / Android 16，但它是第三方混合移植包；AVB metadata 实际混合 Android 15 system 与 Android 12 vendor/boot。
- 包中存在 `boot.img`、`vendor_boot.img`、`dtbo.img`、`vbmeta*.img`、`cust.img` 和 `super.zst`。
- 不存在 `init_boot.img` 与 `payload.bin`；Magisk 从 `boot.img` 入手。
- 原恢复脚本会写入 `efuse`，定制流程禁止照搬。
- `cust.img` 是约 4 KiB 的空 EROFS 占位镜像，不能承载 Agent。
- Magisk patched boot 的 arm64 `magisk` / `magiskinit` 已逐字节匹配官方 Magisk 30.7 Release，但其 AVB flags=3 未重签且 boot hash 不匹配，当前状态为 NO FLASH。
- Android 16 Xposed 基线固定为维护中的 Vector 2.2（module id `zygisk_vector`），不用停更的旧 LSPosed release。
- Nexus 1.0.1 为 MIT；原生支持 DeepSeek、Skills、MCP、记忆、Root、Accessibility、Termux 和 XiaoAi Xposed Hook。
- XiaoAi 资源库当前精确支持 `versionCode=507013003`，配置标记 beta。

## 分层架构

```text
HyperOS / XiaoAi
  ├─ 原厂 DSP 唤醒、ASR、UI、TTS
  ├─ Vector (LSPosed-compatible) + Nexus
  │    ├─ DeepSeek 快速决策
  │    ├─ Accessibility / Root 手机控制
  │    └─ HTTP MCP Client
  └─ Magisk service
       └─ Debian chroot
            ├─ Hermes Agent
            └─ Hermes MCP Bridge :51338
```

Nexus 是手机前台主 Agent；Hermes 是后台深度工作 Agent，不作为平级规划器。

## 安装载荷

`cust` 与 `super` 均不作为首选载荷区。Recovery 安装包新增 `agent/`，刷机阶段将离线载荷部署到 `/data/pearl-agent` 与 `/data/adb/modules/pearl-agent`。`super.zst` 保持原样。

## XiaoAi 加固要求

- 仅精确版本配置或用户批准的本地配置可以启用 Hook。
- 最近版本回退配置只用于诊断，不安装 Hook。
- 内置 `507013003/config.json` 作为断网精确回退。
- `targetReady.await()` 必须有超时；超时清理注入状态并放行原生回答。
- Hook 缺失、Binder 断开或 Hermes 不可用时均 fail-open 到原生小爱。

## 刷机边界

- 不刷 `efuse`。
- 不刷 `preloader_raw`，除非独立救砖流程明确要求。
- 不盲刷 `vbmeta`。
- 首次测试只修改当前槽 boot，另一槽保留原厂 boot。
- API Key、Telegram Token 和签名材料只在首次启动后录入。
