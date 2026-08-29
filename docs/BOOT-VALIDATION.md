# Boot 镜像离线验证

验证日期：2026-09-16。

| 镜像 | 主机路径 | 字节数 | SHA-256 | Boot header | AVB footer |
|---|---|---:|---|---|---|
| 原始 boot | `D:\生活问答\rom-extract\images\boot.img` | `67108864` | `8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82` | `ANDROID!` / v4 | `AVBf` |
| Magisk patched boot | `D:\生活问答\magisk-patch\magisk_patched_OS3.0.310.0.img` | `67108864` | `2024bdb03ea95556774471f4cca348bd499de753aa4a7d859dfb8d5485bc8e73` | `ANDROID!` / v4 | `AVBf` |

## 结论

- 修补镜像和原始镜像均为 64 MiB，分区容量匹配。
- 二者均为 Android boot header v4，且末尾保留 AVB footer。
- stock boot 的 footer、SHA256_RSA2048 vbmeta struct 和 boot hash descriptor 已由 AOSP avbtool 验证成功。
- patched boot 来自该 stock boot：ramdisk 记录的 SHA-1 精确匹配输入，内嵌 `magisk` 与 `magiskinit` 逐字节匹配官方 Magisk 30.7 Release。
- patched boot 把内嵌 vbmeta flags 从 0 改为 3，但没有重新签名；把实验副本 flags 还原为 0 后 RSA 恢复、boot hash descriptor 随即明确失败。
- 因此“保留 AVB footer”不等于 AVB 自洽；当前 patched boot 仍是 **NO FLASH**。

完整 chain、flags 证明与 ROM 混合来源见 `docs/AVB-CHAIN.md`；Magisk/Vector 来源见 `docs/PLATFORM-PROVENANCE.md`。

## 尚未完成的门禁

这份记录**不是刷入许可**。实机刷入前仍需：

1. 使用官方 Magisk 30.7 同版本 `magiskboot` 再执行一次工具级 unpack/repack 验证；
2. 从 `super.zst` 只读副本提取 logical partitions，完成所有 AVB hashtree 验证；
3. 从真机当前槽导出 boot/vbmeta 链并核对 SHA-256；
4. 仅测试 active slot，另一槽保留 stock boot；
5. 准备可独立执行的 stock boot 回滚脚本与官方 pearl 救砖包；
6. 禁止运行上游会刷写 `preloader1/2` 的 `flashl.bat`。

镜像文件由 `.gitignore` 排除，只提交哈希与验证记录。