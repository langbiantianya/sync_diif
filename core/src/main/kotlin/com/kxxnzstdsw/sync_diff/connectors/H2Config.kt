package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.DriverManager

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
 * 构造期建连；[stream] 使用 `fetchSize` 服务端游标逐行 yield。
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
