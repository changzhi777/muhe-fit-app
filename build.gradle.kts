// 根构建脚本：只声明插件版本（apply false），实际应用在 app/build.gradle.kts
// 版本全部收敛在 gradle/libs.versions.toml（version catalog，依赖白名单纪律见 decisions.md 裁定 #12）

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
