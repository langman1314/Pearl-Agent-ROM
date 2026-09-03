# Pearl Agent ROM

Redmi Note 12T Pro (`pearl`) 的常驻 Android Agent 定制工程。

## 目标

通用 Agent 组件仍以可核验的官方 pearl 包作为恢复锚；按用户明确风险选择，当前设备实验路线使用已知可启动但 AVB 不自洽的 `OS3.0.310.0` Android 16 carrier，并通过独立最小化脚本分阶段部署，绝不把它冒充官方/可验证固件：

- Magisk root 与开机常驻服务；
- Nexus 接管 XiaoAi 的低功耗唤醒、ASR、UI 与 TTS；
- Nexus 使用 DeepSeek 完成快速问答与手机控制；
- Debian chroot 中运行 Hermes Agent；
- Nexus 通过本地 HTTP MCP 把复杂任务委派给 Hermes；
- 原厂回滚、单槽测试和救砖路径。

## 不可变上游 ROM 输入

- 文件：`D:\EdgDownloads\pearl_Note12TPro_OS3.0.310.0.WAACNXM_16.0.zip`
- 标称版本：`OS3.0.310.0.WAACNXM` / Android 16
- SHA-256：`b684924f00fe5663f6438ddc482efc6a556f6af3475f94613b95f16dc0cba3c6`
- 大小：`5,942,636,095` bytes
- 身份：第三方 `mytiantian` 混合移植包，不是 Xiaomi 官方 fastboot ROM；AVB metadata 混合 Android 15 system 与 Android 12 vendor/boot，详见 `docs/AVB-CHAIN.md`。
- 原包脚本会刷写 `preloader1/2`，本工程严禁运行或复用该脚本；实验路线只使用 `docs/EXPERIMENTAL-310-CARRIER.md` 的固定最小 allowlist。

另一个输入 `HarmonyOS4_DSU侧载尝鲜版12tp_by是天天吖.zip` 只有 EROFS `system.img`/`vendor.img`，缺少 boot chain，保留为 DSU 研究输入而不合并进 carrier；详见 `docs/OS4-DSU-AUDIT.md`。

## 已核验的官方重建/恢复基线

- 文件：`pearl_images_OS3.0.3.0.VLHCNXM_20260403.0000.00_15.0_cn_f1efbaeeee.tgz`
- 版本：`OS3.0.3.0.VLHCNXM` / Android 15 / Stable Fastboot
- tracker MD5：`f1efbaeeee65e1aec7ac8f7523118f92`
- SHA-256：`99e166422be4bd17237df9b70030b5f0ff1871d7b7bd858cbb62b683f070ea64`
- 大小：`7,822,735,884` bytes
- provenance、URL 与门禁：`docs/OFFICIAL-PEARL-BASELINE.md`

## 安全边界

1. 永不覆盖原始 ROM。
2. 日常刷机脚本不得刷写 `efuse` 或 `preloader`。
3. ROM、镜像、APK、rootfs、签名密钥和 API Key 不进入 Git。
4. 每个变更镜像必须有 SHA-256 和原厂回滚镜像。
5. 离线结构校验完成前不得实际刷机。

详见 `docs/ARCHITECTURE.md`、`docs/EXPERIMENTAL-310-CARRIER.md`、`docs/OS4-DSU-AUDIT.md`、`docs/OFFICIAL-PEARL-BASELINE.md`、`docs/THIRD-PARTY-RETIREMENT.md`、`docs/DEVICE-PREFLIGHT.md`、`docs/DEVICE-OBSERVATION-01.md`、`docs/NEXUS-RELEASE.md`、`docs/PHONE-ARTIFACTS.md`、`docs/XIAOAI-COMPATIBILITY.md`、`docs/CUSTOM-WAKE-PHRASE.md` 与 `docs/ACCEPTANCE.md`。
