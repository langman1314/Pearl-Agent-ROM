# Pearl 常驻手机 Agent 架构

## 已验证事实

- 设备代号：`pearl`，Redmi Note 12T Pro China。
- 恢复锚固定为官方 HyperOS `OS3.0.3.0.VLHCNXM` / Android 15 Stable Fastboot；完整 archive、56 个镜像 hash 与 15 项 AVB chain 检查已通过。
- 原 Android 16 输入是第三方混合移植包，其 AVB descriptor 与 logical extents 不自洽；它仍不是可信官方基线，但按用户明确风险选择作为已知可启动的 experimental carrier，所有限制见 `docs/EXPERIMENTAL-310-CARRIER.md`。
- 310 的 stock `boot`/`vendor_boot`/`dtbo` 与官方包逐字节相同；fresh Magisk 30.7 patch 已从该 exact boot 独立重复生成并审计，只在 carrier stock-boot 验收后才可进入 boot-A 单分区阶段。
- 官方和第三方原脚本都包含危险的 preloader/metadata/userdata 操作，定制流程禁止运行或复用。
- Agent payload 只进入 `/data/pearl-agent` 与 `/data/adb/modules/pearl-agent`，不修改 AVB 保护的 super/logical partitions。
- 历史 Magisk 30.7 patched image 的输入字节后来证明与 official boot 相同，但它在正式 provenance/slot/rollback 门禁前生成且 AVB 不自洽，仍永久弃用、严禁刷入。
- Android 15/16 Xposed 实现候选固定为维护中的 Vector 2.2（module id `zygisk_vector`）；官方 stable 3080 asset 已按 GitHub digest 固定，仍需 310 Android 16 真机验证。
- Nexus 1.0.1 为 MIT；原生支持 DeepSeek、Skills、MCP、记忆、Root、Accessibility、Termux 和 XiaoAi Xposed Hook。
- XiaoAi Hook 同时要求 exact versionCode 与 exact full-APK SHA-256；官方 `507009011`、原第三方 `507012002` 已绑定 hash，既有 `507013003` 在补齐 APK hash 前降为 diagnostic-only。

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

`cust`、`super` 与 logical partitions 都不是 Agent 载荷区。实验 carrier 的一次性系统安装会用固定 allowlist 写入 `super` 和三份 vbmeta，但独立的 Agent Magisk module 仍无 partition image/flashing script，安装后只部署 `/data/pearl-agent` 与 `/data/adb/modules/pearl-agent`。carrier、boot switch 与 data-only Agent 三阶段不得合并成不可审计的一键脚本。

## XiaoAi 加固要求

- 仅同时匹配 package、versionCode 与 full-APK SHA-256 的配置可以启用 Hook；签名相同也不能替代字节匹配。
- 最近版本回退配置只用于诊断，不安装 Hook。
- 内置官方 baseline `507009011` 与原第三方输入 `507012002` 各自绑定独立 APK hash；`507013003` 缺 hash，因此不得安装 Hook。
- `targetReady.await()` 必须有超时；超时清理注入状态并放行原生回答。
- Hook 缺失、Binder 断开或 Hermes 不可用时均 fail-open 到原生小爱。

## 刷机边界

- 不刷 `efuse`。
- 不刷 `preloader_raw`，除非独立救砖流程明确要求。
- data-only Agent 安装不刷 `vbmeta*`；只有单独的 310 experimental carrier 阶段使用其已明确披露的 flags=3 vbmeta，绝不把该状态称为完整性验证通过。
- 真机 `current-slot`/`has-slot`/partition map/anti/capacity 已确认；`fetch` 与 temporary `boot` 均不受支持。用户仅为 unverified experiment 放弃当前分区备份，生产验收仍不视为通过，stock boot-A 恢复与官方 fastboot 包继续保留。
- API Key、Telegram Token 和签名材料只在首次启动后录入。
