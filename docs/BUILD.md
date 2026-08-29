# Nexus 便携构建

在 Windows 的 Git Bash 中运行：

```bash
./scripts/bootstrap-android.sh
```

脚本把 Eclipse Temurin JDK 17 和 Android SDK 放入仓库根目录 `.tools/`，不修改系统级 Java/Android Studio。固定 Android command-line tools `13114758`，安装 `platforms;android-36`、`build-tools;35.0.0` 和 platform-tools。Android 16 对应 API 36；上游的 compileSdk 37 在稳定 SDK 仓库中不可用，然后执行：

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

`.tools/`、Gradle 输出和 APK 均由 `.gitignore` 排除。最终 APK 的 SHA-256 单独写入 `manifests/`。
