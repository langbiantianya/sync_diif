package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.Connection

private const val DUCKDB_DRIVER: String = "org.duckdb.DuckDBDriver"

/**
 * DuckDB in-process 会话骨架：`ParquetConnector` / `CsvConnector` / `JsonlConnector` /
 * `ExcelConnector` / `DuckDBConnector` 共用的建会话 + 取数实现。
 *
 * 建会话顺序（构造期一次完成，失败即抛，不 lazy）：
 * 1. `DriverManager.getConnection("jdbc:duckdb:")`——**每次构造一个新 in-process 实例**，
 *    不 close 就是泄漏，务必 `use { }`；
 * 2. `autoCommit = false`：把会话配置与后续查询收在同一条连接上下文里（见下）；
 * 3. `SET memory_limit` / `SET temp_directory`：约束 DuckDB 引擎侧内存与 spill 落盘；
 * 4. `INSTALL` + `LOAD` [extensions]（逐个执行）；`INSTALL` 首次会联网下载扩展，离线环境要有缓存；
 * 5. [setup]：子类追加自己的会话配置。
 *
 * `autoCommit = false` 的作用只是把 `SET ...` 与后续查询放在同一连接上下文，而不是每条语句
 * 各自成事务——本骨架全程只读、不 `commit()`。
 *
 * 取数三方法的契约与 [JdbcConnector] 完全一致（[guard] 包住、[stream] 服务端游标逐行
 * `yield`、失败抛 [com.kxxnzstdsw.sync_diff.core.ConnectorError.QueryFailed]）；区别只在于
 * DuckDB 文件源不需要 JDBC `fetchSize` 批次控制。
 *
 * 子类的写法：把「路径是否远程 / 需要哪些扩展」在 **super 调用里** 算成实参传进来——
 * 子类的 `val path` 属性要等基类初始化完才赋值，在 [setup] 里读它会拿到未初始化字段。
 *
 * ```kotlin
 * class JsonlConnector(
 *     private val path: String,
 *     memoryLimit: String = "4GB",
 *     tempDir: String = "/tmp/duckdb_spill",
 * ) : DuckDbSessionConnector(memoryLimit, tempDir, httpfsFor(path))
 * ```
 */
abstract class DuckDbSessionConnector(
    memoryLimit: String,
    tempDir: String,
    extensions: List<String> = emptyList(),
) : Connector {

    protected val conn: Connection = jdbcConnection(DUCKDB_DRIVER, JDBC_URL).apply {
        autoCommit = false
        createStatement().use { st ->
            st.execute("SET memory_limit = '$memoryLimit'")
            st.execute("SET temp_directory = '$tempDir'")
            extensions.forEach { extension ->
                st.execute("INSTALL $extension")
                st.execute("LOAD $extension")
            }
        }
        setup(this)
    }

    /** 会话配置钩子，在 `SET memory_limit` / `SET temp_directory` / 扩展加载之后调用。 */
    protected open fun setup(conn: Connection) = Unit

    override fun query(sql: String): List<Row> = guard(sql) {
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs -> rs.toRows() }
        }
    }

    // guard 必须在 sequence 内部调用，理由同 JdbcConnector.stream。
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

    companion object {
        /** DuckDB 内存模式：每次 `DriverManager.getConnection` 都启一个独立 in-process 实例。 */
        private const val JDBC_URL: String = "jdbc:duckdb:"
    }
}

/**
 * [path] 指向对象存储（`s3://` / `oss://`）时要加载的扩展列表，否则空表。
 *
 * 顶层 `internal` 而不是类内方法：它必须在子类的 super 调用里求值（见
 * [DuckDbSessionConnector] 的初始化顺序说明）。只会 `LOAD` 扩展、**不注入任何凭据**——
 * S3 走 DuckDB httpfs 自身配置（`CREATE SECRET` 或 `AWS_ACCESS_KEY_ID` /
 * `AWS_SECRET_ACCESS_KEY`），OSS 还要额外配 S3 兼容端点。
 */
internal fun httpfsFor(path: String): List<String> =
    if (path.startsWith("s3://") || path.startsWith("oss://")) listOf("httpfs") else emptyList()
