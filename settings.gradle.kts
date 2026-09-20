pluginManagement {
    repositories {
        maven("https://jfrog-internal.sensorsdata.cn/artifactory/maven-public/")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

gradle.allprojects {
    repositories {
        maven("https://jfrog-internal.sensorsdata.cn/artifactory/maven-public/")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}


plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "sync_diff"
include("checks")
include("core")
include("app")