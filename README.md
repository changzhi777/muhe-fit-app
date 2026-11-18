# muhe-fit-app · 沐禾健身 Android 原生 APP

Kotlin 2.x + Jetpack Compose + Material 3（全局深色唯一主题）。设计文档与裁定见
[`ck/docs/app-design/android-app.md`](../ck/docs/app-design/android-app.md)（V0.2）与
[`ck/docs/app-design/decisions.md`](../ck/docs/app-design/decisions.md)。

## 构建前置（一次性）

| 前置 | 要求 |
|------|------|
| JDK | 17+（建议 21，与 ck/frontend Capacitor 壳构建口径一致） |
| Android SDK | Platform 35 + Build-Tools（Android Studio Ladybug+ 或命令行 sdkmanager） |
| 本地 Gradle | 8.10+（仅首次生成 wrapper 用） |

**Wrapper jar 需用户侧生成**（仓库只提交了 `gradle/wrapper/gradle-wrapper.properties`，jar 不进库）：

```bash
cd android-app
gradle wrapper --gradle-version 8.10   # 需本地已装 gradle 8.10+；生成 gradlew + gradle-wrapper.jar
```

## 常用命令

```bash
./gradlew :app:test            # JVM 单测（含 golden fixture 对齐，见 docs/golden-testing.md）
./gradlew :app:assembleDebug   # Debug APK
```

## 版本规程（裁定 #27）

- `versionName` 单一事实源 = 本目录 [`VERSION`](./VERSION)（V1.0.1 起步，第 3 位递增），`app/build.gradle.kts` 构建期读取
- `versionCode` 当前手工 `1`；tag 前缀 `app-`（如 `app-v1.0.2`）

## 目录速览

```
android-app/
├── VERSION / CHANGELOG.md / CLAUDE.md     # 版本与文档（versionName 读 VERSION）
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml              # 依赖白名单落地清单（裁定 #12）
├── gradle/wrapper/gradle-wrapper.properties
├── docs/golden-testing.md                 # JS↔Kotlin golden 对齐机制
└── app/
    ├── build.gradle.kts / proguard-rules.pro
    └── src/
        ├── main/                          # Manifest + MuHeApp + MainActivity + ui/theme + navigation/
        └── test/                          # lib/Onerm.kt 移植示范 + golden 对齐测试 + resources/golden/
```
