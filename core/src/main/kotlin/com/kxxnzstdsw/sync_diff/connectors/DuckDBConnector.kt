package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.DriverManager

/**
 * 可自定义的 DuckDB 连接器：用户可在 [setupSql] 中加载扩展、附加外部数据源（PostgreSQL /
 * MySQL / SQLite / …），然后用标准 SQL 对已附加的源做查询。
 *
 * DuckDB 支持多种外部数据源：
 * - PostgreSQL：`ATTACH 'postgresql://host:5432/db' AS pg (TYPE POSTGRESQL)`
 * - MySQL：`ATTACH 'mysql://host:3306/db' AS my (TYPE MYSQL)`
 * - SQLite：`ATTACH 'sqlite:/tmp/test.db' AS sq (TYPE SQLITE)`
 * - 其他 DuckDB 实例：`ATTACH 'duckdb:/tmp/db.duckdb' AS d (TYPE DUCKDB)`
 * - S3 / OSS：`CREATE SECRET` 后直接读写
 *
 * [setupSql] 在构造期执行，失败直接抛 `SQLException`（fail fast，与其他 Connector 一致）。
 * 连接生命周期：`autoCommit = false`，所有查询在同一个事务上下文内。
 *
 * ```kotlin
 * // 多数据源：对 DuckDB 附加的 PostgreSQL 和 MySQL 做跨源 join
 * DuckDBConnector { db ->
 *     // 附加 PostgreSQL
 *     db.createStatement().execute(
 *         "ATTACH 'postgresql://pg-host:5432/ods' AS pg (TYPE POSTGRESQL, USER 'etl', PASSWORD 'secret')"
 *     )
 *     // 附加 MySQL
 *     db.createStatement().execute(
 *         "ATTACH 'mysql://mysql-host:3306/ods' AS my (TYPE MYSQL, USER 'etl', PASSWORD 'secret')"
 *     )
 *     // 加载 httpfs 读 S3
 *     db.createStatement().execute("INSTALL httpfs; LOAD httpfs;")
 * }.use { conn ->
 *     conn.query("""
 *         SELECT p.order_id, p.amount, m.status
 *         FROM pg.orders p
 *         JOIN my.orders m ON p.order_id = m.order_id
 *         WHERE p.dt = '2026-09-01'
 *     """)
 * }
 * ```
 *
 * 若不需要附加外部源，直接使用各专用连接器（`ParquetConnector` / `CsvConnector` 等）。
 */
class DuckDBConnector(
    private val setupSql: (java.sql.Connection) -> Unit = {},
    private val memoryLimit: String = "4GB",
    private val tempDir: String = "/tmp/duckdb_spill",
) : Connector {

    private val conn = DriverManager.getConnection(JDBC_URL).apply {
        autoCommit = false
        createStatement().use { st ->
            st.execute("SET memory_limit = '$memoryLimit'")
            st.execute("SET temp_directory = '$tempDir'")
        }
        setupSql(this)
    }

    override fun query(sql: String): List<Row> = guard(sql) {
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs -> rs.toRows() }
        }
    }

    override fun stream(sql: String): Sequence<Row> = sequence {
        guard(sql) {
            conn.prepareStatement(sql).use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) yield(rs.toRow())
                }
            }
        }
    }

    override fun one(sql: String): Row? = query("$sql LIMIT 1").firstOrNull()

    override fun close() {
        conn.close()
    }

    private companion object {
        private const val JDBC_URL = "jdbc:duckdb:"
    }
}
