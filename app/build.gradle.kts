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
    // 1. 显式设置重复策略为 INCLUDE，让下面 mergeServiceFiles() / append(...) 真正生效
    //    （EXCLUDE 会按路径丢弃所有同名条目；INCLUDE 保留所有，让 append / merge 自己处理）。
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // 2. 合并 ServiceLoader 文件（必须配合 INCLUDE 策略）
    mergeServiceFiles()
    // 3. 合并同名法务元数据（多个依赖各带一份 META-INF/LICENSE*）：
    //    内容追加而非丢弃，既不丢声明，也不留重复条目。
    //    注意 Gradle 的路径前缀匹配按**路径段**比较（RelativePath.startsWith），
    //    `append("META-INF/LICENSE")` 命中不了 `META-INF/LICENSE.txt`，所以逐个写全。
    append("META-INF/LICENSE")
    append("META-INF/LICENSE.txt")
    append("META-INF/NOTICE")
    append("META-INF/NOTICE.txt")
    append("META-INF/ASL2.0")
    append("META-INF/DEPENDENCIES")
    // 4. 夹在依赖 jar 里的 maven 坐标（hive-jdbc shade 了 commons-codec / commons-logging
    //    / slf4j、依赖自带的 git.properties）对 fat jar 没有意义，
    //    重复的几份版本还不一致——追加会拼出坏 XML / 坏 properties，直接排除。
    exclude("META-INF/maven/**")
    exclude("git.properties")
    // 5. 关闭严格失败：transitive 中多个 jar 会把"内容一致"的同名 class 多次塞进来
    //    ——典型是 kotlin-stdlib（kotlin-reflect / kotlinx-* / 各 JDBC 都间接拉一份，
    //    内容字节相同）和 net.jpountz.lz4（clickhouse-jdbc 与 hive-jdbc 各一份 lz4-java，
    //    版本一致）。运行时不存在冲突，关 fail 后 shadow 默认按"先到先得"留首份。
    //    若后续依赖冲突真的开始影响行为，再具体保留这一段做精确过滤。
    failOnDuplicateEntries = false
}