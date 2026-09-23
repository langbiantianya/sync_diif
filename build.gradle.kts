// 根工程只放三个模块**共用**的编译 / 测试约定，不产出任何 artifact。
//
// Kotlin 插件版本必须在这里声明一次并 `apply false`：三个子模块各自写
// `kotlin("jvm") version "2.4.20"` 会让插件被重复加载（Gradle 会警告
// "The Kotlin Gradle plugin was loaded multiple times in different subprojects"，
// 并且各子项目的扩展实例互不相同）。子项目只 `apply(plugin = ...)`，不带版本。

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.20" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }

    // 编译 / 运行统一 JDK 17（Gradle 9 自身也要求 ≥17）；依赖与任务配置仍留在各模块，
    // 只有"每个模块都必须一模一样"的部分放这里。
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
