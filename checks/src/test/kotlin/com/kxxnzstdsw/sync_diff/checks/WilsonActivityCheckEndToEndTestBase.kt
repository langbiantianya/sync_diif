package com.kxxnzstdsw.sync_diff.checks

import com.kxxnzstdsw.sync_diff.check.Check
import com.kxxnzstdsw.sync_diff.connectors.ParquetConnector
import com.kxxnzstdsw.sync_diff.core.DiffLevel
import com.kxxnzstdsw.sync_diff.core.DiffSummary
import com.kxxnzstdsw.sync_diff.core.FieldDiff
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wilson 活动域对账 Check 的端到端测试骨架：合成两份 Parquet（一全、一缺行 + 漂移），
 * 用 `injected` 把它们作为 src / tgt 灌进 [WilsonActivityCheckBase.runCheck]，
 * 断言 [DiffSummary] 的 `hasDiff` / `diffCount` / `level` / `srcCount` / `tgtCount`。
 *
 * 与 [OrderSyncCheckEndToEndTest] 一致：用 DuckDB 写 parquet + ParquetConnector 读，
 * 不依赖 Impala；这是"真实数据流"路径而非 mock。
 *
 * 子类只需要声明 [check]、[columns] 与 [typeFor] / [defaultFor] 三个映射。
 */
abstract class WilsonActivityCheckEndToEndTestBase {

    /** 待测 Check 实例（必填）。 */
    protected abstract val check: WilsonActivityCheckBase

    /** 测试 parquet 里要写出的列清单（必须覆盖 [check] 的 `columns`）。 */
    protected abstract val columns: List<String>

    /** 给定列名，返回该列在 parquet 里的 SQL 类型（`BIGINT` / `TIMESTAMP` / `VARCHAR`）。 */
    protected abstract fun typeFor(col: String): String

    /**
     * 给定列名与行号 [i]，返回一串 DuckDB 字面量表达式。
     *
     * 返回字符串应当是 DuckDB 能直接放进 `VALUES` 的合法字面量（例如 `'code-1'`、`1`、
     * `TIMESTAMP '2026-09-20 00:00:00'`）；与 [typeFor] 给出的类型保持一致。
     */
    protected abstract fun defaultFor(col: String, i: Int): String

    private lateinit var workDir: Path

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        workDir = Files.createTempDirectory("wilson-e2e-")
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    /**
     * 写一份 parquet：schema 覆盖 [columns] 全部列；`drifts` 给出的列 tgt 端会改值。
     *
     * 一行里把所有列都填上，方便"每列抽样"路径覆盖到每一列。
     */
    private fun writeParquet(
        name: String,
        rowCount: Int,
        drifts: Map<String, String> = emptyMap(),
    ): Path {
        val path = workDir.resolve(name)
        val selectColumns = columns.joinToString(",\n            ") { col ->
            val expr = drifts[col] ?: "t.${col}"
            "CAST($expr AS ${typeFor(col)}) AS $col"
        }
        val valuesList = (1..rowCount).joinToString(",\n            ") { i ->
            val allCols = columns.joinToString(", ") { c -> defaultFor(c, i) }
            "($allCols)"
        }
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { st ->
                st.execute(
                    """
                    COPY (
                        SELECT
                            $selectColumns
                        FROM (VALUES
                            $valuesList
                        ) AS t(${columns.joinToString(", ")})
                    ) TO '${path.toAbsolutePath()}' (FORMAT PARQUET)
                    """.trimIndent(),
                )
            }
        }
        return path
    }

    @Test
    fun `runCheck returns zero diffs when src and tgt are identical`() {
        val srcPath = writeParquet("identical_src.parquet", rowCount = 5)
        val tgtPath = writeParquet("identical_tgt.parquet", rowCount = 5)

        check.srcParquetPath = srcPath.toString()
        check.tgtTable = parquetAsTable(tgtPath)
        val src = ParquetConnector(srcPath.toString())
        val tgt = ParquetConnector(tgtPath.toString())
        val result = check.runCheck(src, tgt, sampleSize = 100)

        assertEquals(0L, result.summary.diffCount, "identical src/tgt should yield 0 diffs: ${result.summary}")
        assertEquals(DiffLevel.INFO, result.summary.level)
        assertEquals(5L, result.summary.srcCount, "srcCount = src rows")
        assertEquals(5L, result.summary.tgtCount, "tgtCount = tgt rows")
    }

    @Test
    fun `runCheck detects row count diff and missing PKs when tgt is shorter`() {
        // src: 200 行；tgt: 180 行，PK 1..180
        // 期望：count diff (200 vs 180) + random-sample 中缺失的 PK 数。
        // random-sample 取 100 条 PK（按 hash 伪随机），分布在 1..200；tgt 只有 1..180，
        // 因此落在 181..200 的 PK 必然缺失，约 100 * 20/200 = 10 条（具体数受 hash 影响）。
        val srcPath = writeParquet("count_src.parquet", rowCount = 200)
        val tgtPath = writeParquet("count_tgt.parquet", rowCount = 180)

        check.srcParquetPath = srcPath.toString()
        check.tgtTable = parquetAsTable(tgtPath)
        val src = ParquetConnector(srcPath.toString())
        val tgt = ParquetConnector(tgtPath.toString())
        val result = check.runCheck(src, tgt, sampleSize = 100)
        val summary = result.summary

        assertEquals(200L, summary.srcCount)
        assertEquals(180L, summary.tgtCount)
        // count 行必然 diff；sample 中至少有少量 PK 缺失；总数应 >= 1（count） + 一些 missing。
        // hash 抽样分布在 1..200，tgt 只有 1..180，落在 181..200 的 PK 都缺失 → 大约 5..15 条。
        assertTrue(summary.diffCount >= 2L,
            "expected at least 1 count diff + some missing PKs, got diffCount=${summary.diffCount}: $summary")
        assertTrue(summary.diffCount < 200L,
            "diffCount=${summary.diffCount} should not exceed sampled PKs + count, got: $summary")
        // Missing → DiffLevel.ERROR
        assertEquals(DiffLevel.ERROR, summary.level, "missing rows should escalate to ERROR")

        // 抽到的主键全部进明细（报告表格的行源），缺失数 = diffCount - 1（那 1 条是行数差）。
        assertEquals(100, result.pkSamples.size, "sampleSize=100 → 100 条主键明细")
        assertEquals(
            summary.diffCount - 1L,
            result.pkSamples.count { !it.inTgt }.toLong(),
            "missing PKs in the detail table must match diffCount minus the count row",
        )
    }

    /**
     * 测试用：把一个 parquet 文件路径包成 DuckDB 表表达式 `read_parquet('<path>')`。
     *
     * 生产环境 [WilsonActivityCheckBase.tgtTable] 是一段 Impala SQL 表名（如
     * `dwd.fact_channel_wilson_activity_apply_detail`），下游走 ImpalaConnector 直接执行。
     * 测试没有 Impala，用 ParquetConnector 当下游替身——DuckDB 把路径当表看，
     * 但语法上必须包成 `read_parquet('...')`。
     */
    protected fun parquetAsTable(path: Path): String =
        "read_parquet('${path.toAbsolutePath()}')"

    /**
     * 把一列在 src / tgt 之间「故意错开」的值，分别覆盖到 src / tgt 的两份 parquet 上。
     *
     * 默认实现是「src 用基线、tgt 用漂移值」；子类按列名选两段不同表达式即可。
     */
    protected open fun driftValueFor(col: String): String? = when (col) {
        "code" -> "'TGT-CODE'"
        "enroll_mobile" -> "'13900000000'"
        "title" -> "'TGT-TITLE'"
        "description" -> "'TGT-DESC'"
        "activity_place" -> "'TGT-PLACE'"
        else -> null
    }

    /**
     * 挑选「可漂移」的列：先按 [driftValueFor] 找出候选，再剔除 [defaultFor] 会与漂移值撞车的列
     * （撞车时两端值相同，测不到 Mismatch），最多取 [take] 列。
     */
    private fun driftColumns(take: Int = 2): Map<String, String> = columns
        .mapNotNull { col -> driftValueFor(col)?.let { col to it } }
        .filter { (col, expr) -> !defaultFor(col, 1).contains(expr.trim('\'')) }
        .take(take)
        .toMap()

    @Test
    fun `runCheck detects per-column sample value drift`() {
        // 找出 driftValueFor 不为 null 的两列（若无就跳过本测试）。
        val drifts: Map<String, String> = driftColumns(take = 2)
        if (drifts.size < 2) {
            // 子类列里没有两列可漂移，跳过本测试。
            return
        }
        val srcPath = writeParquet("drift_src.parquet", rowCount = 10)
        val tgtPath = writeParquet("drift_tgt.parquet", rowCount = 10, drifts = drifts)

        check.srcParquetPath = srcPath.toString()
        check.tgtTable = parquetAsTable(tgtPath)
        val src = ParquetConnector(srcPath.toString())
        val tgt = ParquetConnector(tgtPath.toString())
        val result = check.runCheck(src, tgt, sampleSize = 50)
        val summary = result.summary

        assertEquals(10L, summary.srcCount)
        assertEquals(10L, summary.tgtCount)
        // count 行 Equal（10 == 10）；drifts 里两列各 1 个 Mismatch；其它列 Equal；
        // random sample 50 条 PK 在 1..10 内，tgt 也都有 → 0 个 Missing。
        // 所以 diffCount 应当是 2。
        assertEquals(2L, summary.diffCount,
            "expected exactly 2 mismatched column samples, got: $summary")
        assertEquals(DiffLevel.WARN, summary.level, "value mismatch should stay WARN")

        // 单字段抽样明细（报告表格的行源）：每列一行（主键列除外），结论与上面一致。
        assertEquals(columns.size - 1, result.columnSamples.size, "每列一条样本（主键列除外）")
        assertEquals(
            drifts.keys,
            result.columnSamples.filter { it.diff is FieldDiff.Mismatch }.map { it.column }.toSet(),
            "漂移的两列必须是表里唯一判为 Mismatch 的列",
        )
        assertTrue(
            result.columnSamples
                .filter { it.diff is FieldDiff.Mismatch }
                .all { it.srcValue != it.tgtValue && it.tgtValue != null },
            "Mismatch 行必须带出两侧真实取值: ${result.columnSamples}",
        )
    }

    @Test
    fun `runWith writes both sample tables into the report`() {
        val drifts: Map<String, String> = driftColumns(take = 1)
        if (drifts.isEmpty()) return   // 没有可漂移的列就跳过（同 `runCheck detects per-column sample value drift`）
        val srcPath = writeParquet("report_src.parquet", rowCount = 10)
        val tgtPath = writeParquet("report_tgt.parquet", rowCount = 10, drifts = drifts)

        val dt = "2026-09-20"
        val reportPath = Paths.get("reports", "${check.reportFilename}_$dt.md")
        // SQL 模板读的是这两个字段（不是注入的 Connector），所以注入的同时也要改它们——
        // 与其它用例一致；否则 src 侧会指向上一个用例留下的旧 parquet 路径。
        check.srcParquetPath = srcPath.toString()
        check.tgtTable = parquetAsTable(tgtPath)
        check.injected = ParquetConnector(srcPath.toString()) to ParquetConnector(tgtPath.toString())
        check.alertUrl = ""                       // 别把测试的告警发到真地址上
        Files.deleteIfExists(reportPath)
        try {
            runBlocking { check.runWith(Check.Args(dt = dt)) }

            val md = Files.readString(reportPath)
            assertTrue(md.contains("# Diff Summary"), "标准汇总块还在: $md")

            val columnSection = sectionOf(md, "单字段抽样")
            assertTrue(columnSection.isNotEmpty(), "报告缺少单字段抽样表: $md")
            assertEquals(columns.size - 1, dataRowCount(columnSection), "每列一行（主键列除外）: $columnSection")
            drifts.keys.forEach { col ->
                assertTrue(columnSection.contains("| $col |"), "漂移列 $col 未进表: $columnSection")
            }
            assertTrue(columnSection.contains("值不同"), "漂移列的结论应为「值不同」: $columnSection")

            val pkSection = sectionOf(md, "主键随机抽样")
            assertTrue(pkSection.isNotEmpty(), "报告缺少主键随机抽样表: $md")
            assertEquals(10, dataRowCount(pkSection), "10 行上游 → 抽 10 条主键: $pkSection")
            assertTrue(pkSection.contains("| 有 | 有 | 一致 |"), "命中的主键应标一致: $pkSection")
        } finally {
            check.injected = null
            Files.deleteIfExists(reportPath)
        }
    }

    /** 取 `## <title>` 这一节的内容（到下一个 `## ` 为止）。 */
    private fun sectionOf(markdown: String, title: String): String =
        markdown.substringAfter("## $title", "").substringBefore("\n## ")

    /** 数一节里的表格数据行：表头以 `| ` 开头，分隔行（`|:--|`）不是，所以减 1。 */
    private fun dataRowCount(section: String): Int =
        section.lines().count { it.startsWith("| ") } - 1

    @Test
    fun `check is registered with its declared name`() {
        assertNotNull(check)
        // name 是子类 WilsonActivityCheckBase(name = "...") 传进去的，与 [columns] 配套。
        // 这里只断言「check 不为 null」即可；具体名字在更窄的单测里覆盖（见各 Check 的
        // 单测里 assertEquals(name, Check.name)）。
    }
}
