package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.ConnectorError
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.instant
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.*

class ParquetConnectorTest {

    private lateinit var workDir: Path

    @org.junit.jupiter.api.BeforeEach
    fun setUpDir() {
        workDir = Files.createTempDirectory("sync-diff-parquet-test-")
    }

    @AfterTest
    fun tearDown() {
        if (Files.exists(workDir)) {
            workDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parquet connector reads a synthetic parquet with TIMESTAMP and DECIMAL coercion`() {
        // DuckDB itself writes a parquet file with TIMESTAMP + DECIMAL columns,
        // then ParquetConnector reads it back — exercising ResultSetExt type coercion.
        val parquet = workDir.resolve("orders.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { st ->
                st.execute(
                    """
                    COPY (
                        SELECT * FROM (VALUES
                            (1, 'alice', 12.34, TIMESTAMP '2026-09-20 01:23:45'),
                            (2, 'bob',   99.00, TIMESTAMP '2026-09-20 02:00:00'),
                            (3, 'carol',  0.50, TIMESTAMP '2026-09-20 03:00:00')
                        ) AS t(id, name, amount, updated_at)
                    ) TO '${parquet.toAbsolutePath()}' (FORMAT PARQUET)
                    """.trimIndent(),
                )
            }
        }

        ParquetConnector(parquet.toString()).use { connector ->
            val rows: List<Row> = connector.query(
                "SELECT id, name, amount, updated_at FROM read_parquet('${parquet.toAbsolutePath()}') ORDER BY id",
            )
            assertTrue(rows.isNotEmpty(), "expected non-empty rows")
            assertEquals(3, rows.size)

            val alice = rows[0]
            assertEquals(1, alice["id"])
            assertEquals("alice", alice["name"])
            assertNotNull(alice["amount"], "amount column must be present")
            assertNotNull(alice["updated_at"], "updated_at column must be present")

            // Type coercion per README §9: TIMESTAMP -> Instant, DECIMAL -> BigDecimal.
            assertEquals(Instant.parse("2026-09-20T01:23:45Z"), alice["updated_at"])
            assertEquals(BigDecimal("12.34"), alice["amount"])

            // Typed accessor on Row round-trips the Instant.
            assertEquals(Instant.parse("2026-09-20T02:00:00Z"), rows[1].instant("updated_at"))
        }
    }

    @Test
    fun `parquet connector stream yields rows lazily`() {
        val path = workDir.resolve("stream.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { st ->
                st.execute(
                    """
                    COPY (
                        SELECT * FROM (VALUES
                            (1, 'x'),
                            (2, 'y'),
                            (3, 'z'),
                            (4, 'w')
                        ) AS t(id, label)
                    ) TO '${path.toAbsolutePath()}' (FORMAT PARQUET)
                    """.trimIndent(),
                )
            }
        }

        ParquetConnector(path.toString()).use { connector ->
            val taken: Sequence<Row> = connector.stream(
                "SELECT id, label FROM read_parquet('${path.toAbsolutePath()}')",
            )
            val first2 = taken.take(2).toList()
            assertEquals(2, first2.size)
            assertEquals(1, first2[0]["id"])
            assertEquals(2, first2[1]["id"])
        }
    }

    @Test
    fun `parquet connector one returns single row or null`() {
        val path = workDir.resolve("one.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { st ->
                st.execute(
                    "COPY (SELECT 1 AS id, 'alice' AS name) TO '${path.toAbsolutePath()}' (FORMAT PARQUET)",
                )
            }
        }

        ParquetConnector(path.toString()).use { connector ->
            val row: Row? = connector.one(
                "SELECT id, name FROM read_parquet('${path.toAbsolutePath()}')",
            )
            assertNotNull(row)
            assertEquals(1, row["id"])
            assertEquals("alice", row["name"])
        }
    }

    @Test
    fun `query failure surfaces as ConnectorError_QueryFailed carrying the sql`() {
        // Connector 契约（见 Connector.kt）：失败必须抛领域错误而不是裸 SQLException。
        val missing = workDir.resolve("does-not-exist.parquet")
        ParquetConnector(missing.toString()).use { connector ->
            val sql = "SELECT * FROM read_parquet('${missing.toAbsolutePath()}')"

            val failure = assertFailsWith<ConnectorError.QueryFailed> { connector.query(sql) }

            assertEquals(sql, failure.sql, "领域错误必须带上原始 SQL")
            assertNotNull(failure.cause, "必须保留底层异常供排查")
        }
    }

    @Test
    fun `stream failure surfaces as ConnectorError when the sequence is consumed`() {
        // stream 是惰性的：prepare/execute 发生在迭代时，因此失败也在迭代时才抛出，
        // 且同样必须是领域错误（guard 必须写在 sequence 构建器内部）。
        val missing = workDir.resolve("stream-missing.parquet")
        ParquetConnector(missing.toString()).use { connector ->
            val sql = "SELECT * FROM read_parquet('${missing.toAbsolutePath()}')"

            val rows = connector.stream(sql)   // 此处不抛：还没开始迭代
            assertFailsWith<ConnectorError.QueryFailed> { rows.toList() }
        }
    }
}