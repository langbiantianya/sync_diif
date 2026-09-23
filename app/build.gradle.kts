import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    // CheckRegistry.discover() 的反射 (KClass.objectInstance) 需要 kotlin-reflect；
    // 它在 checks 模块，checks 的 implementation 不外传，所以这里要自己声明一份。
    implementation(kotlin("reflect"))
    // CLI
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    // 协程：Main.kt 用 runBlocking 把 suspend 的 Check.runWith 桥到同步 main。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation(project(":checks"))
    implementation(project(":core"))

    testImplementation(kotlin("test"))
    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
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
    // Hive 客户端闭包带进 hadoop 之后，条目数会超过 ZIP 的 65535 上限
    // （org.apache.tools.zip.Zip64RequiredException）；Kotlin 里这个属性名是 isZip64
    // （Gradle Zip 的 isZip64()/setZip64() 访问器），不是 zip64。
    isZip64 = true
    // 6. 只留目标平台（linux/amd64）的原生库。DuckDB JDBC 自带 4 个平台的
    //    libduckdb_java.so（osx_universal 103 MB + linux_arm64 51 MB + windows_amd64 34 MB），
    //    zstd-jni / snappy 又各带十几到二十几个平台的 .so/.dll——加起来让 jar 解压后接近
    //    480 MB，而调度机只有 linux/amd64 那一份会被加载。排除后 jar 体积降一个量级。
    //    要在别的架构 / 系统上跑这个 jar，就把对应的一行从下面删掉（例如 macOS 开发机
    //    需要 libduckdb_java.so_osx_universal 与 darwin/**）。
    exclude(
        "libduckdb_java.so_osx_universal",
        "libduckdb_java.so_linux_arm64",
        "libduckdb_java.so_windows_amd64",
        "aix/**",
        "freebsd/**",
        "darwin/**",
        "openbsd/**",
        "solaris/**",
        "win/**",
        "linux/aarch64/**",
        "linux/arm/**",
        "linux/i386/**",
        "linux/mips64/**",
        "linux/ppc64/**",
        "linux/ppc64le/**",
        "linux/riscv64/**",
        "linux/s390x/**",
        "linux/loongarch64/**",
    )
}
