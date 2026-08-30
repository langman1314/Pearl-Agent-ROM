# Nexus release signing and provenance

## Release identity

| 字段 | 值 |
|---|---|
| package | `com.niki914.nexus.agentic` |
| versionCode | `7` |
| versionName | `1.0.1-pearl.1` |
| minSdk | `26` |
| targetSdk | `34` |
| compileSdk | `37` |
| Nexus Git tree | `a8db77090faa6f4b11d6aa6f1afc12aaf658f593` |
| APK 文件 | `nexus-1.0.1-pearl.1-release.apk` |
| APK 字节数 | `4,877,945` |
| APK SHA-256 | `9be721babf11992ac6c087deb2348ea9a1b6620896a84b0130a9d16ffed09b30` |

APK 位于 gitignored `artifacts/nexus/`，不进入源码仓库。`aapt dump badging` 与 `apksigner verify --verbose --print-certs` 的完整输出随本地 audit artifact 保存。

## Signer identity

- key algorithm：RSA
- key size：4096 bits
- certificate DN：`CN=Pearl Nexus ROM, OU=Pearl Agent, O=langman1314, L=Local, ST=Local, C=CN`
- certificate SHA-256：`01e17b4c40f9b87973dc0c4bd05b7e36bcf2585eac64a5558e8973ed06f482d4`
- public-key SHA-256：`1cf95f88f933a5556283f2a124aaead4fa87080bdc40ce5ecdcfdd4a636656b8`
- signers：1
- APK Signature Scheme v2：通过
- v1/v3/v3.1/v3.2/v4：未启用

`minSdk 26` 支持 v2。后续更新必须继续使用同一 signer；丢失 keystore 将无法作为同 package 的正常升级安装。

## 私钥边界

- 主 keystore：gitignored `secrets/nexus-release.p12`；
- Gradle 密码/alias 配置：gitignored `secrets/nexus-release.properties`；
- Windows ACL 仅允许当前用户、SYSTEM、Administrators；
- 因 Java/Android build tools 对中文路径兼容不完整，构建时把**同一个** keystore 暂存到 ACL 受限的 `D:\PearlAgentBuild\nexus-signing\` ASCII 路径；不重新生成 key；
- 密码不得写进 Gradle 文件、命令输出、Git、APK 或 ROM；只通过当前进程的 `ORG_GRADLE_PROJECT_*` 环境变量注入；
- 必须把 PKCS12 和 properties 一起离线加密备份到不同物理介质；恢复演练时只比较证书指纹，不输出密码。

## 固定工具链

- Gradle `9.3.1`；distribution SHA-256：`b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`；
- wrapper 已通过 `distributionSha256Sum` 强制校验；
- Eclipse Temurin JDK `17.0.20.1+1`；
- Android SDK build-tools `37.0.0`；
- Android SDK 路径通过 ASCII build tree 的 `local.properties` 与 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 设置；
- release source 复制到 `D:\PearlAgentBuild\nexus-release`，避免 `aapt`、`apksigner` 与 Java 对中文路径的失败。

## 构建与验证流程

1. 要求 Git 工作树干净并记录 `HEAD:nexus` tree id；
2. 把 `nexus/` 镜像复制到隔离 ASCII build tree，排除 `.gradle`、`build`、`.idea`、`local.properties`；
3. 设置固定 JDK、SDK、Gradle distribution 与独立 `GRADLE_USER_HOME`；
4. 从受保护 properties 逐项设置当前进程的 Gradle signing properties，并覆盖为受 ACL 保护的 ASCII keystore path；
5. 执行 `clean testDebugUnitTest assembleRelease`；测试或任一 task 失败即不发布；
6. 复制 APK 到 gitignored artifact 后计算 SHA-256；
7. 通过 ASCII hash-identical copy 执行 `aapt dump badging`；package、versionCode、versionName 必须与本页一致；
8. 执行 `apksigner verify --verbose --print-certs`；要求 v2=true、signers=1、RSA-4096、证书 SHA-256 精确匹配；
9. 只有通过上述门禁的 hash 才能进入 ROM release manifest。

## 可复现性状态

**签名 APK 目前不声明 byte-for-byte reproducible。**

在加入 507012002 config 之前做过一次强制 `--rerun-tasks assembleRelease`：两次产物有相同大小、相同 672 个 ZIP entry 名称、CRC、uncompressed/compressed size 与 timestamp，但 APK 总 SHA-256 从 `58f35e…0185` 变为 `1e09fd…cb2e`。差异因此局限于 ZIP entries 以外的 APK signing block；两份 payload 等价，但严格 signed-byte reproducibility gate 未通过。当前含官方 XiaoAi `507009011` exact config 的 `9be721…9b30` release 继续按单一批准 hash 管理，不借源码变化重置这一未通过结论。

安全策略是不修改 signer 实现、不注入伪随机源、不降低验证算法来追求相同字节。发布时以“固定源码 tree + 固定工具链 + 固定 signer certificate + 单个批准 APK hash”的 provenance 模式管理；若未来要声明严格可复现，必须先解释并消除 signing-block 差异，再独立构建两次得到相同总 SHA-256。
