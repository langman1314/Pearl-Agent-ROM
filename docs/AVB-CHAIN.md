# Pearl AVB chain 离线审计

审计日期：2026-09-16。

## 输入与工具

- 上游输入包：`pearl_Note12TPro_OS3.0.310.0.WAACNXM_16.0.zip`
- 上游输入包 SHA-256：`b684924f00fe5663f6438ddc482efc6a556f6af3475f94613b95f16dc0cba3c6`
- AVB 工具：AOSP `platform/external/avb`
- 工具 commit：`c5066a96caa7bf4150c0a8cc8cc14ab81733fdc7`
- 所有 `info_image`、`verify_image` 和 flags 还原实验均只读原镜像；实验副本只放在被 Git 忽略的 `artifacts/avb/`。

## 镜像身份

| 镜像 | 字节数 | SHA-256 | RSA vbmeta 结构 |
|---|---:|---|---|
| `vbmeta.img` | 8,388,608 | `e6d3cc2daf15266bc324a5986daa475ec119c1e7672e0a187439d8b5ce644e05` | 失败：flags 被改为 3 后未重签 |
| `vbmeta_system.img` | 8,388,608 | `c739d1a67ebfd24f45dd916963de768f5378afb355350348aafd879e0f442b32` | 成功 |
| `vbmeta_vendor.img` | 8,388,608 | `5bafec47682ff49cd2cb3cdcb0daf811820822c23986429ffb2102d0e25b3735` | 成功 |
| stock `boot.img` | 67,108,864 | `8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82` | footer、RSA、boot hash 全部成功 |
| Magisk patched boot | 67,108,864 | `2024bdb03ea95556774471f4cca348bd499de753aa4a7d859dfb8d5485bc8e73` | 失败：flags 被改为 3 后未重签；boot hash 也不匹配 |

三份 vbmeta 和 stock/patched boot 内嵌公钥的 SHA-1 都是：

`b2a02f1e56e366d727a1a8e089762fe0b91bbc84`

这只能证明它们声明使用相同链公钥，不等于 flags=3 后的顶层 vbmeta 或 patched boot 仍有有效签名。

## 顶层 chain

顶层 `vbmeta.img` 声明三个 chain partition：

| Partition | Rollback index location | Flags | 公钥 SHA-1 |
|---|---:|---:|---|
| `boot` | 3 | 0 | `b2a02f1e56e366d727a1a8e089762fe0b91bbc84` |
| `vbmeta_system` | 2 | 0 | `b2a02f1e56e366d727a1a8e089762fe0b91bbc84` |
| `vbmeta_vendor` | 4 | 0 | `b2a02f1e56e366d727a1a8e089762fe0b91bbc84` |

顶层还直接描述：

- hash：`dtbo`、`vendor_boot`；
- hashtree：`mi_ext`、`odm`、`odm_dlkm`、`vendor_dlkm`；
- `vbmeta_system` hashtree：`product`、`system`、`system_ext`；
- `vbmeta_vendor` hashtree：`vendor`。

`vbmeta_system` 和 `vbmeta_vendor` 的 RSA 签名已验证成功，但这不代表它们描述的 logical partition 数据自洽。现已用 `scripts/extract-super-extents.py` 完整读取 `super.zst` 解压后的 `9,126,805,504` 字节、读到外层 ZIP member EOF 触发 CRC 校验，并原子提取全部八个已填充 `_a` partition；SHA-256 见 `manifests/logical-partitions.sha256`。

### 子 vbmeta 与 super 的不可满足矛盾

| Partition | super extent 总字节 | descriptor data | tree | FEC | descriptor 最低总字节 | 结论 |
|---|---:|---:|---:|---:|---:|---|
| `product` | 3,457,253,376 | 4,527,869,952 | 35,659,776 | 36,077,568 | 4,599,607,296 | 比 extent 大 1,142,353,920，物理上不可能 |
| `system` | 863,363,072 | 819,441,664 | 6,459,392 | 6,529,024 | 832,430,080 | 尺寸可容纳，仍需单独重算 root digest |
| `system_ext` | 869,822,464 | 897,777,664 | 7,077,888 | 7,159,808 | 912,015,360 | 比 extent 大 42,192,896，物理上不可能 |
| `vendor` | 1,965,887,488 | 1,972,944,896 | 15,544,320 | 15,720,448 | 2,004,209,664 | 比 extent 大 38,322,176，物理上不可能 |

因此至少 `product`、`system_ext`、`vendor` 的签名 hashtree descriptor 不可能对应当前 `super.zst`。这不是补一个缺失文件或把 top-level flags 改回 0 能修复的问题；它证明该第三方包的签名 metadata 与 logical payload 来自不一致的 donor/layout。当前包不能建立可验证 AVB 链，仍然不是官方/生产基线；用户已明确批准的实验例外只允许 `docs/EXPERIMENTAL-310-CARRIER.md` 中固定 hash、固定 allowlist 的 carrier 路线。

## flags 篡改证明

顶层 `vbmeta.img` 的 header flags 是 `3`（hashtree disabled + verification disabled），其 authentication block 不再匹配。只在实验副本把 flags 四字节还原为 `0` 后，AOSP avbtool 立即报告：

`Successfully verified SHA256_RSA2048 vbmeta struct`

随后仅因未提供 chain partition 数据而停止。这证明上游包的顶层 vbmeta 是在有效签名产物上改 flags、但没有重新签名。

Magisk patched boot 同样把内嵌 vbmeta flags 从 stock 的 `0` 改成 `3`。在实验副本还原 flags 后：

1. footer 与 RSA vbmeta 结构恢复成功；
2. boot descriptor SHA-256 明确不匹配。

所以 patched boot 依赖禁用 AVB verification 的启动路径，不是重新签名且 descriptor 自洽的镜像。历史 patched boot 仍然永久弃用；当前 fresh Magisk patch 也只允许在 310 carrier 的 stock-boot 验收后通过独立 `boot_a` 脚本写入，不能把它描述为 AVB 自洽或生产验证镜像。

## ROM 身份不一致

压缩包文件名和 `flashl.bat` 声称：

- `OS3.0.310.0.WAACNXM`；
- Android 16；
- 构建者 `mytiantian_是天天吖`，日期 2026-05-16。

但签名 metadata 显示混合来源：

- system/product/mi_ext：Android 15，`OS3.0.3.0.VLHCNXM`，设备/产品含 `missi`；
- vendor/vendor_boot/boot：Android 12，`OS3.0.3.0.VLHCNXM`，设备含 `mihal`；
- security patch 分别为 2026-04-01 和 2026-02-01。

因此该 ZIP 是 pearl 的第三方混合移植包，不是可按文件名当作 Xiaomi 官方 Android 16 fastboot ROM 的基线。原 ZIP 仍作为不可变上游输入保留，但必须单独准备官方 pearl 救砖包。

## 原刷机脚本危险项

上游 `flashl.bat` 会执行：

- `fastboot set_active a`；
- 刷写大量 slot A 分区；
- **把 `preloader_raw.img` 同时刷入 `preloader1` 和 `preloader2`**；
- 可选擦除 `metadata` 与 `userdata`。

本项目禁止运行或复用该脚本。任何新 installer 必须满足：

1. 永不刷 `efuse`、`preloader1`、`preloader2`；
2. inactive slot 保留 stock boot；
3. data-only Agent 阶段不刷 `vbmeta*`；310 carrier 阶段只按固定 hash 写入其三份 slot-A vbmeta，并明确这是 verification-disabled experimental 状态；
4. 实机读取当前槽 `boot`/`vbmeta*` 并核对哈希后再生成设备专用方案；
5. stock boot 回滚脚本和独立官方救砖包必须先准备完成。

## 剩余门禁

- 当前 `super.zst` 已完整提取并证明无法满足签名 hashtree descriptor；不得把关闭 verification 当作修复 AVB 矛盾；
- 官方 pearl fastboot/recovery 基线与官方哈希已固定；310 只作为用户明确批准的 unverified carrier，不是 AVB-valid ROM；
- 真机 `fetch` 与 temporary `boot` 均不受支持，当前槽镜像无法导出；用户仅为 unverified experiment 明确 waiver 当前分区备份；
- 记录 bootloader unlock/critical unlock 状态及当前 AVB/verity 状态；
- fresh Magisk 30.7 patch已完成双次独立生成与结构审计；仍需真机 stock carrier 启动、root 和 stock boot-A 回滚验证；
- 仅 active slot 的真机 dry-run、carrier 验收、stock boot-A 回滚、Vector/Nexus/Hermes 分阶段验收仍未完成。

在物理 dry-run 和分阶段验收完成前，AVB 审计状态仍是 **NO FLASH / EXECUTION NOT AUTHORIZED**。
