package com.kxxnzstdsw.sync_diff.reporter

import com.kxxnzstdsw.sync_diff.core.DiffLevel
import com.kxxnzstdsw.sync_diff.core.DiffSummary
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 报告侧的契约测试：写完 `.xlsx` 后用 POI（仅测试期依赖，不进 fat jar）读回来断言。
 *
 * 手工拼 OOXML 最容易错的地方是「文件能被真实实现解析」而不是「字符串里有那几行」，
 * 所以这里一律走读回路径：POI 能打开 + 单元格值 / 类型符合预期，才算这个 writer 是对的。
 */
class ReporterTest {

    private fun tmp(name: String): Path = Files.createTempFile("reporter-test-", "-$name.xlsx")

    private fun <T> read(path: Path, block: (XSSFWorkbook) -> T): T =
        XSSFWorkbook(Files.newInputStream(path)).use(block)

    private fun XSSFWorkbook.sheetNames(): List<String> = (0 until numberOfSheets).map { getSheetName(it) }

    private fun XSSFWorkbook.text(sheet: String, row: Int, column: Int): String =
        getSheet(sheet).getRow(row).getCell(column).stringCellValue

    private fun XSSFWorkbook.number(sheet: String, row: Int, column: Int): Double =
        getSheet(sheet).getRow(row).getCell(column).numericCellValue

    private fun XSSFWorkbook.type(sheet: String, row: Int, column: Int): CellType =
        getSheet(sheet).getRow(row).getCell(column).cellType

    private fun XSSFWorkbook.rowCount(sheet: String): Int =
        getSheet(sheet).lastRowNum + 1

    @Test
    fun `excel writes the summary block onto the Diff Summary sheet`() {
        val out = tmp("summary")
        Reporter().excel(out, DiffSummary(srcCount = 100, tgtCount = 99, diffCount = 7, level = DiffLevel.WARN))

        read(out) { wb ->
            assertEquals(listOf("Diff Summary"), wb.sheetNames())
            assertEquals("Diff Summary", wb.text("Diff Summary", 0, 0), "title row shares the sheet name")
            assertEquals("metric", wb.text("Diff Summary", 1, 0))
            assertEquals("value", wb.text("Diff Summary", 1, 1))
            assertEquals("level", wb.text("Diff Summary", 2, 0))
            assertEquals("WARN", wb.text("Diff Summary", 2, 1))
            assertEquals("src rows", wb.text("Diff Summary", 3, 0))
            assertEquals(100.0, wb.number("Diff Summary", 3, 1))
            assertEquals(99.0, wb.number("Diff Summary", 4, 1))
            assertEquals(7.0, wb.number("Diff Summary", 5, 1))
            assertEquals(CellType.NUMERIC, wb.type("Diff Summary", 3, 1), "counts must stay number cells")
            assertEquals("hasDiff = true", wb.text("Diff Summary", 6, 0))
            assertEquals(
                "Top diff keys are reported in the linked L3 detail file.",
                wb.text("Diff Summary", 7, 0),
            )
        }
    }

    @Test
    fun `excel omits the diff note when there is no diff`() {
        val out = tmp("no-diff")
        Reporter().excel(out, DiffSummary(1, 1, 0, DiffLevel.INFO))

        read(out) { wb ->
            assertEquals("hasDiff = false", wb.text("Diff Summary", 6, 0))
            assertEquals(7, wb.rowCount("Diff Summary"), "no trailing note when hasDiff = false")
        }
    }

    @Test
    fun `excel overwrites the existing file`() {
        val out = tmp("overwrite")
        Reporter().excel(out, DiffSummary(1, 1, 0, DiffLevel.INFO)) {
            heading("first run only")
        }
        Reporter().excel(out, DiffSummary(10, 11, 12, DiffLevel.ERROR))

        read(out) { wb ->
            assertEquals(listOf("Diff Summary"), wb.sheetNames(), "second run must not keep the first run's sheets")
            assertEquals(10.0, wb.number("Diff Summary", 3, 1))
            assertEquals(12.0, wb.number("Diff Summary", 5, 1))
            assertEquals("ERROR", wb.text("Diff Summary", 2, 1))
        }
    }

    @Test
    fun `excel keeps heading-less custom content on the summary sheet`() {
        val out = tmp("body-same-sheet")
        Reporter().excel(out, DiffSummary(3, 2, 1, DiffLevel.WARN)) {
            paragraph("只列抽样命中的差异。")
            bullets(listOf("分区齐全", "part 数一致"))
        }

        read(out) { wb ->
            assertEquals(listOf("Diff Summary"), wb.sheetNames())
            assertEquals("只列抽样命中的差异。", wb.text("Diff Summary", 8, 0))
            assertEquals("• 分区齐全", wb.text("Diff Summary", 9, 0))
            assertEquals("• part 数一致", wb.text("Diff Summary", 10, 0))
        }
    }

    @Test
    fun `each heading opens its own sheet and renames the empty default one`() {
        val out = tmp("headings")
        Reporter().excel(out, DiffSummary(3, 2, 1, DiffLevel.WARN)) {
            heading("差异明细")
            table(
                headers = listOf("列", "src", "tgt"),
                rows = listOf(listOf("amount", 1, 2)),
            )
        }

        read(out) { wb ->
            assertEquals(listOf("Diff Summary", "差异明细"), wb.sheetNames())
            assertEquals("差异明细", wb.text("差异明细", 0, 0), "the heading doubles as the sheet title row")
            assertEquals("列", wb.text("差异明细", 1, 0))
            assertEquals("amount", wb.text("差异明细", 2, 0))
            assertEquals(2.0, wb.number("差异明细", 2, 2))
        }
    }

    @Test
    fun `a lone heading renames the default sheet instead of leaving an empty Report sheet`() {
        val out = tmp("rename-default")
        Reporter().excel(out) {
            heading("巡检结果")
            bullets(listOf("分区齐全"))
        }

        read(out) { wb ->
            assertEquals(listOf("巡检结果"), wb.sheetNames())
            assertEquals("巡检结果", wb.text("巡检结果", 0, 0))
            assertEquals("• 分区齐全", wb.text("巡检结果", 1, 0))
        }
    }

    @Test
    fun `an empty workbook still gets one sheet`() {
        val out = tmp("empty")
        Reporter().excel(out) { }

        read(out) { wb ->
            assertEquals(listOf("Report"), wb.sheetNames())
            assertEquals(0, wb.getSheet("Report").physicalNumberOfRows)
        }
    }

    @Test
    fun `heading sanitizes truncates and de-duplicates sheet names`() {
        val out = tmp("sheet-names")
        val longTitle = "x".repeat(40)
        Reporter().excel(out) {
            heading("a/b*c")            // 非法字符 -> 空格
            heading("差异")
            heading("差异")             // 重名 -> 加序号
            heading("ABC")
            heading("abc")              // 重名忽略大小写
            heading(longTitle)          // 超 31 字符 -> 截断
            heading("history")          // Excel 保留名
            heading("   ")              // 清洗后为空
        }

        read(out) { wb ->
            assertEquals(
                listOf(
                    "a b c",
                    "差异",
                    "差异 (2)",
                    "ABC",
                    "abc (2)",
                    "x".repeat(31),
                    "history_",
                    "Report",
                ),
                wb.sheetNames(),
            )
            assertEquals(longTitle, wb.text("x".repeat(31), 0, 0), "the full title must survive in the title row")
        }
    }

    @Test
    fun `cell values keep their type and 64-bit integers keep their digits`() {
        val out = tmp("values")
        val checksum = 123456789012345678L
        Reporter().excel(out) {
            heading("values")
            table(
                headers = listOf("case", "value"),
                rows = listOf(
                    listOf("null", null),
                    listOf("boolean", true),
                    listOf("int", 42),
                    listOf("decimal", BigDecimal("1.25")),
                    listOf("checksum", checksum),
                    listOf("huge decimal", BigDecimal("123456789012345678.123")),
                    listOf("nan", Double.NaN),
                    listOf("text", "<a&b>"),
                ),
            )
        }

        read(out) { wb ->
            assertEquals(CellType.STRING, wb.type("values", 2, 1))
            assertEquals("null", wb.text("values", 2, 1), "null must stay distinguishable from an empty string")
            assertEquals(CellType.BOOLEAN, wb.type("values", 3, 1))
            assertTrue(wb.getSheet("values").getRow(3).getCell(1).booleanCellValue)
            assertEquals(42.0, wb.number("values", 4, 1))
            assertEquals(1.25, wb.number("values", 5, 1))
            assertEquals(
                CellType.STRING,
                wb.type("values", 6, 1),
                "a 64-bit checksum must not be rounded into a double",
            )
            assertEquals(checksum.toString(), wb.text("values", 6, 1))
            assertEquals(CellType.STRING, wb.type("values", 7, 1))
            assertEquals("123456789012345678.123", wb.text("values", 7, 1))
            assertEquals("NaN", wb.text("values", 8, 1))
            assertEquals("<a&b>", wb.text("values", 9, 1), "cell text must survive XML escaping")
        }
    }

    @Test
    fun `table rejects malformed input`() {
        val out = tmp("reject")
        assertFailsWith<IllegalArgumentException>("empty header") {
            Reporter().excel(out) { table(headers = emptyList(), rows = emptyList()) }
        }
        assertFailsWith<IllegalArgumentException>("row wider than header") {
            Reporter().excel(out) { table(headers = listOf("a"), rows = listOf(listOf(1, 2))) }
        }
    }

    @Test
    fun `cell text is truncated at the Excel limit with a marker`() {
        val out = tmp("truncate")
        Reporter().excel(out) { paragraph("y".repeat(40_000)) }

        read(out) { wb ->
            val text = wb.text("Report", 0, 0)
            assertEquals(32_767, text.length, "Excel rejects a workbook with a longer cell")
            assertTrue(text.endsWith("..."), "truncation must be visible, got tail: ${text.takeLast(8)}")
        }
    }

    @Test
    fun `control characters and unpaired surrogates are dropped so the workbook stays readable`() {
        val out = tmp("control-chars")
        Reporter().excel(out) { paragraph("a\u0001b\uD83Dc\uD83D\uDE00d") }

        read(out) { wb -> assertEquals("abc\uD83D\uDE00d", wb.text("Report", 0, 0)) }
    }

    @Test
    fun `webhook no-op on blank url`() {
        val summary = DiffSummary(1, 1, 0, DiffLevel.INFO)
        // should not throw, regardless of network.
        Reporter().webhook("", summary)
        Reporter().webhook(null, summary)
        Reporter().webhook("   ", summary)
    }

    @Test
    fun `webhook posts JSON to a running HTTP server`() {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/webhook") { ex ->
            ex.sendResponseHeaders(200, -1)
            ex.responseBody.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/webhook"
            val captured = AtomicReference<String?>(null)

            val capturing = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            capturing.createContext("/capture") { ex ->
                captured.set(ex.requestBody.bufferedReader().readText())
                ex.sendResponseHeaders(200, -1)
                ex.responseBody.close()
            }
            capturing.start()
            try {
                val capUrl = "http://127.0.0.1:${capturing.address.port}/capture"
                val s = DiffSummary(srcCount = 1, tgtCount = 2, diffCount = 3, level = DiffLevel.ERROR)
                Reporter().webhook(capUrl, s)
                val body = captured.get()
                assertNotNull(body)
                assertTrue(body.contains("\"level\":\"ERROR\""))
                assertTrue(body.contains("\"srcCount\":1"))
                assertTrue(body.contains("\"tgtCount\":2"))
                assertTrue(body.contains("\"diffCount\":3"))
                assertTrue(body.contains("\"hasDiff\":true"))
            } finally {
                capturing.stop(0)
            }

            // Verify the original 'server' URL is still 200-OK to webhook without exception.
            Reporter().webhook(url, DiffSummary.EMPTY)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `webhook swallows IO failure`() {
        // closed port — should not throw.
        Reporter().webhook("http://127.0.0.1:1/webhook", DiffSummary.EMPTY)
    }
}
