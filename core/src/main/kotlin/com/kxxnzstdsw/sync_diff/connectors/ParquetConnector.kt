package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.Connection
import java.sql.DriverManager

/**
 * 上游阶段 1 的入口：用 DuckDB 直接读 Parquet（本地文件、glob、S3/OSS）。
 *
 * [path] 只做两件事：判断要不要加载 httpfs、以及给人看的线索。
 * **真正读哪些文件由调用方写的 SQL 决定** —— SQL 里必须显式 `read_parquet('...')` 并把
 * 路径写全（支持 glob，如 `/data/orders/dt=2026-09-01/part-*.parquet`）；本类不会拿 [path]
 * 去拼 SQL。
 *
 * 构造参数：
 * - [path]：文件或 glob 路径，只用于判断是否 `s3://` / `oss://`。
 * - [memoryLimit]：交给 DuckDB `SET memory_limit`，约束 DuckDB 引擎侧的内存上限。
 * - [tempDir]：交给 DuckDB `SET temp_directory`，超出 [memoryLimit] 时的 spill 落盘目录。
 *
 * 对象存储：仅当 [path] 以 `s3://` 或 `oss://` 开头时 `INSTALL` + `LOAD` httpfs。
 * 本类**不注入任何凭据**——S3 凭据走 DuckDB httpfs 自身的配置（`CREATE SECRET`，或
 * `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` 环境变量）；OSS 还要额外配置 S3 兼容端点。
 * 凭据缺失的报错发生在查询阶段，不在构造阶段。
 *
 * 内存（README §10）：DuckDB 按 row group 扫描 Parquet，结果由 DuckDB 自己管，
 * 受 [memoryLimit] 约束、必要时 spill 到 [tempDir]。注意 [Connector.query] 会再把全部行
 * 物化成 `List<Row>`，JVM 堆占用与结果集大小成正比；大结果集只用 [Connector.stream]，
 * 它逐行 `yield`，JVM 侧一次只持有一行。
 *
 * `autoCommit = false`：构造时就把连接切到显式事务模式。DuckDB JDBC 在这之后会在首条
 * 语句前 `BEGIN TRANSACTION`，并一直持有到连接关闭（本类不调 `commit()`）。全程只读，这个
 * 事务的作用只是把会话配置（`SET memory_limit` / `SET temp_directory`）与后续所有查询收在
 * 同一条连接上下文里，而不是每条语句各自成事务。
 *
 * 生命周期：[JDBC_URL] 是内存库，每次构造都会新起一个独立 in-process DuckDB 实例，
 * **不 close 就是泄漏**；一个 Check 里通常一个源一个实例，务必 `use { }` 收尾。
 *
 * ```kotlin
 * val path = "/data/orders/dt=2026-09-01/orders.parquet"
 *
 * ParquetConnector(path).use { src ->
 *     val rows = src.stream("SELECT order_id, amount FROM read_parquet('$path')")
 *         .take(1000)
 *         .toList()
 *     rows.forEach { row -> println("${row["order_id"]} -> ${row.decimal("amount")}") }
 * }
 * ```
 *
 * 注意：`take` 只是停止消费，`stream` 内部的 Statement / ResultSet 要等连接 [close]
 * 才释放（生成器停在 `yield` 上，没有走到 `use` 的收尾）。提前 `take` 之后照样要把
 * 连接关掉，用 `use { }` 是唯一省心的写法。
 */
class ParquetConnector(
    private val path: String,
    private val memoryLimit: String = "1GB",
    private val tempDir: String = "/tmp/duckdb_spill",
) : Connector {

    private val conn: Connection = DriverManager.getConnection(JDBC_URL).apply {
        autoCommit = false
        createStatement().use { st ->
            st.execute("SET memory_limit = '$memoryLimit'")
            st.execute("SET temp_directory = '$tempDir'")
            if (path.startsWith("s3://") || path.startsWith("oss://")) {
                st.execute("INSTALL httpfs")
                st.execute("LOAD httpfs")
            }
        }
    }

    override fun query(sql: String): List<Row> = guard(sql) {
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs -> rs.toRows() }
        }
    }

    // guard 必须在 sequence 内部调用：写在 `sequence { }` 外面只能包住序列对象的创建，
    // 真正的 prepare/execute 发生在迭代时，异常会绕过 guard 直接冒泡成裸 SQLException。
    // guard 是 inline，因此这里的 yield 仍处于 sequence 构建器的受限挂起作用域内。
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
        /** DuckDB 内存模式：每次 `DriverManager.getConnection` 都启一个独立 in-process 实例。 */
        const val JDBC_URL: String = "jdbc:duckdb:"
    }
}