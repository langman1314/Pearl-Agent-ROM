# Boot 镜像离线验证

验证日期：2026-09-16。

| 镜像 | 主机路径 | 字节数 | SHA-256 | Boot header | AVB footer |
|---|---|---:|---|---|---|
| 原始 boot | `D:\生活问答\rom-extract\images\boot.img` | `67108864` | `8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82` | `ANDROID!` / v4 | `AVBf` |
| Magisk patched boot | `D:\生活问答\magisk-patch\magisk_patched_OS3.0.310.0.img` | `67108864` | `2024bdb03ea95556774471f4cca348bd499de753aa4a7d859dfb8d5485bc8e73` | `ANDROID!` / v4 | `AVBf` |

## 结论

- 修补镜像和原始镜像均为 64 MiB，分区容量匹配。
- 二者均为 Android boot header v4，且末尾保留 AVB footer。
- SHA-256 不同，证明输出不是原图误复制。
- 先前字符串/CPIO 检查已发现 `magisk`、`overlay.d`、`.backup` 和 `KEEPVERITY` 等修补证据。

## 尚未完成的门禁

这份记录**不是刷入许可**。实机刷入前仍需：

1. 记录生成镜像所用 Magisk 的版本与来源；
2. 用同版本 `magiskboot` 解包并验证 ramdisk；
3. 解析设备 AVB chain，确认无需盲改 `vbmeta`；
4. 从当前槽导出原厂 boot 并核对 SHA-256；
5. 仅刷当前槽，另一槽保留 stock boot；
6. 准备可独立执行的 stock boot 回滚脚本。

镜像文件由 `.gitignore` 排除，只提交哈希与验证记录。