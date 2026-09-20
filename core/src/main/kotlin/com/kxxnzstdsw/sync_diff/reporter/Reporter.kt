package com.kxxnzstdsw.sync_diff.reporter

import com.kxxnzstdsw.sync_diff.core.DiffLevel
import com.kxxnzstdsw.sync_diff.core.DiffSummary
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 对账结果输出：Markdown 报告 + 可选 Webhook 告警（README §4.5 末尾那段链路）。
 *
 * 依赖只有 JDK 8 stdlib；不引 OkHttp / Ktor。
 *
 * [markdown] 落盘的报告原文（[renderMarkdown] 的真实输出）：
 *
 * ```markdown
 * # Diff Summary
 *
 * | metric | value |
 * |:---|---:|
 * | level | WARN |
 * | src rows | 100 |
 * | tgt rows | 99 |
 * | diff keys | 7 |
 *
 * > hasDiff = true
 *
 * Top diff keys are reported in the linked L3 detail file.
 * ```
 *
 * 表格固定四行（level / src rows / tgt rows / diff keys），级别单元格直接写 INFO / WARN / ERROR；
 * 末两段只在 `hasDiff == true` 时追加，无差异时报告到 `> hasDiff = false` 为止。汇总块本身不含
 * row-level 明细——差异明细、业务字段对照这类内容由 Check 自己追加，见下面的自定义内容。
 *
 * [webhook] POST 出去的 JSON body（[renderJson] 的真实输出）：
 *
 * ```json
 * {"level":"WARN","srcCount":100,"tgtCount":99,"diffCount":7,"hasDiff":true}
 * ```
 *
 * `level` 是 [DiffLevel] 的 `name`，其余字段名与 [DiffSummary] 的属性名一致（camelCase）。
 *
 * ## 自定义内容
 *
 * [markdown] 有三种写法，都是覆盖写、UTF-8、自动创建父目录：
 *
 * ```kotlin
 * // ① 只有标准汇总块
 * report.markdown("reports/order_sync_$dt.md", summary)
 *
 * // ② 汇总块 + Check 追加的自定义内容
 * report.markdown("reports/order_sync_$dt.md", summary) {
 *     heading("差异明细（抽样）")
 *     table(
 *         headers = listOf("层次", "列", "主键", "src", "tgt"),
 *         rows = rows.map { it.toCells() },          // Iterable<Iterable<Any?>>
 *         aligns = listOf(Align.LEFT, Align.LEFT, Align.LEFT, Align.RIGHT, Align.RIGHT),
 *     )
 *     paragraph("> 只列抽样命中的差异，未命中不代表一致。")
 * }
 *
 * // ③ 完全自定义，不写汇总块（报告长什么样全由 Check 决定）
 * report.markdown("reports/inspect_$dt.md") {
 *     heading("巡检结果", level = 1)
 *     bullets(listOf("分区齐全", "part 文件数与上游一致"))
 * }
 * ```
 *
 * 内容用 [MarkdownBuilder] 的 DSL 拼：`heading` / `paragraph` / `bullets` / `table` /
 * `code` / `raw`。其中 [MarkdownBuilder.table] 只是 [markdownTable] 的落笔形式——
 * 需要「先拿字符串、再决定写哪儿」（比如同一份内容既落盘又进 webhook body）时，
 * 直接用 `MarkdownBuilder().apply { … }.toString()`，或调 [markdownTable] 单出那张表。
 *
 * 一个 Reporter 实例无状态，可复用；所有方法只读 [summary]，自定义内容在调用点上就地渲染
 * （每次 [markdown] 都新建 [MarkdownBuilder]），并发调不同 [markdown] 不会互相污染。
 */
class Reporter {

    /**
     * 写一份 Markdown 报告到 [path]，**覆盖**已存在的文件（不是追加）。
     *
     * 父目录不存在会自动创建（`Files.createDirectories`）；[path] 没有父目录（裸文件名）时
     * 写到当前目录。编码 UTF-8，换行是 LF。内容结构见 [Reporter] 类注释。
     *
     * ```kotlin
     * val summary: DiffSummary = engine.summary()
     * Reporter().markdown("reports/order_sync_$dt.md", summary)
     * ```
     *
     * 与 [webhook] 不同，本方法**会抛** IO 异常（[java.io.IOException] / 权限、磁盘满等）：
     * 报告是对账的交付物，写不出来就应该失败，而不是静默吞掉。
     */
    fun markdown(path: String, summary: DiffSummary): Unit = markdown(Paths.get(path), summary)

    /** 同 [markdown]，但接收 [Path]；方便测试里用 `Files.createTempFile`。 */
    fun markdown(path: Path, summary: DiffSummary) {
        write(path, render(summary) {}.toString())
    }

    /**
     * 标准汇总块 + [body] 追加的自定义内容（表格 / 说明 / 代码块……），写盘语义同 [markdown]。
     *
     * ```kotlin
     * report.markdown("reports/wilson_$dt.md", summary) {
     *     heading("差异明细（抽样）")
     *     table(listOf("列", "主键", "src", "tgt"), cells, aligns = ALIGNS)
     * }
     * ```
     *
     * [body] 在汇总块之后调用，可以挂任意多段；抛异常同样会中断写盘（内容没准备好就不该产出报告）。
     */
    fun markdown(path: String, summary: DiffSummary, body: MarkdownBuilder.() -> Unit): Unit =
        markdown(Paths.get(path), summary, body)

    /** 同 [markdown] 的三参版本，但接收 [Path]。 */
    fun markdown(path: Path, summary: DiffSummary, body: MarkdownBuilder.() -> Unit) {
        write(path, render(summary, body).toString())
    }

    /**
     * 完全自定义的报告：不写标准汇总块，内容全部由 [body] 决定。写盘语义同 [markdown]。
     *
     * 适合输出与 [DiffSummary] 无关的结果（巡检结论、上游表结构、耗时分布……）：
     *
     * ```kotlin
     * report.markdown("reports/inspect_$dt.md") {
     *     heading("巡检结果", level = 1)
     *     bullets(listOf("分区齐全", "part 文件数与上游一致"))
     * }
     * ```
     *
     * 注意这样写出来的报告没有 level / 行数 / 差异数，webhook 那边仍应单独发 [DiffSummary]。
     */
    fun markdown(path: String, body: MarkdownBuilder.() -> Unit): Unit = markdown(Paths.get(path), body)

    /** 同 [markdown] 的两参 lambda 版本，但接收 [Path]。 */
    fun markdown(path: Path, body: MarkdownBuilder.() -> Unit) {
        write(path, MarkdownBuilder().apply(body).toString())
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
     *   一律静默。这是设计意图而非遗漏：对账结果已经落成 Markdown 报告（[markdown] 那边
     *   写失败会抛），告警链路只是通知手段，不该把整条对账流程带崩。
     *   代价是调用方拿不到投递失败的信号，需要「告警必达」时得另加监控或自己发请求。
     *
     * 真实接法（`OrderSyncCheck` 的写法，`alertUrl` 由 CLI / 环境变量注入）：
     *
     * ```kotlin
     * val summary = engine.summary()
     * report.markdown("reports/order_sync_$dt.md", summary)
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

    // --- 渲染层（公开便于单测；不是 public API） ---

    internal fun renderMarkdown(summary: DiffSummary): String = render(summary) {}.toString()

    /**
     * 报告文档：标准汇总块 + [body] 追加的自定义块。
     *
     * 汇总块的字节输出沿用旧实现（`# Diff Summary` 标题 → 四行 kv 表 → `> hasDiff = …`
     * → 有差异时补一句指向 L3 明细的说明），因此 [renderMarkdown] 的输出与历史报告一致。
     */
    private fun render(summary: DiffSummary, body: MarkdownBuilder.() -> Unit): MarkdownBuilder =
        MarkdownBuilder().apply {
            heading("Diff Summary", level = 1)
            table(
                headers = listOf("metric", "value"),
                rows = listOf(
                    listOf("level", summary.level.badge()),
                    listOf("src rows", summary.srcCount),
                    listOf("tgt rows", summary.tgtCount),
                    listOf("diff keys", summary.diffCount),
                ),
                aligns = listOf(Align.LEFT, Align.RIGHT),
            )
            paragraph("> hasDiff = ${summary.hasDiff}")
            if (summary.hasDiff) {
                paragraph("Top diff keys are reported in the linked L3 detail file.")
            }
            body()
        }

    /** 落盘：建父目录 + UTF-8 覆盖写。三个 [markdown] 入口共用。 */
    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent ?: Paths.get("."))
        Files.write(path, content.toByteArray(Charsets.UTF_8))
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

    private fun DiffLevel.badge(): String = when (this) {
        DiffLevel.INFO -> "INFO"
        DiffLevel.WARN -> "WARN"
        DiffLevel.ERROR -> "ERROR"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS: Int = 5_000
        const val READ_TIMEOUT_MS: Int = 10_000
    }
}

/**
 * Markdown 表格列对齐。[markdownTable] / [MarkdownBuilder.table] 的 `aligns` 参数用。
 *
 * 对应对齐标记：`---` / `:---` / `:---:` / `---:`。多数渲染器把它当排版建议而不是硬约束，
 * 所以选错只是不美观，不会让表格失效。
 */
enum class Align(internal val marker: String) {
    /** `---`：不声明对齐，交给渲染器按内容决定。 */
    DEFAULT("---"),

    /** `:---`：左对齐；文字列、主键列的常规选择。 */
    LEFT(":---"),

    /** `:---:`：居中。 */
    CENTER(":---:"),

    /** `---:`：右对齐；数字列（计数、金额）的常规选择。 */
    RIGHT("---:"),
}

/**
 * 渲染一张 Markdown 表格，返回**不含尾换行**的多行字符串（行之间用 `\n`）。
 *
 * 输出形状（`aligns = [Align.LEFT, Align.RIGHT]`）：
 *
 * ```markdown
 * | metric | value |
 * |:---|---:|
 * | level | WARN |
 * ```
 *
 * 单元格渲染规则：
 * - `null` 写成 `null`（不折叠成空串——对账报告里「没有值」和「值是空串」必须能区分）。
 * - 其它值走 `toString()`。
 * - `|` 转义成 `\|`，换行折叠成 `<br>`：Markdown 表格的一行就是一行，不处理会把表格切碎。
 *
 * 参数校验走 fail fast（[IllegalArgumentException]），不静默产出错位表格：
 * - [headers] 不能为空；
 * - [aligns] 要么为空（全部 [Align.DEFAULT]），要么与 [headers] 等长；
 * - 每一行的列数必须等于 [headers] 的列数。
 *
 * [rows] 只遍历一次：一次性视图（不支持二次遍历的实现）也能安全传入。
 */
fun markdownTable(
    headers: List<String>,
    rows: Iterable<Iterable<Any?>>,
    aligns: List<Align> = emptyList(),
): String {
    require(headers.isNotEmpty()) { "markdown table needs at least one column" }
    require(aligns.isEmpty() || aligns.size == headers.size) {
        "aligns has ${aligns.size} entries but the table has ${headers.size} columns"
    }
    val alignment: List<Align> = aligns.ifEmpty { List(headers.size) { Align.DEFAULT } }

    val lines = ArrayList<String>(8)
    lines += headers.joinToString(separator = " | ", prefix = "| ", postfix = " |") { escapeCell(it) }
    // 分隔行不带空格填充：与表头 / 数据行的 `| a | b |` 风格不同，但这是 Markdown 的常规写法
    // （`|:---|---:|`），也是历史报告里汇总表的样子——保持字节不变，别动。
    lines += "|" + alignment.joinToString("|") { it.marker } + "|"
    rows.forEachIndexed { index, row ->
        val cells = row.toList()
        require(cells.size == headers.size) {
            "markdown table row ${index + 1} has ${cells.size} cells but the table has ${headers.size} columns"
        }
        lines += cells.joinToString(separator = " | ", prefix = "| ", postfix = " |") { escapeCell(it.toString()) }
    }
    return lines.joinToString("\n")
}

/** 单元格转义：`|` 不转义会切断单元格，换行不折叠会把表格行拆成两行。 */
private fun escapeCell(text: String): String =
    text.replace("|", "\\|").replace("\r\n", "\n").replace("\n", "<br>")

/**
 * Markdown 文档拼装器：Check 输出自定义报告内容时用它，不用手写竖线与空行。
 *
 * 每个方法追加一个**块**（块之间恰好隔一个空行），返回 `this` 以便链式调用；
 * [toString] 收尾时去掉尾部空行、保留一个 `\n`，空文档则是空串。拼接规则与
 * [Reporter] 的标准汇总块一致，所以两种内容混排不会出现双空行或粘连。
 *
 * ```kotlin
 * val md = MarkdownBuilder().apply {
 *     heading("差异明细")
 *     table(listOf("列", "src", "tgt"), rows, aligns = listOf(Align.LEFT, Align.RIGHT, Align.RIGHT))
 *     paragraph("样本只覆盖抽样命中的主键。")
 * }.toString()
 * ```
 *
 * 块内容一律**原样落笔**（除表格单元格的转义）：[paragraph] / [raw] 里可以写任意 Markdown
 * （引用、链接、HTML），本类不做二次加工，也不会把 `*` `_` 之类转义掉。
 */
class MarkdownBuilder {

    private val out = StringBuilder()

    /**
     * 标题：`#` × [level] + 空格 + [text]。
     *
     * [level] 取 1..6（对应 `#` 到 `######`），越界抛 [IllegalArgumentException]：
     * 7 个 `#` 不是标题，行内渲染成普通段落，报告会静默变形。
     */
    fun heading(text: String, level: Int = 2): MarkdownBuilder {
        require(level in 1..6) { "markdown heading level must be in 1..6, got $level" }
        return block("#".repeat(level) + " " + text)
    }

    /** 一个段落；[text] 内部可以有换行（原样保留），但整段算一个块。 */
    fun paragraph(text: String): MarkdownBuilder = block(text)

    /** 无序列表：每条目一行 `- ` 前缀；空集合不产生内容（也不会留下空行）。 */
    fun bullets(items: Iterable<String>): MarkdownBuilder {
        val body = items.joinToString("\n") { "- $it" }
        return if (body.isEmpty()) this else block(body)
    }

    /**
     * 表格，渲染细节见 [markdownTable]（含转义与列数校验）。
     *
     * ```kotlin
     * table(
     *     headers = listOf("列", "src", "tgt"),
     *     rows = listOf(listOf("amount", 10, 12)),
     *     aligns = listOf(Align.LEFT, Align.RIGHT, Align.RIGHT),
     * )
     * ```
     */
    fun table(
        headers: List<String>,
        rows: Iterable<Iterable<Any?>>,
        aligns: List<Align> = emptyList(),
    ): MarkdownBuilder = block(markdownTable(headers, rows, aligns))

    /** 围栏代码块；[language] 为空就写不带语言标注的围栏（首行直接换行）。 */
    fun code(text: String, language: String = ""): MarkdownBuilder =
        block("```" + language + "\n" + text + "\n```")

    /** 原样追加一段 Markdown：留给本类没封装的结构（嵌套列表、HTML、表格外的自定义排版）。 */
    fun raw(markdown: String): MarkdownBuilder = block(markdown)

    /** 完整文档：块间空行已就位，末尾恰好一个 `\n`；空文档返回空串。 */
    override fun toString(): String =
        if (out.isEmpty()) "" else out.toString().trimEnd('\n') + "\n"

    /** 追加一个块（内容 + 空行）。空内容直接忽略，避免留下连续空行。 */
    private fun block(text: String): MarkdownBuilder {
        if (text.isEmpty()) return this
        out.append(text).append("\n\n")
        return this
    }
}