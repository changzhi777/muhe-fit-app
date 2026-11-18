// 沐禾健身 APP · Gradle 工程入口（单模块 :app，后续按需加模块）
// 设计依据：ck/docs/app-design/android-app.md（V0.2）· 裁定：ck/docs/app-design/decisions.md

pluginManagement {
    repositories {
        // 国内镜像优先（google/central/gradlePluginPortal 的阿里云镜像），官方仓兜底
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "muhe-fit-app"
include(":app")
