package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.DriverManager

/**
 * MaxCompute 连接配置。
 *
 * JDBC URL 形态：`jdbc:odps:http://service.cn-shanghai.maxcompute.aliyun.com/api`。
 * 需要配合 `aliyun.odps.jdbc.user` / `aliyun.odps.jdbc.password`（access key）。
 * ```kotlin
 * MaxComputeConfig(
 *     jdbcUrl = "jdbc:odps:http://service.cn-shanghai.maxcompute.aliyun.com/api",
 *     user = "your_access_key_id",
 *     password = "your_access_key_secret",
 *     project = "your_project",
 * )
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口。
 */
data class MaxComputeConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val project: String,
) {
    companion object {
        fun fromEnv(): MaxComputeConfig = MaxComputeConfig(
            jdbcUrl = System.getenv("MAXCOMPUTE_JDBC_URL")
                ?: error("MAXCOMPUTE_JDBC_URL is not set"),
            user = System.getenv("MAXCOMPUTE_USER")
                ?: error("MAXCOMPUTE_USER is not set"),
            password = System.getenv("MAXCOMPUTE_PASSWORD")
                ?: error("MAXCOMPUTE_PASSWORD is not set"),
            project = System.getenv("MAXCOMPUTE_PROJECT")
                ?: error("MAXCOMPUTE_PROJECT is not set"),
        )
    }
}

/**
 * MaxCompute 连接器。
 *
 * 构造期建连；[stream] 使用 `fetchSize` 服务端游标逐行 yield。
 * MaxCompute JDBC 不支持 `autoCommit=false`，使用默认自动提交。
 *
 * ```kotlin
 * MaxComputeConnector(MaxComputeConfig(
 *     jdbcUrl = "jdbc:odps:http://service.cn-shanghai.maxcompute.aliyun.com/api",
 *     user = "access_key_id",
 *     password = "access_key_secret",
 *     project = "my_project",
 * )).use { conn ->
 *     conn.query("SELECT * FROM orders WHERE dt = '2026-09-01'")
 * }
 * ```
 */
class MaxComputeConnector(
    jdbcUrl: String,
    user: String,
    password: String,
    private val fetchSize: Int = DEFAULT_FETCH_SIZE,
) : Connector {

    private val conn = DriverManager.getConnection(jdbcUrl, user, password)

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
