package com.kxxnzstdsw.sync_diff.connectors

private const val CLICKHOUSE_DRIVER: String = "com.clickhouse.jdbc.ClickHouseDriver"


/**
 * ClickHouse 连接配置。
 *
 * JDBC URL 形态：`jdbc:clickhouse://host:8123/database`。
 * ```kotlin
 * ClickHouseConfig("jdbc:clickhouse://ch-prod:8123/ods")
 * ClickHouseConfig("jdbc:clickhouse://ch-prod:8123/ods", "default", "")
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口（`CLICKHOUSE_JDBC_URL` 必填，缺失即抛）。
 */
data class ClickHouseConfig(
    val jdbcUrl: String,
    val user: String = "default",
    val password: String = "",
) {
    companion object {
        fun fromEnv(): ClickHouseConfig = ClickHouseConfig(
            jdbcUrl = System.getenv("CLICKHOUSE_JDBC_URL")
                ?: error("CLICKHOUSE_JDBC_URL is not set"),
            user = System.getenv("CLICKHOUSE_USER") ?: "default",
            password = System.getenv("CLICKHOUSE_PASSWORD") ?: "",
        )
    }
}

/**
 * ClickHouse 连接器。
 *
 * 建连 / 取数 / 失败包装的公共实现见 [JdbcConnector]：构造期建连，`autoCommit = false`
 * 配 `fetchSize` 走服务端游标逐行 `yield`。
 *
 * ```kotlin
 * ClickHouseConnector(ClickHouseConfig("jdbc:clickhouse://ch-prod:8123/ods")).use { conn ->
 *     conn.query("SELECT * FROM orders WHERE dt = '2026-09-01'")
 * }
 * ```
 */
class ClickHouseConnector(
    jdbcUrl: String,
    user: String = "default",
    password: String = "",
    fetchSize: Int = DEFAULT_FETCH_SIZE,
) : JdbcConnector(
    jdbcConnection(CLICKHOUSE_DRIVER, jdbcUrl, user, password).also { it.autoCommit = false },
    fetchSize,
) {
    /** 构造自 [ClickHouseConfig]：Check 里最常用的入口。 */
    constructor(cfg: ClickHouseConfig, fetchSize: Int = DEFAULT_FETCH_SIZE) : this(
        jdbcUrl = cfg.jdbcUrl,
        user = cfg.user,
        password = cfg.password,
        fetchSize = fetchSize,
    )
}
