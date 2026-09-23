pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

// 子项目的 repositories / toolchain / test 约定统一在根 build.gradle.kts 的 subprojects 块里配置。

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "sync_diff"
include("checks")
include("core")
include("app")
