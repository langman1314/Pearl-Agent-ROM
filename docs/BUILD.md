# Nexus 便携构建

在 Windows 的 Git Bash 中运行：

```bash
./scripts/bootstrap-android.sh
```

脚本把 Eclipse Temurin JDK 17 和 Android SDK 放入仓库根目录 `.tools/`，不修改系统级 Java/Android Studio。固定 Android command-line tools `13114758`，通过 preview channel 安装 `platforms;android-37.0`、`build-tools;37.0.0` 和 platform-tools。上游 Compose 1.12 alpha / Material3 1.5 alpha 的 AAR metadata 要求 compileSdk 37；应用的 targetSdk 仍为 34，然后执行：

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

`.tools/`、Gradle 输出和 APK 均由 `.gitignore` 排除。最终 APK 的 SHA-256 单独写入 `manifests/`。

便携 JDK 的 Gradle 进程使用 `Windows-ROOT` trust store，以复用 Windows 已验证的企业/代理根证书；不会关闭 TLS 或采用 trust-all。

由于 Windows AGP 拒绝非 ASCII 项目路径，脚本使用 `robocopy /MIR` 将 `nexus/` 同步到同盘 `D:\PearlAgentBuild\nexus`（可通过 `PEARL_BUILD_ROOT` 改写）后构建，并把 APK 复制回仓库的 `artifacts/`。不使用 `android.overridePathCheck` 绕过检查。
