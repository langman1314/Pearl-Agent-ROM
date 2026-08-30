# Pearl 常驻手机 Agent 架构

## 已验证事实

- 设备代号：`pearl`，Redmi Note 12T Pro China。
- 最终重建/恢复基线固定为官方 HyperOS `OS3.0.3.0.VLHCNXM` / Android 15 Stable Fastboot；完整 archive、56 个镜像 hash 与 15 项 AVB chain 检查已通过。
- 原 Android 16 输入是第三方混合移植包；其 AVB descriptor 与 logical extents 不自洽，不能通过关闭 verification 修复，也不能作为 final partition 来源。
- 官方包不存在 `init_boot.img` 与 `payload.bin`；Magisk 必须从 exact official `boot.img` 入手，且在真机 identity/rollback 门禁前不得 patch/flash。
- 官方和第三方原脚本都包含危险的 preloader/metadata/userdata 操作，定制流程禁止运行或复用。
- Agent payload 只进入 `/data/pearl-agent` 与 `/data/adb/modules/pearl-agent`，不修改 AVB 保护的 super/logical partitions。
- 先前基于第三方 boot 的 Magisk 30.7 patched image 即使二进制来源匹配也已弃用；它不是官方 baseline boot，严禁刷入。
- Android 15/16 Xposed 实现候选固定为维护中的 Vector 2.2（module id `zygisk_vector`），不用停更的旧 LSPosed release，仍需 Android 15 真机验证。
- Nexus 1.0.1 为 MIT；原生支持 DeepSeek、Skills、MCP、记忆、Root、Accessibility、Termux 和 XiaoAi Xposed Hook。
- XiaoAi exact assets 分离支持官方 baseline `507009011`、原第三方输入 `507012002` 与既有 `507013003`；非 exact fallback 不安装 Hook。

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

`cust`、`super` 与其他 logical partitions 都不是 Agent 载荷区。当前离线产物是无 partition image/无 flashing script 的 Magisk module；安装后只部署 `/data/pearl-agent` 与 `/data/adb/modules/pearl-agent`。保守 installer/rollback 命令尚未通过真机门禁前，不生成所谓 recovery ROM 包。

## XiaoAi 加固要求

- 仅精确版本配置或用户批准的本地配置可以启用 Hook。
- 最近版本回退配置只用于诊断，不安装 Hook。
- 内置官方 baseline `507009011`、原第三方输入 `507012002` 与既有 `507013003` exact configs；三者绝不互相 nearest-version 代用。
- `targetReady.await()` 必须有超时；超时清理注入状态并放行原生回答。
- Hook 缺失、Binder 断开或 Hermes 不可用时均 fail-open 到原生小爱。

## 刷机边界

- 不刷 `efuse`。
- 不刷 `preloader_raw`，除非独立救砖流程明确要求。
- data-only Agent 安装不刷 `vbmeta*`，也不通过 flags=3 绕过完整性检查。
- 首次 boot 测试前必须用真机确认 `current-slot`/`has-slot:boot`/实际 partition map，导出两槽 stock boot/vbmeta 并证明 fastboot/recovery rollback；不能先假设 pearl 的槽语义。
- API Key、Telegram Token 和签名材料只在首次启动后录入。
