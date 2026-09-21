plugins {
    kotlin("jvm") version "2.4.20"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    // CheckRegistry.discover() 的反射 (KClass.objectInstance) 需要 kotlin-reflect
    implementation(kotlin("reflect"))

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // JDBC 驱动
    runtimeOnly("org.duckdb:duckdb_jdbc:1.5.5.1")
    // Hive-JDBC：Impala 的 HiveServer2 兼容入口。驱动类 org.apache.hive.jdbc.HiveDriver，
    // URL 形态 jdbc:hive2://host:21050/default（不是 jdbc:impala://）。如生产侧仍用
    // 旧的 jdbc:impala:// URL，需要同步把 ImpalaConfig.jdbcUrl 切到 hive2 scheme。
    // Source: https://mvnrepository.com/artifact/org.apache.hive/hive-jdbc
    runtimeOnly("org.apache.hive:hive-jdbc:4.2.1")
    // H2
    // Source: https://mvnrepository.com/artifact/com.h2database/h2
    runtimeOnly("com.h2database:h2:2.5.250")
    // MySQL
    // Source: https://mvnrepository.com/artifact/com.mysql/mysql-connector-j
    runtimeOnly("com.mysql:mysql-connector-j:26.7.0")
    // SQL Server
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:13.6.0.jre11")
    // PostgreSQL
    // Source: https://mvnrepository.com/artifact/org.postgresql/postgresql
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    // ClickHouse
    // Source: https://mvnrepository.com/artifact/com.clickhouse/clickhouse-jdbc
    runtimeOnly("com.clickhouse:clickhouse-jdbc:0.10.0")
    // MaxCompute
    // Source: https://mvnrepository.com/artifact/com.aliyun.odps/odps-jdbc
    runtimeOnly("com.aliyun.odps:odps-jdbc:3.10.13")


    testImplementation(kotlin("test"))
    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")

    // Excel 报告由 XlsxWriter 手写最小 OOXML，运行时不需要 Excel 库；
    // 这里只在测试期用 POI 当「独立读回」的校验器，确认写出的工作簿能被真实
    // OOXML 实现解析（不进 fat jar）。
    testImplementation("org.apache.poi:poi-ooxml:5.4.1")
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
