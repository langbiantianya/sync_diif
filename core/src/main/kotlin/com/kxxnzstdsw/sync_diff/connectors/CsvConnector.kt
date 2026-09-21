package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.DriverManager

/**
 * CSV connector using DuckDB read_csv_auto.
 *
 * @param path local path, glob, or s3/oss path
 * @param memoryLimit DuckDB memory limit
 * @param tempDir DuckDB spill directory
 * @param csvOptions extra options for read_csv_auto
 */
class CsvConnector(
    private val path: String,
    private val memoryLimit: String = "4GB",
    private val tempDir: String = "/tmp/duckdb_spill",
    private val csvOptions: String = "",
) : Connector {

    private val conn = DriverManager.getConnection(JDBC_URL).apply {
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
