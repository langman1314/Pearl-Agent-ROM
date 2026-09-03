# Boot image validation and patch gate

## Approved stock input

The sole boot patch input is the exact official fastboot baseline file:

| Field | Value |
|---|---|
| package | `OS3.0.3.0.VLHCNXM` official pearl fastboot |
| archive SHA-256 | `99e166422be4bd17237df9b70030b5f0ff1871d7b7bd858cbb62b683f070ea64` |
| member | `images/boot.img` |
| bytes | `67,108,864` |
| boot SHA-256 | `8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82` |
| header | `ANDROID!`, boot header v4 |
| AVB | footer, SHA256_RSA2048 vbmeta and 21,045,248-byte boot hash all verified |

The complete official top-level/chained AVB graph, including all logical hashtrees, also passes. See `docs/OFFICIAL-PEARL-BASELINE.md`.

## Historical patched image is rejected

A historical Magisk 30.7 experiment produced a 67,108,864-byte image with SHA-256:

`2024bdb03ea95556774471f4cca348bd499de753aa4a7d859dfb8d5485bc8e73`

Its extracted ramdisk lineage and embedded `magisk`/`magiskinit` matched the stock input and official Magisk 30.7 binaries. However:

- it was created before the official recovery baseline and process gates were established;
- embedded vbmeta flags changed from `0` to `3` without a new trusted signature;
- restoring flags to `0` makes RSA verification return but the boot hash descriptor fails, as expected for changed ramdisk bytes;
- preserving an `AVBf` footer is not proof of AVB consistency;
- no target-device slot/rollback acceptance was performed.

This image is retained only as audit evidence. It is not a release artifact and must never be flashed. Final work must regenerate a patch from a fresh hash-checked copy of the official boot through the controlled procedure below.

## Controlled patch procedure

The final patched boot was generated only after the available physical device identity/fastboot report passed, using this required procedure:

1. copy the exact official boot by hash into a clean staging directory;
2. use the approved official Magisk 30.7 APK/binaries whose source, release asset and signatures/hashes are recorded;
3. patch on the target device or an equivalently validated ARM64 Magisk environment without supplying a foreign vbmeta image;
4. pull the result without renaming it over stock, record size and SHA-256, and preserve the unmodified stock image beside it;
5. unpack stock and patched images with the same pinned `magiskboot` and record component hashes/diff scope;
6. prove kernel, bootconfig/header geometry and partition size remain compatible;
7. inspect embedded vbmeta/footer behavior explicitly and never describe flags=3 as signed AVB;
8. test only through the device-specific slot/rollback plan approved from actual fastboot variables.

## Fresh controlled patch result

The procedure has now been completed without flashing. The Magisk app already installed on the phone was pulled and proved byte-identical to official Magisk 30.7 APK SHA-256 `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5`. Its ARM64 patch components processed the exact accepted stock boot in isolated `/data/local/tmp` storage with `KEEPVERITY=true`, `KEEPFORCEENCRYPT=true`, `PATCHVBMETAFLAG=false`, `RECOVERYMODE=false`; all phone staging files were then removed.

Two independent clean runs produced exactly the same 67,108,864-byte image:

`f3cb3ca2353315bc96694cd17d400b71108864f28ff4ef8a7260c50f69572005`

Pinned `magiskboot` comparison found the stock and patched kernel identical at SHA-256 `42c827b5...b801`; only ramdisk changed (`7d935a76...5247` to `1a70c97c...5068`) with the expected `.backup` and `overlay.d` additions. Boot header v4, kernel size and partition size remain compatible. The embedded vbmeta remains flags `0` and its RSA structure verifies, while its old boot hash descriptor correctly fails after the ramdisk change; this image is not represented as AVB-signed stock.

The pearl bootloader accepted transfer but rejected non-flashing `fastboot boot` as `unknown command`, so no temporary execution occurred. The image is available only in the host staging manifest and may be written to `boot_a` solely after the experimental 310 stock-boot carrier passes its clean Phase-B acceptance. Exact stock restoration is separately gated by `scripts/switch-experimental-310-boot.sh --mode stock`.

## Device-derived prerequisites

Before any real `fastboot flash` execution, the generated device-specific plan must collect or reconcile read-only evidence for:

- `ro.product.device`, vendor/system device, fingerprint and build version;
- bootloader unlocked/secure state;
- `current-slot`, slot count and `has-slot:boot`/`has-slot:vbmeta` where exposed;
- actual `/dev/block/by-name` boot/vbmeta mapping;
- hashes and external backups of every available stock boot/vbmeta slot for production acceptance; the bootloader exposed neither `fetch` nor temporary `boot`, and the user explicitly waived this item only for the unverified 310 experiment;
- anti-rollback variables where exposed;
- working recovery/fastboot access independent of Android userspace.

Do not assume the slot semantics from Xiaomi's flash scripts: those scripts erase or flash `boot_ab`, metadata/userdata and preloader-related targets and are prohibited.

## Rollback gate

The first boot experiment requires a separately reviewed rollback procedure that:

- references stock image files only by approved SHA-256;
- validates device product and partition/slot variables before mutation;
- never flashes efuse, preloader, super or vbmeta as a side effect;
- stops on any missing/ambiguous variable;
- records every proposed command before execution;
- can restore stock boot from fastboot/recovery if Android does not start.

Current status remains **NO FLASH / EXECUTION NOT YET AUTHORIZED**. The fresh patch exists and is host-approved for the separately gated experimental carrier sequence, but has not been written to the phone.
