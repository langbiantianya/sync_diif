package com.kxxnzstdsw.sync_diff.reporter

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 行样式；[index] 就是 `styles.xml` 里 `cellXfs` 的下标。
 *
 * 三种样式对应 [ExcelBuilder] 的三个入口：[BODY] 正文（自动换行、顶对齐）、[TITLE] sheet
 * 首行的加粗标题、[HEADER] 表格表头（加粗 + 灰底）。
 */
internal enum class CellStyle(val index: Int) {
    BODY(0),
    TITLE(1),
    HEADER(2),
}

/** 一行：单元格原值（`Any?`，序列化时判定类型）与整行共用的样式。 */
internal class Row(val cells: List<Any?>, val style: CellStyle)

/**
 * 一张 sheet：名字（已清洗 / 去重）+ 行序列（按写入顺序）。
 *
 * [name] 可变：第一个 [ExcelBuilder.heading] 会给还没写过内容的默认 sheet 改名，
 * 免得「完全自定义」的报告里留一张空 sheet。
 */
internal class Sheet(var name: String, val rows: MutableList<Row> = mutableListOf())

/**
 * 最小 xlsx（SpreadsheetML / OOXML）序列化器：把 [Sheet] 列表写成一个能被 Excel 打开的工作簿。
 *
 * 不引 Excel 库（POI 会带 xmlbeans / commons-* / log4j-api 一整串依赖，且会撞上
 * `app` 的 `failOnDuplicateEntries = true`），只拼这份报告实际用到的最小子集：
 *
 * ```
 * [Content_Types].xml                     包内容类型
 * _rels/.rels                             包级关系：officeDocument → xl/workbook.xml
 * xl/workbook.xml                         sheet 清单（名字 + rId）
 * xl/_rels/workbook.xml.rels              rId → worksheets/sheetN.xml、styles.xml
 * xl/styles.xml                           3 种 cellXfs（正文 / 标题 / 表头）
 * xl/worksheets/sheet1.xml … sheetN.xml   sheetData
 * ```
 *
 * 刻意不做的部分（用不到就不写，少一处能写错的地方）：sharedStrings（单元格一律
 * `t="inlineStr"`）、主题 / docProps / 自动过滤 / 图表 / 公式 / 列宽。
 *
 * 单元格取值规则见 [ExcelBuilder]；这里只负责转义与类型标签：
 * - 文本 → `t="inlineStr"` + `<is><t xml:space="preserve">`（保留首尾空格与换行），超过
 *   32767 字符（Excel 的单格上限）截断并补 `...`，否则整份工作簿会被判损坏；
 * - 数字 → 裸 `<v>`（整数不带 `.0`），Excel 按数值处理，可直接求和 / 排序；
 * - 布尔 → `t="b"` + `1` / `0`；
 * - 非法字符（XML 1.0 不允许的控制字符）直接丢弃——留着会让 Excel 判定文件损坏。
 *
 * 写入是覆盖写、自动建父目录；父目录不存在 / 无权限 / 磁盘满照样抛 IO 异常。
 */
internal object XlsxWriter {

    /** 至少一张 sheet（Excel 不接受没有任何 worksheet 的工作簿）。 */
    fun write(path: Path, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "an xlsx workbook needs at least one sheet" }
        Files.createDirectories(path.parent ?: Paths.get("."))
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(path))).use { zip ->
            // `[Content_Types].xml` 惯例放第一个条目
            zip.put("[Content_Types].xml", contentTypes(sheets.size))
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("xl/workbook.xml", workbook(sheets))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            zip.put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { index, sheet ->
                zip.put("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet))
            }
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append(XML_DECLARATION)
        append("<Types xmlns=\"$PACKAGE_CONTENT_TYPES_NS\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"$WORKBOOK_CONTENT_TYPE\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"$STYLES_CONTENT_TYPE\"/>")
        for (number in 1..sheetCount) {
            append("<Override PartName=\"/xl/worksheets/sheet$number.xml\" ContentType=\"$WORKSHEET_CONTENT_TYPE\"/>")
        }
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append(XML_DECLARATION)
        append("<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$DOC_RELS_NS\"><sheets>")
        sheets.forEachIndexed { index, sheet ->
            append("<sheet name=\"")
                .append(escape(sheet.name))
                .append("\" sheetId=\"")
                .append(index + 1)
                .append("\" r:id=\"")
                .append(sheetRelId(index))
                .append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"$PACKAGE_RELS_NS\">")
        for (number in 1..sheetCount) {
            append("<Relationship Id=\"")
                .append(sheetRelId(number - 1))
                .append("\" Type=\"$WORKSHEET_REL_TYPE\" Target=\"worksheets/sheet$number.xml\"/>")
        }
        append("<Relationship Id=\"")
            .append(stylesRelId(sheetCount))
            .append("\" Type=\"$STYLES_REL_TYPE\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun worksheet(sheet: Sheet): String = buildString {
        append(XML_DECLARATION)
        append("<worksheet xmlns=\"$MAIN_NS\"><sheetData>")
        sheet.rows.forEachIndexed { index, row -> append(row(index + 1, row)) }
        append("</sheetData></worksheet>")
    }

    private fun row(number: Int, row: Row): String = buildString {
        append("<row r=\"").append(number).append("\">")
        row.cells.forEachIndexed { index, value ->
            val reference = columnName(index) + number
            val style = row.style.index
            when (value) {
                null -> append("<c r=\"").append(reference).append("\" s=\"").append(style)
                    .append("\" t=\"inlineStr\"><is><t>null</t></is></c>")
                is Boolean -> append("<c r=\"").append(reference).append("\" s=\"").append(style)
                    .append("\" t=\"b\"><v>").append(if (value) 1 else 0).append("</v></c>")
                is Number -> append("<c r=\"").append(reference).append("\" s=\"").append(style)
                    .append("\"><v>").append(number(value.toDouble())).append("</v></c>")
                else -> append("<c r=\"").append(reference).append("\" s=\"").append(style)
                    .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(escape(cellText(value.toString()))).append("</t></is></c>")
            }
        }
        append("</row>")
    }

    /**
     * 单元格文本：Excel 单个单元格上限 32767 个字符，超了工作簿会被判损坏，所以截断——
     * 但留 [TRUNCATION_MARKER] 尾巴，别让「取值被截」看起来像「取值本来就到这儿」。
     *
     * 对账列里出现超长文本是可能的（`application_form` / `group_info` 这类 JSON 列），
     * 32K 前缀足够看出差异了。
     */
    private fun cellText(text: String): String =
        if (text.length <= MAX_CELL_CHARS) {
            text
        } else {
            text.take(MAX_CELL_CHARS - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
        }

    /**
     * 数字字面量：整数值写成 `100`（不是 `100.0`），其余走 [Double.toString] 的十进制 / 科学计数
     * 记法（`<v>` 收 `xsd:double`，两种都合法）。
     *
     * 调用方保证值有限（NaN / Infinity 不可能出现在这里，[ExcelBuilder] 已经把它们转成文本）。
     */
    private fun number(value: Double): String =
        if (value == Math.floor(value) && Math.abs(value) <= Long.MAX_VALUE.toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    /** 0 → `A`、25 → `Z`、26 → `AA`：Excel 的列名（双射进制，没有 0 位）。 */
    private fun columnName(index: Int): String {
        var remaining = index + 1
        val name = StringBuilder()
        while (remaining > 0) {
            val digit = (remaining - 1) % 26
            name.append('A' + digit)
            remaining = (remaining - 1) / 26
        }
        return name.reverse().toString()
    }

    private fun sheetRelId(index: Int): String = "rId${index + 1}"

    private fun stylesRelId(sheetCount: Int): String = "rId${sheetCount + 1}"

    /**
     * XML 文本转义：`&` `<` `>` 与两种引号都转（引号在文本节点里本可不转，转了一处不漏）。
     * XML 1.0 不允许的控制字符（除 `\t` `\n` `\r`）直接丢弃。
     *
     * 落单的代理项（unpaired surrogate）同样丢弃：它不是合法 XML 字符，写进去整份工作簿
     * 会被判损坏——一个畸形字符毁掉所有 sheet 不划算，与丢控制字符是同一取舍。
     * 合法代理对按码点整体写出（不拆成半个字符）。
     */
    private fun escape(text: String): String {
        val escaped = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            when {
                char == '&' -> escaped.append("&amp;")
                char == '<' -> escaped.append("&lt;")
                char == '>' -> escaped.append("&gt;")
                char == '"' -> escaped.append("&quot;")
                char == '\'' -> escaped.append("&apos;")
                width == 1 && Character.isSurrogate(char) -> Unit
                char == '\t' || char == '\n' || char == '\r' || char >= ' ' -> escaped.appendCodePoint(codePoint)
                else -> Unit
            }
            index += width
        }
        return escaped.toString()
    }

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val PACKAGE_RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val PACKAGE_CONTENT_TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val DOC_RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WORKSHEET_REL_TYPE = "$DOC_RELS_NS/worksheet"
    private const val STYLES_REL_TYPE = "$DOC_RELS_NS/styles"
    private const val WORKBOOK_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    private const val WORKSHEET_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    private const val STYLES_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"

    /** Excel 单个单元格的字符数上限；超了整份工作簿会被判损坏。 */
    private const val MAX_CELL_CHARS = 32_767

    /** 截断后的尾巴，提示这一格不是完整取值。 */
    private const val TRUNCATION_MARKER = "..."

    private const val ROOT_RELS: String = XML_DECLARATION +
        "<Relationships xmlns=\"$PACKAGE_RELS_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$DOC_RELS_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    /**
     * `cellXfs` 的下标与 [CellStyle.index] 一一对应：0 正文、1 标题、2 表头。
     *
     * `fonts` / `fills` 不写 `<color>`（不引主题部件，黑色即默认），`fills` 的前两项按约定
     * 是 `none` / `gray125`。
     */
    private const val STYLES: String = XML_DECLARATION +
        "<styleSheet xmlns=\"$MAIN_NS\">" +
        "<fonts count=\"3\">" +
        "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"13\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "</fonts>" +
        "<fills count=\"3\">" +
        "<fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCDCDC\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "</fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"3\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">" +
        "<alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
        "</cellXfs>" +
        "</styleSheet>"
}
