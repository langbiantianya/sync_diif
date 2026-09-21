package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.DriverManager

/**
 * ClickHouse 连接配置。
 *
 * JDBC URL 形态：`jdbc:clickhouse://host:8123/database`。
 * ```kotlin
 * ClickHouseConfig("jdbc:clickhouse://ch-prod:8123/ods")
 * ClickHouseConfig("jdbc:clickhouse://ch-prod:8123/ods", "default", "")
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口。
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
 * 构造期建连；[stream] 使用 `fetchSize` 服务端游标逐行 yield。
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
    private val fetchSize: Int = DEFAULT_FETCH_SIZE,
) : Connector {

    private val conn = DriverManager.getConnection(jdbcUrl, user, password).also {
        it.autoCommit = false
    }

    override fun query(sql: String): List<Row> = guard(sql) {
        conn.prepareStatement(sql).use { st ->
            st.fetchSize = fetchSize
            st.executeQuery().use { rs -> rs.toRows() }
        }
    }

    override fun stream(sql: String): Sequence<Row> = sequence {
        conn.prepareStatement(sql).use { st ->
            st.fetchSize = fetchSize
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    yield(rs.toRow())
                }
            }
        }
    }

    override fun one(sql: String): Row? = query("$sql LIMIT 1").firstOrNull()

    override fun close() {
        conn.close()
    }

    private companion object {
        const val DEFAULT_FETCH_SIZE: Int = 10_000
    }
}
