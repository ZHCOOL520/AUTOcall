import org.gradle.kotlin.dsl.mavenCentral
import org.gradle.kotlin.dsl.repositories
import kotlin.text.set

pluginManagement {
    repositories {
        // 给插件也加上国内镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像源，优先访问
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 备用官方源（alpha版本需要从官方获取）
        google()
        mavenCentral()
        // 添加JCenter作为额外备用（某些旧版依赖）
        maven { url = uri("https://jcenter.bintray.com") }
    }
}

rootProject.name = "AUTO call"
include(":app")