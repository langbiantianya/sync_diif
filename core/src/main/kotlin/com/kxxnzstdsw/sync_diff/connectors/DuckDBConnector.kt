package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import java.sql.Connection

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
 * 建会话 / 取数的公共实现见 [DuckDbSessionConnector]（`autoCommit=false`、
 * `SET memory_limit` / `temp_directory`、[stream] 服务端游标逐行 `yield`）；本类不预加载任何
 * 扩展，需要 httpfs / excel 就在 [setupSql] 里自己 `INSTALL` + `LOAD`。
 *
 * [setupSql] 在构造期执行（在 `SET memory_limit` / `SET temp_directory` 之后），失败直接抛
 * `SQLException`（fail fast，与其他连接器一致）。
 *
 * ```kotlin
 * // 多数据源：对 DuckDB 附加的 PostgreSQL 和 MySQL 做跨源 join
 * DuckDBConnector { db ->
 *     db.createStatement().execute(
 *         "ATTACH 'postgresql://pg-host:5432/ods' AS pg (TYPE POSTGRESQL, USER 'etl', PASSWORD 'secret')"
 *     )
 *     db.createStatement().execute(
 *         "ATTACH 'mysql://mysql-host:3306/ods' AS my (TYPE MYSQL, USER 'etl', PASSWORD 'secret')"
 *     )
 *     db.createStatement().execute("INSTALL httpfs")
 *     db.createStatement().execute("LOAD httpfs")
 * }.use { conn ->
 *     conn.query(
 *         """
 *         SELECT p.order_id, p.amount, m.status
 *         FROM pg.orders p
 *         JOIN my.orders m ON p.order_id = m.order_id
 *         WHERE p.dt = '2026-09-01'
 *         """.trimIndent(),
 *     )
 * }
 * ```
 *
 * 若不需要附加外部源，直接使用各专用连接器（`ParquetConnector` / `CsvConnector` 等）。
 */
class DuckDBConnector(
    private val setupSql: (Connection) -> Unit = {},
    memoryLimit: String = "4GB",
    tempDir: String = "/tmp/duckdb_spill",
) : DuckDbSessionConnector(memoryLimit, tempDir) {

    override fun setup(conn: Connection) = setupSql(conn)
}
