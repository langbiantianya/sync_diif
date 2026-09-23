package com.kxxnzstdsw.sync_diff.connectors

private const val PG_DRIVER: String = "org.postgresql.Driver"


/**
 * PostgreSQL 连接配置。
 *
 * JDBC URL 形态：`jdbc:postgresql://host:5432/database`。
 * ```kotlin
 * PgConfig("jdbc:postgresql://pg-prod:5432/ods")
 * PgConfig("jdbc:postgresql://pg-prod:5432/ods", "etl", "secret")
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口（`PG_JDBC_URL` 必填，缺失即抛）。
 */
data class PgConfig(
    val jdbcUrl: String,
    val user: String? = null,
    val password: String? = null,
) {
    companion object {
        fun fromEnv(): PgConfig = PgConfig(
            jdbcUrl = System.getenv("PG_JDBC_URL")
                ?: error("PG_JDBC_URL is not set"),
            user = System.getenv("PG_USER"),
            password = System.getenv("PG_PASSWORD"),
        )
    }
}

/**
 * PostgreSQL 连接器。
 *
 * 建连 / 取数 / 失败包装的公共实现见 [JdbcConnector]：构造期建连（连不上当场抛），
 * `autoCommit = false` 让 [stream] 走服务端游标逐行 `yield`。
 *
 * ```kotlin
 * PgConnector(PgConfig("jdbc:postgresql://pg-prod:5432/ods")).use { conn ->
 *     conn.query("SELECT * FROM orders WHERE dt = '2026-09-01'")
 * }
 * ```
 */
class PgConnector(
    jdbcUrl: String,
    user: String? = null,
    password: String? = null,
    fetchSize: Int = DEFAULT_FETCH_SIZE,
) : JdbcConnector(
    jdbcConnection(PG_DRIVER, jdbcUrl, user, password).also { it.autoCommit = false },
    fetchSize,
) {
    /** 构造自 [PgConfig]：Check 里最常用的入口。 */
    constructor(cfg: PgConfig, fetchSize: Int = DEFAULT_FETCH_SIZE) : this(
        jdbcUrl = cfg.jdbcUrl,
        user = cfg.user,
        password = cfg.password,
        fetchSize = fetchSize,
    )
}
