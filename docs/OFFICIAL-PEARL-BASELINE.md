# Official pearl fastboot baseline

## 选择结果

当前第三方输入包已因 AVB descriptor 与 `super.zst` logical extent 不可能同时成立而被标记为 **NO FLASH**。项目的新 ROM/恢复基线改为：

| 字段 | 值 |
|---|---|
| 设备 | Redmi Note 12T Pro China |
| codename | `pearl` |
| 版本 | `OS3.0.3.0.VLHCNXM` |
| Android | 15 |
| 分支 | Stable |
| 方法 | Fastboot |
| 构建日期 | 2026-04-03 |
| 文件 | `pearl_images_OS3.0.3.0.VLHCNXM_20260403.0000.00_15.0_cn_f1efbaeeee.tgz` |
| 字节数 | `7,822,735,884` |
| tracker MD5 | `f1efbaeeee65e1aec7ac8f7523118f92` |
| 本地 SHA-256 | `99e166422be4bd17237df9b70030b5f0ff1871d7b7bd858cbb62b683f070ea64` |

下载 URL：

`https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/OS3.0.3.0.VLHCNXM/pearl_images_OS3.0.3.0.VLHCNXM_20260403.0000.00_15.0_cn_f1efbaeeee.tgz`

## Provenance

- tracker：`XiaomiFirmwareUpdater/miui-updates-tracker`
- tracker commit：`bd3db1fda36f9467bac923b9e1b653837ea49708`
- `data/devices.yml` 明确把 `pearl` 映射为 `Redmi Note 12T Pro China`；
- `data/latest.yml` 同时给出上述 Stable Fastboot URL、版本、日期、大小和 MD5；
- 下载使用断点续传，只有完整文件 MD5 等于 tracker 值后才从 `.partial` 原子改成最终文件名；
- SHA-256 是下载完成后本地独立计算，记录于 `manifests/official-pearl-baseline.sha256`；
- TGZ 本体为大文件，不进入 Git。

tracker 是社区维护的官方 OTA/Fastboot URL 索引，不是 Xiaomi 的签名授权本身。因此 MD5/HTTPS/tracker Git commit 只建立下载 provenance；还必须继续验证 TGZ 路径安全、内置刷机脚本、AVB 链、镜像哈希与设备身份。

## 使用边界

1. 不直接运行包内 `flash_all*.bat/.sh`；
2. 不刷 `efuse`、`preloader1`、`preloader2` 或任何 raw preloader；
3. 先只读列出/审计 archive，拒绝绝对路径、`..`、危险 symlink/hardlink；
4. 提取后用固定 AOSP `avbtool` 验证 stock `boot`、`vbmeta*` 与 logical partitions；
5. 把官方 stock boot 和完整 fastboot 包作为恢复锚；
6. Nexus/Hermes payload 继续只部署到 `/data`，不改 AVB 保护的 logical partition；
7. 在真机导出当前槽镜像并完成比对前仍然 **NO FLASH**。

## 下一步门禁

- 安全列出并提取 TGZ；
- 记录包内设备/版本 metadata 与每个关键 image SHA-256；
- 审计官方 flash scripts，生成项目自己的保守恢复流程而不是复用脚本；
- 验证官方 AVB chain 和 stock boot；
- 比较第三方输入包与官方基线，明确哪些文件可以完全丢弃；
- 基于官方基线重新完成 recovery/rollback 设计。
