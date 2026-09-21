package com.kxxnzstdsw.sync_diff.reporter

import com.kxxnzstdsw.sync_diff.core.DiffSummary
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 对账结果输出：Excel 报告 + 可选 Webhook 告警。
 *
 * 报告是 `.xlsx`，一个 Check 一次执行一个工作簿。落盘细节见 [XlsxWriter]，
 * [excel] 里的标准汇总块（`render(summary) {}` 的真实输出）：
 *
 * ```
 * sheet「Diff Summary」
 *   A1  Diff Summary                                     ← 加粗标题（与 sheet 名同源）
 *   A2  metric        B2  value                          ← 加粗表头（灰底）
 *   A3  level         B3  WARN
 *   A4  src rows      B4  100
 *   A5  tgt rows      B5  99
 *   A6  diff keys     B6  7
 *   A7  hasDiff = true
 *   A8  Top diff keys are reported in the linked L3 detail file.
 * ```
 *
 * 四行汇总（level / src rows / tgt rows / diff keys）是固定内容：`level` 单元格写
 * INFO / WARN / ERROR，行数三行是**数字单元格**（可在 Excel 里直接求和 / 排序）。
 * `hasDiff` 一行总是写，指向 L3 明细的说明只在 `hasDiff == true` 时追加。汇总块本身不含
 * row-level 明细——差异明细、业务字段对照这类内容由 Check 自己追加，见下面的自定义内容。
 *
 * [webhook] POST 出去的 JSON body（[renderJson] 的真实输出）**不变**，与报告格式无关：
 *
 * ```json
 * {"level":"WARN","srcCount":100,"tgtCount":99,"diffCount":7,"hasDiff":true}
 * ```
 *
 * `level` 是 [com.kxxnzstdsw.sync_diff.core.DiffLevel] 的 `name`，其余字段名与 [DiffSummary]
 * 的属性名一致（camelCase）。
 *
 * ## 自定义内容
 *
 * [excel] 有三种写法，都是覆盖写、自动创建父目录：
 *
 * ```kotlin
 * // ① 只有标准汇总块
 * report.excel("reports/order_sync_$dt.xlsx", summary)
 *
 * // ② 汇总块 + Check 追加的自定义内容（每个 heading 一张 sheet）
 * report.excel("reports/order_sync_$dt.xlsx", summary) {
 *     heading("差异明细（抽样）")
 *     table(
 *         headers = listOf("层次", "列", "主键", "src", "tgt"),
 *         rows = rows.map { it.toCells() },          // Iterable<Iterable<Any?>>
 *     )
 *     paragraph("只列抽样命中的差异，未命中不代表一致。")
 * }
 *
 * // ③ 完全自定义，不写汇总块（报告长什么样全由 Check 决定）
 * report.excel("reports/inspect_$dt.xlsx") {
 *     heading("巡检结果")
 *     bullets(listOf("分区齐全", "part 文件数与上游一致"))
 * }
 * ```
 *
 * 内容用 [ExcelBuilder] 的 DSL 拼：`heading` / `paragraph` / `bullets` / `table`。
 * 其中 [ExcelBuilder.heading] 会开一张新 sheet（标题同时写进 sheet 名与首行），
 * 所以「一张明细表 = 一张 sheet」是自然落法，不用自己算单元格坐标。
 *
 * 一个 Reporter 实例无状态，可复用；所有方法只读 [summary]，自定义内容在调用点上就地渲染
 * （每次 [excel] 都新建 [ExcelBuilder]），并发调不同 [excel] 不会互相污染。
 */
class Reporter {

    /**
     * 写一份 Excel 报告到 [path]，**覆盖**已存在的文件（不是追加）。
     *
     * 父目录不存在会自动创建（`Files.createDirectories`）；[path] 没有父目录（裸文件名）时
     * 写到当前目录。内容结构见 [Reporter] 类注释。
     *
     * ```kotlin
     * val summary: DiffSummary = engine.summary()
     * Reporter().excel("reports/order_sync_$dt.xlsx", summary)
     * ```
     *
     * 与 [webhook] 不同，本方法**会抛** IO 异常（[java.io.IOException] / 权限、磁盘满等）：
     * 报告是对账的交付物，写不出来就应该失败，而不是静默吞掉。
     */
    fun excel(path: String, summary: DiffSummary): Unit = excel(Paths.get(path), summary)

    /** 同 [excel]，但接收 [Path]；方便测试里用 `Files.createTempFile`。 */
    fun excel(path: Path, summary: DiffSummary) {
        write(path, render(summary) {})
    }

    /**
     * 标准汇总块 + [body] 追加的自定义内容（表格 / 说明 / 清单……），写盘语义同 [excel]。
     *
     * ```kotlin
     * report.excel("reports/wilson_$dt.xlsx", summary) {
     *     heading("差异明细（抽样）")
     *     table(listOf("列", "主键", "src", "tgt"), cells)
     * }
     * ```
     *
     * [body] 在汇总块之后调用，可以挂任意多段；抛异常同样会中断写盘（内容没准备好就不该产出报告）。
     * 带 [ExcelBuilder.heading] 的内容各自落到新 sheet，不带 heading 的内容接在「Diff Summary」
     * sheet 的汇总块下面。
     */
    fun excel(path: String, summary: DiffSummary, body: ExcelBuilder.() -> Unit): Unit =
        excel(Paths.get(path), summary, body)

    /** 同 [excel] 的三参版本，但接收 [Path]。 */
    fun excel(path: Path, summary: DiffSummary, body: ExcelBuilder.() -> Unit) {
        write(path, render(summary, body))
    }

    /**
     * 完全自定义的报告：不写标准汇总块，内容全部由 [body] 决定。写盘语义同 [excel]。
     *
     * 适合输出与 [DiffSummary] 无关的结果（巡检结论、上游表结构、耗时分布……）：
     *
     * ```kotlin
     * report.excel("reports/inspect_$dt.xlsx") {
     *     heading("巡检结果")
     *     bullets(listOf("分区齐全", "part 文件数与上游一致"))
     * }
     * ```
     *
     * 注意这样写出来的报告没有 level / 行数 / 差异数，webhook 那边仍应单独发 [DiffSummary]。
     */
    fun excel(path: String, body: ExcelBuilder.() -> Unit): Unit = excel(Paths.get(path), body)

    /** 同 [excel] 的两参 lambda 版本，但接收 [Path]。 */
    fun excel(path: Path, body: ExcelBuilder.() -> Unit) {
        write(path, ExcelBuilder().apply(body))
    }

    /**
     * POST [summary] 的 JSON 序列化体到 [url]（body 形如 `{"level":...,"hasDiff":...}`，
     * 见 [Reporter] 类注释）。
     *
     * - [url] 为 null / 空 / 全空白：no-op 直接返回。把 CLI 的 `--alert-url`（环境变量
     *   `ALERT_URL`，默认空）原样传进来即可，本地跑没配告警时天然跳过，不必自己判空。
     * - 超时：连接 5s、读 10s；Content-Type `application/json; charset=utf-8`，
     *   长度用 `setFixedLengthStreamingMode` 预声明。
     * - 响应码不在 2xx：内部抛 `IOException`，随即被同一条兜底吞掉。
     * - **任意 IO 异常本方法都不抛**：网络不通、DNS 失败、webhook 服务 5xx、读超时——
     *   一律静默。这是设计意图而非遗漏：对账结果已经落成 Excel 报告（[excel] 那边写失败会抛），
     *   告警链路只是通知手段，不该把整条对账流程带崩。
     *   代价是调用方拿不到投递失败的信号，需要「告警必达」时得另加监控或自己发请求。
     *
     * 真实接法（`OrderSyncCheck` 的写法，`alertUrl` 由 CLI / 环境变量注入）：
     *
     * ```kotlin
     * val summary = engine.summary()
     * report.excel("reports/order_sync_$dt.xlsx", summary)
     * if (alertUrl.isNotEmpty()) report.webhook(alertUrl, summary)   // 判空可省，webhook 自己会跳过
     * ```
     */
    fun webhook(url: String?, summary: DiffSummary): Unit {
        if (url.isNullOrBlank()) return
        val body = renderJson(summary).toByteArray(Charsets.UTF_8)
        runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
                try {
                    if (responseCode !in 200..299) throw IOException("webhook responded ${responseCode}")
                } finally {
                    disconnect()
                }
            }
        }
    }

    // --- 渲染层（不是 public API） ---

    /**
     * 报告工作簿内容：标准汇总块 + [body] 追加的自定义块。
     *
     * 汇总块的信息面沿用旧报告（`# Diff Summary` 标题 → 四行 kv 表 → `hasDiff = …`
     * → 有差异时补一句指向 L3 明细的说明），只是落点换成了 Excel 单元格。
     */
    private fun render(summary: DiffSummary, body: ExcelBuilder.() -> Unit): ExcelBuilder =
        ExcelBuilder().apply {
            heading("Diff Summary")
            table(
                headers = listOf("metric", "value"),
                rows = listOf(
                    listOf("level", summary.level.name),
                    listOf("src rows", summary.srcCount),
                    listOf("tgt rows", summary.tgtCount),
                    listOf("diff keys", summary.diffCount),
                ),
            )
            paragraph("hasDiff = ${summary.hasDiff}")
            if (summary.hasDiff) {
                paragraph("Top diff keys are reported in the linked L3 detail file.")
            }
            body()
        }

    /** 落盘：建父目录 + 覆盖写一份最小 OOXML 工作簿，见 [XlsxWriter]。 */
    private fun write(path: Path, builder: ExcelBuilder) {
        XlsxWriter.write(path, builder.sheets)
    }

    internal fun renderJson(summary: DiffSummary): String = buildString {
        append('{')
        append("\"level\":\"").append(summary.level.name).append("\",")
        append("\"srcCount\":").append(summary.srcCount).append(',')
        append("\"tgtCount\":").append(summary.tgtCount).append(',')
        append("\"diffCount\":").append(summary.diffCount).append(',')
        append("\"hasDiff\":").append(summary.hasDiff)
        append('}')
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS: Int = 5_000
        const val READ_TIMEOUT_MS: Int = 10_000
    }
}

/**
 * Excel 报告的表格拼装器：把「标题 / 段落 / 清单 / 表格」四种块按顺序落到工作簿上。
 *
 * **一个 [heading] 一张 sheet**：标题既当 sheet 名（Excel 标签页，非法字符会被替换、
 * 超 31 字符截断、重名自动加序号），又当该 sheet 的首行加粗标题——所以信息不会因为
 * sheet 名截断而丢失。标题之前的内容（一般是标准汇总块）落在默认 sheet「Report」上，
 * 第一个 [heading] 会给这张还没写内容的默认 sheet 改名，不会留下空 sheet。
 *
 * ```kotlin
 * Reporter().excel("reports/detail.xlsx") {
 *     heading("差异明细")                       // sheet「差异明细」
 *     table(listOf("列", "src", "tgt"), rows)   // 表头加粗，逐行落格
 * }
 * ```
 *
 * 单元格取值规则（[table] / [paragraph] / [bullets] 一致）：
 * - `null` 写成文本 `null`（不折叠成空单元格——对账报告里「没有值」和「值是空串」必须能区分）；
 * - [Number] 写成**数字单元格**（可在 Excel 里直接求和 / 排序；NaN / Infinity 退回文本，
 *   Excel 存不了这两个值）；[Boolean] 写成布尔单元格；其余走 `toString()`；
 * - `Long` / `BigDecimal` 超出 double 精确范围（±2^53 ／ 15 位有效数字）时退回文本，
 *   免得 64 位 checksum 被四舍五入（见 [ExcelBuilder.normalize]）。
 *
 * [table] 的参数校验走 fail fast（[IllegalArgumentException]），不静默产出错位表格：
 * [headers] 不能为空，每一行的列数必须等于 [headers] 的列数。
 */
class ExcelBuilder internal constructor() {

    /** 全部 sheet，顺序即落盘顺序；[Reporter] 直接交给 [XlsxWriter]。 */
    internal val sheets: MutableList<Sheet> = mutableListOf(Sheet(DEFAULT_SHEET_NAME))

    private var sheet: Sheet = sheets.first()
    private var headingSeen: Boolean = false
    private var sheetHasContent: Boolean = false

    /**
     * 开一节：新起一张 sheet 并把 [text] 写成它的首行加粗标题（sheet 名同源，见类注释）。
     *
     * 当前 sheet 还没写任何内容、也还没出现过标题时，直接给默认 sheet 改名，
     * 避免「完全自定义」写法留一张空的「Report」sheet。
     */
    fun heading(text: String): ExcelBuilder {
        if (headingSeen || sheetHasContent) {
            sheet = Sheet(uniqueSheetName(text, except = null)).also { sheets += it }
            sheetHasContent = false
        } else {
            sheet.name = uniqueSheetName(text, except = sheet)
        }
        headingSeen = true
        return writeRow(listOf(text), CellStyle.TITLE)
    }

    /** 一段说明文字，占一行。 */
    fun paragraph(text: String): ExcelBuilder = writeRow(listOf(text), CellStyle.BODY)

    /** 无序清单：每条目一行，行首加 `• `；空集合不产生任何行。 */
    fun bullets(items: Iterable<String>): ExcelBuilder {
        items.forEach { writeRow(listOf("• $it"), CellStyle.BODY) }
        return this
    }

    /**
     * 表格：一行加粗表头 + 每个数据行一行。
     *
     * ```kotlin
     * table(
     *     headers = listOf("列", "src", "tgt"),
     *     rows = listOf(listOf("amount", 10, 12)),
     * )
     * ```
     */
    fun table(headers: List<String>, rows: Iterable<Iterable<Any?>>): ExcelBuilder {
        require(headers.isNotEmpty()) { "excel table needs at least one column" }
        writeRow(headers, CellStyle.HEADER)
        rows.forEachIndexed { index, row ->
            val cells = row.toList()
            require(cells.size == headers.size) {
                "excel table row ${index + 1} has ${cells.size} cells but the table has ${headers.size} columns"
            }
            writeRow(cells, CellStyle.BODY)
        }
        return this
    }

    /** 追加一行到当前 sheet。 */
    private fun writeRow(values: List<Any?>, style: CellStyle): ExcelBuilder {
        sheet.rows += Row(values.map(::normalize), style)
        sheetHasContent = true
        return this
    }

    /**
     * 值归一：NaN / Infinity 不是 Excel 能表示的数，落回文本（否则工作簿被判损坏）；
     * 超出 double 精确范围的整数落回文本（见下）。
     *
     * `Long` / `BigDecimal` 那条是刻意的：对账里的 checksum / `SUM(hash(...))` 常年是 64 位整数，
     * 写成数字单元格会被 Excel 的 double 表示静默四舍五入（`123456789012345678` →
     * `123456789012345680`），报告上的数与实际数对不上比对账失败更糟。这类值不给「求和 / 排序」
     * 的便利，换显示值与落盘值都不失真；±2^53 以内的整数照常走数字单元格。
     */
    private fun normalize(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite()) value else value.toString()
        is Float -> if (value.isFinite()) value.toDouble() else value.toString()
        is Long -> if (value in -EXACT_INT_RANGE..EXACT_INT_RANGE) value else value.toString()
        is BigDecimal ->
            if (value.scale() <= MAX_EXACT_SCALE && value.abs() <= EXACT_INT_DECIMAL) {
                value
            } else {
                value.toPlainString()
            }
        else -> value
    }

    /**
     * 由标题 [raw] 算出一个能用的 sheet 名：非法字符换成空格、去掉首尾空格与单引号、
     * 非空、按码点截到 31 个字符（Excel 上限），重名（不区分大小写，Excel 就是这么算的）
     * 时后缀 ` (2)`、` (3)`……
     *
     * [except] 是「正在改名的那张 sheet」——它自己不算占用，否则 `heading("Report")` 之类
     * 与默认 sheet 同名的标题会被无谓地挤成 `Report (2)`。
     */
    private fun uniqueSheetName(raw: String, except: Sheet?): String {
        val base = sanitizeSheetName(raw)
        var candidate = base
        var index = 2
        while (true) {
            val taken = sheets.any { it !== except && it.name.equals(candidate, ignoreCase = true) }
            if (!taken) return candidate
            val suffix = " ($index)"
            candidate = truncate(base, SHEET_NAME_LIMIT - suffix.length).trimEnd() + suffix
            index++
        }
    }

    private fun sanitizeSheetName(raw: String): String {
        val cleaned = raw
            .map { if (it in INVALID_SHEET_NAME_CHARS) ' ' else it }
            .joinToString("")
            .trim()
            .trim('\'')
            .trim()
        val named = when {
            cleaned.isEmpty() -> DEFAULT_SHEET_NAME
            cleaned.equals(RESERVED_SHEET_NAME, ignoreCase = true) -> "${cleaned}_"
            else -> cleaned
        }
        return truncate(named, SHEET_NAME_LIMIT)
    }

    /** 按码点截断到 [max] 个字符，不切断代理对（否则会写出半个字符）。 */
    private fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        val out = StringBuilder(max)
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val width = Character.charCount(codePoint)
            if (out.length + width > max) break
            out.appendCodePoint(codePoint)
            i += width
        }
        return out.toString()
    }

    private companion object {
        /** 没被任何 [heading] 命名过的 sheet 的默认名字。 */
        const val DEFAULT_SHEET_NAME: String = "Report"

        /** Excel 保留的 sheet 名，写下去 Excel 会判文件损坏。 */
        const val RESERVED_SHEET_NAME: String = "History"

        /** Excel 对 sheet 名的长度上限（字符数）。 */
        const val SHEET_NAME_LIMIT: Int = 31

        /** Excel 明确禁止出现在 sheet 名里的字符。 */
        val INVALID_SHEET_NAME_CHARS: Set<Char> = setOf('\\', '/', '*', '?', ':', '[', ']')

        /** double 能精确表示的整数上界（2^53）；超过它的整数落成文本来保住精度。 */
        const val EXACT_INT_RANGE: Long = 1L shl 53

        val EXACT_INT_DECIMAL: BigDecimal = BigDecimal(EXACT_INT_RANGE)

        /** double 的十进制有效位数上界；`BigDecimal` 超过这个 scale 就意味着落 double 会丢位数。 */
        const val MAX_EXACT_SCALE: Int = 15
    }
}
