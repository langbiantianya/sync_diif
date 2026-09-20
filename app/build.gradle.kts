import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    // CheckRegistry.discover() 的反射 (KClass.objectInstance) 需要 kotlin-reflect
    implementation(kotlin("reflect"))
    // CLI
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(project(":checks"))
    implementation(project(":core"))

    testImplementation(kotlin("test"))
    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
}

kotlin {
    jvmToolchain(17)
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("sync_diff")
    archiveClassifier.set("all")
    archiveVersion.set("")
    // CLI 启动入口：Main.kt 顶层 main() 编译成 cli.MainKt
    manifest {
        attributes("Main-Class" to "com.kxxnzstdsw.sync_diff.MainKt")
    }
    // 1. 显式设置重复策略，确保 mergeServiceFiles() 生效
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // 2. 合并 ServiceLoader 文件（必须配合 INCLUDE 策略）
    mergeServiceFiles()
    // 3. 合并同名法务元数据（jna / ImpalaJDBC41 各带一份 META-INF/LICENSE）：
    //    内容追加而非丢弃，既不丢声明，也不留重复条目。
    append("META-INF/LICENSE")
    append("META-INF/NOTICE")
    append("META-INF/DEPENDENCIES")
    // 4. 开启严格模式：JAR 中出现重复条目时构建失败
    failOnDuplicateEntries = true
}