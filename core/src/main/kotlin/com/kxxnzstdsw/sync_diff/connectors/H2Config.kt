package com.kxxnzstdsw.sync_diff.connectors

private const val H2_DRIVER: String = "org.h2.Driver"


/**
 * H2 连接配置。
 *
 * JDBC URL 形态：`jdbc:h2:mem:test`（内存）、`jdbc:h2:./data/test`（文件）、`jdbc:h2:tcp://host:9092/test`（远程）。
 * ```kotlin
 * H2Config("jdbc:h2:mem:test")
 * H2Config("jdbc:h2:./data/test", "sa", "")
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口。
 */
data class H2Config(
    val jdbcUrl: String,
    val user: String = "sa",
    val password: String = "",
) {
    companion object {
        fun fromEnv(): H2Config = H2Config(
            jdbcUrl = System.getenv("H2_JDBC_URL") ?: "jdbc:h2:mem:test",
            user = System.getenv("H2_USER") ?: "sa",
            password = System.getenv("H2_PASSWORD") ?: "",
        )
    }
}

/**
 * H2 连接器：嵌入式或远程 H2 数据库。
 *
 * 建连 / 取数 / 失败包装的公共实现见 [JdbcConnector]。生产对账不用它，主要价值是**测试替身**：
 * 内置 JDBC 源，可零依赖起一个 `jdbc:h2:mem:` 实例验证 Check 的 SQL 与 [JdbcConnector] 契约。
 *
 * ```kotlin
 * H2Connector(H2Config("jdbc:h2:mem:test")).use { conn ->
 *     conn.query("SELECT * FROM orders WHERE dt = '2026-09-01'")
 * }
 * ```
 */
class H2Connector(
    jdbcUrl: String,
    user: String = "sa",
    password: String = "",
    fetchSize: Int = DEFAULT_FETCH_SIZE,
) : JdbcConnector(
    jdbcConnection(H2_DRIVER, jdbcUrl, user, password).also { it.autoCommit = false },
    fetchSize,
) {
    /** 构造自 [H2Config]。 */
    constructor(cfg: H2Config, fetchSize: Int = DEFAULT_FETCH_SIZE) : this(
        jdbcUrl = cfg.jdbcUrl,
        user = cfg.user,
        password = cfg.password,
        fetchSize = fetchSize,
    )
}
