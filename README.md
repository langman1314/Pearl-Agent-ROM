# Pearl Agent ROM

Redmi Note 12T Pro (`pearl`) 的常驻 Android Agent 定制工程。

## 目标

在 HyperOS 3 / Android 16 基础上构建可恢复、可验证的刷机成品：

- Magisk root 与开机常驻服务；
- Nexus 接管 XiaoAi 的低功耗唤醒、ASR、UI 与 TTS；
- Nexus 使用 DeepSeek 完成快速问答与手机控制；
- Debian chroot 中运行 Hermes Agent；
- Nexus 通过本地 HTTP MCP 把复杂任务委派给 Hermes；
- 原厂回滚、单槽测试和救砖路径。

## 原始 ROM

- 文件：`D:\EdgDownloads\pearl_Note12TPro_OS3.0.310.0.WAACNXM_16.0.zip`
- 版本：`OS3.0.310.0.WAACNXM` / Android 16
- SHA-256：`b684924f00fe5663f6438ddc482efc6a556f6af3475f94613b95f16dc0cba3c6`
- 大小：`5,942,636,095` bytes

## 安全边界

1. 永不覆盖原始 ROM。
2. 日常刷机脚本不得刷写 `efuse` 或 `preloader`。
3. ROM、镜像、APK、rootfs、签名密钥和 API Key 不进入 Git。
4. 每个变更镜像必须有 SHA-256 和原厂回滚镜像。
5. 离线结构校验完成前不得实际刷机。

详见 `docs/ARCHITECTURE.md`。
