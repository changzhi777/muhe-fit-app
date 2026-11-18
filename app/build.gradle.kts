import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// versionName 单一事实源 = android-app/VERSION（裁定 #27）；文件缺失/为空时兜底 0.0.0 以便 fail-visible
val versionFile = rootProject.file("VERSION")
val muheVersionName = versionFile.takeIf { it.exists() }
    ?.readText()?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "0.0.0"

// 🆕 P9 T2 release 签名：keystore.properties（本地不入仓，.gitignore）提供四键
// storeFile/storePassword/keyAlias/keyPassword；文件缺失时 release 退回 debug 签名
// （本地出包不受阻），CI/正式发布前按 README 生成 keystore。
val keystorePropsFile = rootProject.file("keystore.properties")
val muheKeystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.muhe.fit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.muhe.fit.app"   // 与存量 Capacitor 壳 com.muhe.fit 并存对照（裁定 #3 / D1）
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = muheVersionName
    }

    signingConfigs {
        if (muheKeystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(muheKeystoreProps.getProperty("storeFile"))
                storePassword = muheKeystoreProps.getProperty("storePassword")
                keyAlias = muheKeystoreProps.getProperty("keyAlias")
                keyPassword = muheKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // keystore.properties 在场才挂 release 签名，否则退 debug 签名（fail-soft）
            if (muheKeystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// Room schema 导出（MuheDatabase exportSchema=true；schemas/ 快照入 git 作迁移对照）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // ── Compose（BOM 统一版本）──
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)   // HorizontalPager（打卡屏卡轨；BOM 统一版本，白名单 #12 内）
    implementation(libs.androidx.compose.navigation)

    // ── AndroidX 基座 ──
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)   // Credential Manager 宿主要求 FragmentActivity（MainActivity 基类）

    // ── Credentials（device-link 扫码登录 passkey 注册；白名单外过审依据见 libs.versions.toml 注记）──
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)

    // ── Room（KSP 编译器）──
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ── Hilt（KSP 编译器）──
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── 网络（裁定 #2）──
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // ── 图片 / QR（白名单 #12；QR 依赖进树、M2 打卡屏开始消费）──
    implementation(libs.coil.compose)
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)
    // 二版预留：ML Kit pose-detection（裁定 #11/#12，首版不进依赖树）——见 libs.versions.toml 注释

    // ── CameraX（M2 打卡扫码；白名单外过审依据见 libs.versions.toml androidxCamera 注记）──
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ── 测试 ──
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
