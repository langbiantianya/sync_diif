package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.DriverManager

/**
 * MySQL 连接配置。
 *
 * JDBC URL 形态：`jdbc:mysql://host:3306/database`。
 * ```kotlin
 * MySQLConfig("jdbc:mysql://mysql-prod:3306/ods")
 * MySQLConfig("jdbc:mysql://mysql-prod:3306/ods", "etl", "secret")
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口。
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
 * 构造期建连；[stream] 使用 `fetchSize` 服务端游标逐行 yield。
 *
 * ```kotlin
 * MySQLConnector(MySQLConfig("jdbc:mysql://mysql-prod:3306/ods")).use { conn ->
 *     conn.query("SELECT * FROM orders WHERE dt = '2026-09-01'")
 * }
 * ```
 */
class MySQLConnector(
    jdbcUrl: String,
    user: String? = null,
    password: String? = null,
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
