package com.kxxnzstdsw.sync_diff.checks

import com.kxxnzstdsw.sync_diff.connectors.ParquetConnector
import com.kxxnzstdsw.sync_diff.core.DiffLevel
import com.kxxnzstdsw.sync_diff.core.DiffSummary
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

/**
 * OrderSyncCheck 的端到端测试：合成两份 parquet（一全、一缺一行 + amount 漂移），
 * 把它们作为 src / tgt 注入 [com.kxxnzstdsw.sync_diff.checks.OrderSyncCheck.runCheck]，断言
 * [DiffSummary.diffCount] > 0 且级别升到 [DiffLevel.WARN]。
 *
 * 用真实 DuckDB 写 parquet + ParquetConnector 读，避免 mock；这是"真实数据流"路径。
 */
class OrderSyncCheckEndToEndTest {

    private lateinit var workDir: Path

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        workDir = Files.createTempDirectory("order-sync-e2e-")
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    /**
     * 用 DuckDB 写一个包含 5 行的 parquet。
     * Schema: order_id BIGINT, amount DOUBLE, status VARCHAR, updated_at TIMESTAMP, dt VARCHAR.
     */
    private fun writeParquet(name: String, sqlValues: String): Path {
        val path = workDir.resolve(name)
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { st ->
                st.execute(
                    """
                    COPY (
                        SELECT
                            CAST(t.id AS BIGINT)            AS order_id,
                            CAST(t.amount AS DOUBLE)        AS amount,
                            t.status                        AS status,
                            CAST(t.updated_at AS TIMESTAMP) AS updated_at,
                            '2026-09-20'                    AS dt
                        FROM (VALUES $sqlValues) AS t(id, amount, status, updated_at)
                    ) TO '${path.toAbsolutePath()}' (FORMAT PARQUET)
                    """.trimIndent(),
                )
            }
        }
        return path
    }

    @Test
    fun `runCheck produces diffs when tgt is missing a row and another row has drifted amount`() {
        // src: 5 行（id=1..5），amount 全部 100
        val srcPath = writeParquet(
            "src.parquet",
            """
            (1, 100.00, 'PAID',    TIMESTAMP '2026-09-20 01:00:00'),
            (2, 100.00, 'PAID',    TIMESTAMP '2026-09-20 01:01:00'),
            (3, 100.00, 'PAID',    TIMESTAMP '2026-09-20 01:02:00'),
            (4, 100.00, 'REFUND',  TIMESTAMP '2026-09-20 01:03:00'),
            (5, 100.00, 'PAID',    TIMESTAMP '2026-09-20 01:04:00')
            """.trimIndent(),
        )
        // tgt: 4 行，id=2..5；amount: id=3 漂到 105.00（差 5.0，超 0.01 tolerance）
        val tgtPath = writeParquet(
            "tgt.parquet",
            """
            (2, 100.00, 'PAID',    TIMESTAMP '2026-09-20 01:01:00'),
            (3, 105.00, 'PAID',    TIMESTAMP '2026-09-20 01:02:00'),
            (4, 100.00, 'REFUND',  TIMESTAMP '2026-09-20 01:03:00'),
            (5, 100.00, 'PAID',    TIMESTAMP '2026-09-20 01:04:00')
            """.trimIndent(),
        )

        // 把 OrderSyncCheck 的路径替换成我们写的文件；connector 用同一份路径打开即可。
        OrderSyncCheck.parquetPath = srcPath.toString()
        OrderSyncCheck.tgtParquetPath = tgtPath.toString()
        val src = ParquetConnector(srcPath.toString())
        val tgt = ParquetConnector(tgtPath.toString())
        val summary: DiffSummary = OrderSyncCheck.runCheck(src, tgt, dt = "2026-09-20", take = 1000)

        // 应有差异：src 多一行 (id=1)，src 有 id=3 amount=100 vs tgt amount=105（5.0 漂移，超 tolerance）。
        assertTrue(summary.hasDiff, "summary.hasDiff should be true, got $summary")
        assertTrue(summary.diffCount > 0L,
            "summary.diffCount should be > 0 (missing row + amount drift), got ${summary.diffCount}")
        // 由于 rowDiffCount > 0，summary 的 level 升到 WARN
        assertEquals(DiffLevel.WARN, summary.level)
        // srcCount 应为 src 流过的行数（5），tgtCount 应为 tgt 流过的行数（4）
        assertEquals(5L, summary.srcCount, "srcCount = src rows streamed")
        assertEquals(4L, summary.tgtCount, "tgtCount = tgt rows streamed")
    }

    @Test
    fun `runCheck returns zero diffs when src and tgt are identical`() {
        val values = """
            (1, 100.00, 'PAID', TIMESTAMP '2026-09-20 01:00:00'),
            (2, 100.00, 'PAID', TIMESTAMP '2026-09-20 01:01:00'),
            (3, 100.00, 'PAID', TIMESTAMP '2026-09-20 01:02:00')
        """.trimIndent()
        val srcPath = writeParquet("identical_src.parquet", values)
        val tgtPath = writeParquet("identical_tgt.parquet", values)

        OrderSyncCheck.parquetPath = srcPath.toString()
        OrderSyncCheck.tgtParquetPath = tgtPath.toString()
        val src = ParquetConnector(srcPath.toString())
        val tgt = ParquetConnector(tgtPath.toString())
        val summary = OrderSyncCheck.runCheck(src, tgt, dt = "2026-09-20", take = 1000)

        assertEquals(0L, summary.diffCount, "identical src/tgt should yield 0 diffs: $summary")
        assertEquals(DiffLevel.INFO, summary.level)
        assertEquals(3L, summary.srcCount)
        assertEquals(3L, summary.tgtCount)
    }

    @Test
    fun `runCheck tolerates amount drift within 0_01`() {
        // src: amount=100；tgt: amount=100.005（差 0.005，在 tolerance 0.01 内）
        val srcPath = writeParquet(
            "tol_src.parquet",
            "(1, 100.000, 'PAID', TIMESTAMP '2026-09-20 01:00:00')",
        )
        val tgtPath = writeParquet(
            "tol_tgt.parquet",
            "(1, 100.005, 'PAID', TIMESTAMP '2026-09-20 01:00:00')",
        )

        OrderSyncCheck.parquetPath = srcPath.toString()
        OrderSyncCheck.tgtParquetPath = tgtPath.toString()
        val src = ParquetConnector(srcPath.toString())
        val tgt = ParquetConnector(tgtPath.toString())
        val summary = OrderSyncCheck.runCheck(src, tgt, dt = "2026-09-20", take = 100)

        // amount 0.005 在 0.01 tolerance 内 → 不应被报告为 diff。
        assertEquals(0L, summary.diffCount, "0.005 drift should be tolerated: $summary")
        assertEquals(DiffLevel.INFO, summary.level)
    }

    @Test
    fun `OrderSyncCheck is registered with name order_sync`() {
        assertEquals("order_sync", OrderSyncCheck.name)
        assertNotNull(OrderSyncCheck)
    }
}
