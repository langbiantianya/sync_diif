package com.kxxnzstdsw.sync_diff.connectors

private const val MYSQL_DRIVER: String = "com.mysql.cj.jdbc.Driver"


/**
 * MySQL 连接配置。
 *
 * JDBC URL 形态：`jdbc:mysql://host:3306/database`。
 * ```kotlin
 * MySQLConfig("jdbc:mysql://mysql-prod:3306/ods")
 * MySQLConfig("jdbc:mysql://mysql-prod:3306/ods", "etl", "secret")
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口（`MYSQL_JDBC_URL` 必填，缺失即抛）。
 */
data class MySQLConfig(
    val jdbcUrl: String,
    val user: String? = null,
    val password: String? = null,
) {
    companion object {
        fun fromEnv(): MySQLConfig = MySQLConfig(
            jdbcUrl = System.getenv("MYSQL_JDBC_URL")
                ?: error("MYSQL_JDBC_URL is not set"),
            user = System.getenv("MYSQL_USER"),
            password = System.getenv("MYSQL_PASSWORD"),
        )
    }
}

/**
 * MySQL 连接器。
 *
 * 建连 / 取数 / 失败包装的公共实现见 [JdbcConnector]；`autoCommit = false` 已由本类设置。
 *
 * **注意**：Connector-J 只在 URL 带 `useCursorFetch=true` 时才按 `fetchSize` 服务端游标取数，
 * 否则会把整表拉回客户端再切分——那样 [stream] 的惰性就失效了。本类不代改 URL（也不做隐式
 * 拼接），所以接 MySQL 时请自己写全：
 *
 * ```kotlin
 * MySQLConnector(
 *     MySQLConfig("jdbc:mysql://mysql-prod:3306/ods?useCursorFetch=true", "etl", "secret"),
 * ).use { conn ->
 *     conn.query("SELECT * FROM orders WHERE dt = '2026-09-01'")
 * }
 * ```
 */
class MySQLConnector(
    jdbcUrl: String,
    user: String? = null,
    password: String? = null,
    fetchSize: Int = DEFAULT_FETCH_SIZE,
) : JdbcConnector(
    jdbcConnection(MYSQL_DRIVER, jdbcUrl, user, password).also { it.autoCommit = false },
    fetchSize,
) {
    /** 构造自 [MySQLConfig]：Check 里最常用的入口。 */
    constructor(cfg: MySQLConfig, fetchSize: Int = DEFAULT_FETCH_SIZE) : this(
        jdbcUrl = cfg.jdbcUrl,
        user = cfg.user,
        password = cfg.password,
        fetchSize = fetchSize,
    )
}
