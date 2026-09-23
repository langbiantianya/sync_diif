plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    // CheckRegistry.discover() 的反射 (KClass.objectInstance) 需要 kotlin-reflect；
    // CheckRegistry 在 checks 模块，所以只有这边需要它。
    implementation(kotlin("reflect"))

    // JDBC 驱动
    runtimeOnly("org.duckdb:duckdb_jdbc:1.5.5.1")
    // Hive-JDBC：Impala 的 HiveServer2 协议入口。驱动类 org.apache.hive.jdbc.HiveDriver，
    // URL 形态 jdbc:hive2://host:21050/default（不是 jdbc:impala://）。如生产侧仍用旧的
    // jdbc:impala:// URL，需同步把 ImpalaConfig.jdbcUrl 切到 hive2 scheme。
    //
    // 必须用 `4.1.0:standalone` 这个坐标，三个条件缺一不可：
    // 1. 版本 ≤ 4.1.0：4.2.x 的 HiveDriver 是 Java 21 字节码（class file 65），JDK 17 加载不了；
    //    更麻烦的是它在合并后的 META-INF/services/java.sql.Driver 里排第二——驱动注册扫描会在
    //    那里中断，导致 H2 / MySQL / MSSQL / PG / ClickHouse / MaxCompute 全部
    //    "No suitable driver found"。4.1.0 是 Java 17 字节码（class file 61），与 toolchain 一致。
    // 2. `standalone` 变体：hive-jdbc 普通 jar 的 POM 只声明 test 依赖（其余按"由 Hive 发行版
    //    提供"处理），单加它会在 HiveDriver.connect 处逐个缺类——libthrift、TCLIService、
    //    HiveSQLException、HiveConf、org.apache.hadoop.shaded.*、org.apache.curator.*…；
    //    手工拼这些坐标会把 hadoop / jetty / curator 整套拖进来，且很容易漏一个（漏的后果是
    //    上线才炸）。standalone 是 Hive 官方为「独立客户端」发布的变体，自带 shade 过的全部依赖。
    // 3. isTransitive=false：standalone 只换 artifact，POM 元数据照旧被解析，不加这行会把
    //    Hive 发行版的 provided 依赖一起拉进来（apache-curator 是 pom-only，shadowJar 直接以
    //    "Cannot expand ZIP ... .pom" 失败）。
    // 代价：这个 jar 约 48 MB、4.5 万个条目，所以 app 的 shadowJar 必须开 isZip64 = true
    // （ZIP 的 65535 条目上限）。
    // Source: https://mvnrepository.com/artifact/org.apache.hive/hive-jdbc
    runtimeOnly("org.apache.hive:hive-jdbc:4.1.0:standalone") {
        isTransitive = false
    }
    // Hive / hadoop 客户端会通过 slf4j-api 打日志；没有 provider 时 SLF4J 每次启动都往 stderr
    // 刷三行 "No SLF4J providers were found"。本项目不做运行期日志，装个 no-op provider 消音。
    runtimeOnly("org.slf4j:slf4j-nop:2.0.7")
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
