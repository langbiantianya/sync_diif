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
    implementation("org.duckdb:duckdb_jdbc:1.5.5.1")
    // Impala JDBC 依赖需手动安装到本地或内部 Maven 仓库，版本保持不变
    implementation("com.cloudera.impala.jdbc:ImpalaJDBC41:2.6.4")
    // H2
    implementation("com.h2database:h2:2.2.224")
    // MySQL
    implementation("com.mysql:mysql-connector-j:9.2.0")
    // SQL Server
    implementation("com.microsoft.sqlserver:mssql-jdbc:11.2.1.jre17")
    // PostgreSQL
    implementation("org.postgresql:postgresql:42.7.5")
    // ClickHouse
    implementation("com.clickhouse:clickhouse-jdbc:0.9.2")
    // MaxCompute
    implementation("com.aliyun.odps:odps-jdbc:3.3.0")

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
