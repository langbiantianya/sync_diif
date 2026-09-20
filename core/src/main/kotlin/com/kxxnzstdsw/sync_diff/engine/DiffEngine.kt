package com.kxxnzstdsw.sync_diff.engine

import com.kxxnzstdsw.sync_diff.core.*

/**
 * 三档差异（L1 聚合 / L2 主键集合 / L3 行级）的执行体。
 *
 * 三档的入口、输入与代价：
 *
 * | 档位 | 入口 | 输入 | 额外内存 | 用途 |
 * |:---|:---|:---|:---|:---|
 * | L1 | [aggregate] | `List<Row>`，一行一个分区 | O(分区数)，两侧全量进内存 | count / sum / checksum 粗筛 |
 * | L2 | [keySet] | `Sequence<Row>`，只含主键列 | O(对侧键数)，两次差集合计 O(两端键数) | 主键集合对称差，找缺失行 |
 * | L3 | [compareRows] | `Sequence<Row>`，整行 | O(src 行数 + tgt 去重键数) | 逐行逐字段细比，定位漂移列 |
 *
 * 推荐调用顺序：**L1 不平就不必下钻 L3**。L1 每个分区只读一行聚合结果，代价最低，
 * 先看 count / sum / checksum 是否对上；对上了说明两端数据大体一致，可以落报告收工。
 * L1 有差异再跑 L2 收敛到「是哪几个主键」，最后只对这批主键跑 L3 看「哪个字段不同」。
 * 反过来的话 L3 要逐行拉全量数据，代价高一个量级，而绝大多数行本来就没问题。
 *
 * 三档共用同一套 [FieldRules]（[aggregate] 与 [compare] 的 `rules` 参数）：
 * 同一列的容忍度在 L1 / L3 必须一致，否则会出现「L1 报差异、L3 查不出差异」的矛盾结论。
 * 典型接法见 [aggregate] 的示例。
 *
 * 设计要点：
 *
 * - **惰性贯穿**：[keySet] 与 [compareRows] 接收 `Sequence<Row>`，全程不把流抽进 `List`。
 *   L3 的差异 [Sequence] 也是惰性的；调用方需要 [DiffSummary] 时再 `toList` / fold。
 *   注意惰性的代价：这些序列上的副作用（计数器累加）同样只在消费时发生。
 * - **运行期计数器**：每次 [aggregate] / [keySet] / [compareRows] 跑完，更新
 *   [aggDiffCount] / [keyDiffCount] / [rowDiffCount] / [srcRowCount] / [tgtRowCount]；
 *   [summary] 把它们折叠成 [DiffSummary]。
 * - **错误处理**：SQL / IO 异常由 Connector 内部用
 *   [com.kxxnzstdsw.sync_diff.core.guard] 包成
 *   [com.kxxnzstdsw.sync_diff.core.ConnectorError]，引擎自身不抛裸 [Throwable]，
 *   也不用 `try / catch` 做控制流。
 *
 * 不是线程安全的：一个 Check 跑一次就用一个实例；并发场景每条 [Check] 单独 new。
 */
class DiffEngine {

    // -------- 运行期计数器（accumulate / summary 的来源） --------

    /**
     * L1 差异主键数：[aggregate] 每次调用累加其返回中 [DiffRow.hasDiff] 为 true 的条数。
     *
     * 口径是「主键条数」而不是「[FieldDiff] 条数」：一个分区三列都不一致也只算 1。
     * 与 [DiffSummary.accumulate] 的计数口径一致。
     */
    var aggDiffCount: Long = 0L
        private set

    /**
     * L2 不对称主键数：[keySet] 输出的序列每 `yield` 一个键就 ++（src 独有与 tgt 独有都算）。
     *
     * 累加发生在 `yield` 处，所以**不消费序列就永远是 0**——`keySet(...)` 只建序列、
     * 不 `toList()` / `forEach`，这档差异在 [summary] 里会整档丢失。该计数未去重。
     */
    var keyDiffCount: Long = 0L
        private set

    /** L3 差异主键数：[compareRows] 里 [check] 命中非 [FieldDiff.Equal]，或整行缺失时各 ++。 */
    var rowDiffCount: Long = 0L
        private set

    /**
     * L3 源端行数：[compareRows] 把 `src` 装进 `HashMap` 时每读一行 ++，与是否命中 tgt 无关。
     *
     * 该计数**不来自** [aggregate]：只跑 L1 时它是 0，[summary] 的 `srcCount` 也是 0。
     */
    var srcRowCount: Long = 0L
        private set

    /** L3 目标端行数：[compareRows] 消费 `tgt` 时每读一行 ++（缺行 / 命中都算）。 */
    var tgtRowCount: Long = 0L
        private set

    // -------- L1：聚合比对 --------

    /**
     * L1：两端聚合行按 [keys] 对齐，逐字段比出 [DiffRow]。用于 count / sum / checksum 粗筛。
     *
     * [rules] 不要省：L1 的 sum 类度量列若按 `==` 精确比对，行级允许的数值漂移会在聚合层
     * 被误报成差异。把与 L3 同一套 [FieldRules] 传进来，两档口径才一致（见类注释的调用顺序）：
     *
     * ```kotlin
     * val srcAgg = src.query(
     *     "SELECT '$dt' AS dt, COUNT(*) AS c, SUM(amount) AS s FROM read_parquet('$path')"
     * )
     * val tgtAgg = tgt.query(
     *     "SELECT dt, COUNT(*) AS c, SUM(amount) AS s FROM ods.orders WHERE dt = '$dt' GROUP BY dt"
     * )
     * val aggDiffs = engine.aggregate(srcAgg, tgtAgg, keys = listOf("dt")) {
     *     field("s") { tolerance(abs = 0.01) }   // SUM(amount)，与 L3 的 amount 同容忍度
     *     // c / h（count / checksum）不声明，默认 ==
     * }
     * ```
     *
     * 对齐与字段范围：
     *
     * - 两侧都没有该主键：跳过，不 emit。
     * - 只有一侧有：逐 `keys` 列 emit [FieldDiff.Missing]，`side` 指向缺的那一侧。
     * - 两侧都有：字段范围是 `s.columns ∪ t.columns` 的并集，逐列按 [rules] 判定，
     *   未声明规则的列退回 `==`；一侧缺列同样 emit [FieldDiff.Missing]。
     *
     * 返回值保留 [FieldDiff.Equal] 占位，便于 Reporter 画完整表格。
     *
     * 计数：[aggDiffCount] 累加的是返回中 [DiffRow.hasDiff] 为 true 的**主键条数**
     * （不是 [FieldDiff] 条数）。本方法不会更新 [srcRowCount] / [tgtRowCount]。
     *
     * [keys] 为空会 `require` 失败。输入是 `List`：聚合结果一行一分区，本身很小，
     * 不需要流式；两侧各自物化成 `Map`，额外内存 O(分区数)。
     */
    fun aggregate(
        src: List<Row>,
        tgt: List<Row>,
        keys: List<String>,
        rules: FieldRules.() -> Unit = {},
    ): List<DiffRow> {
        require(keys.isNotEmpty()) { "aggregate: keys must not be empty" }
        val fieldRules = FieldRules().apply(rules)
        val srcByKey: Map<Map<String, Any?>, Row> = src.associateBy { keyOf(it, keys) }
        val tgtByKey: Map<Map<String, Any?>, Row> = tgt.associateBy { keyOf(it, keys) }
        val allKeys = srcByKey.keys + tgtByKey.keys

        val rows = allKeys.map { k ->
            val s = srcByKey[k]
            val t = tgtByKey[k]
            val diffs = diffPair(s, t, keys, fieldRules)
            DiffRow(key = k, diffs = diffs)
        }
        // 计数：非 Equal 的算差异主键；和 [DiffSummary.accumulate] 的口径一致。
        aggDiffCount += rows.count { it.hasDiff }
        return rows
    }

    // -------- L2：主键集合差集 --------

    /**
     * L2：两端按 [key] 取主键，输出**只在单侧出现**的键（对称差）。
     *
     * ```kotlin
     * val onlyOneSide = engine.keySet(
     *     src = srcConn.stream("SELECT order_id FROM read_parquet('$path')"),
     *     tgt = tgtConn.stream("SELECT order_id FROM ods.orders WHERE dt = '$dt'"),
     *     key = "order_id",
     * )
     * // 序列是惰性的：不消费就不计 keyDiffCount
     * val keys: List<String> = onlyOneSide.toList()
     * ```
     *
     * **两侧各只扫一遍**：方法内部把每一侧的键收进 `LinkedHashSet`，再做双向成员判定。
     * 不要改成方向相反的两个差额（`srcKeys - tgtKeys` 与 `tgtKeys - srcKeys`）——
     * `Sequence.minus` 会物化"被减号右边"的那一侧，两个方向合起来正好把两侧各跑两遍，
     * 对数仓来说就是 4 次全表扫描。
     *
     * 内存：O(两端**去重后**键数之和)。这是哈希法做对称差的固有代价；键基数极大时，
     * 应在 SQL 层先用分区 / 哈希桶条件（`WHERE hash(k) % 100 < n`）把参与对齐的量压下来。
     *
     * 时机：两个 `LinkedHashSet` 在**本方法返回前**就已建好，因此 `keySet(...)` 一调用，
     * 两端的 `Sequence` 当场被拉完（即使之后从不消费返回值）；而 [keyDiffCount] 的累加
     * 发生在 `yield` 处，只在**消费**时发生。两者时机不同，别混淆。
     *
     * 输出**去重、不排序**：同一侧重复出现的主键只输出一次（L2 是"主键集合"的差集），
     * 顺序是"先 src 独有、再 tgt 独有"，各自保持首次出现顺序。按主键有序输出需在 SQL 里
     * 加 `ORDER BY`。
     *
     * 陷阱：[keyDiffCount] 在序列被**消费**时逐条累加，所以拿到序列不消费（不 `toList()`、
     * 不 `forEach`）就永远是 0，[summary] 会漏掉这档差异。
     */
    fun keySet(src: Sequence<Row>, tgt: Sequence<Row>, key: String): Sequence<String> {
        // 两侧各物化一次：用 `Sequence.minus` 做两次差额会把每一侧都再跑一遍
        // （`minus` 会物化对侧，两个方向的差额正好把两侧各读两遍）。
        val srcKeys = src.mapNotNullTo(LinkedHashSet()) { it[key]?.toString() }
        val tgtKeys = tgt.mapNotNullTo(LinkedHashSet()) { it[key]?.toString() }
        return sequence {
            srcKeys.forEach {
                if (it !in tgtKeys) {
                    keyDiffCount++
                    yield(it)
                }
            }
            tgtKeys.forEach {
                if (it !in srcKeys) {
                    keyDiffCount++
                    yield(it)
                }
            }
        }
    }

    // -------- L3：单主键行级比对 --------

    /**
     * L3：给一对具体行 [s] / [t]，按 [rules] DSL 比出每列的 [FieldDiff]。单主键的字段级细比。
     *
     * 字段范围是 `s.columns ∪ t.columns` 的并集，避免一侧缺列被静默忽略：
     *
     * - 列声明了规则：规则返回 true → [FieldDiff.Equal]，false → [FieldDiff.Mismatch]（带
     *   `expected` = s 侧值、`actual` = t 侧值）。
     * - 未声明规则的列：退回 `==` 结构相等。
     * - 一侧缺列：emit [FieldDiff.Missing]，`side` 指向缺列的那一侧。
     *
     * [FieldDiff.Equal] 保留在返回值里（不过滤），便于 Reporter 画完整表格；
     * 只想看差异时用 `filter { it !is FieldDiff.Equal }`。
     *
     * ```kotlin
     * val diffs = engine.compare(s, t) {
     *     field("amount")       { tolerance(abs = 0.01) }
     *     field("status")       { ignore() }
     *     field("updated_at")   { toUtc }                        // toUtc 是属性，不是函数
     *     field("phone")        { phoneNumber() }                // 自定义扩展规则
     *     field("amount_taxed") { amountWithTax(BigDecimal("1.06")) }
     * }
     * val mismatches = diffs.filterIsInstance<FieldDiff.Mismatch>()
     * ```
     *
     * 等价的中缀写法：`field("amount") by tolerance(abs = 0.01)`。
     *
     * 本方法**不读写任何计数器**（单行比对不属于任何一档的统计口径），
     * 大批量比对走 [compareRows]，后者内部就是按主键逐行调本方法。
     */
    fun compare(s: Row, t: Row, rules: FieldRules.() -> Unit): List<FieldDiff> {
        val fr = FieldRules().apply(rules)
        return compareInternal(s, t, fr)
    }

    // -------- L3：流式行级比对 --------

    /**
     * L3 流式入口：把两侧 [Row] 流按 [key] 对齐逐行比。
     *
     * 内存代价：先把 `src` 整条流装进 `HashMap<key, Row>`（O(src 行数)），另外维护一个
     * `seen` 集合记住 tgt 已出现的键（O(tgt 去重键数)），用于回扫 src 的剩余键。
     * 适合「一侧可控、一侧很大」的场景；两侧都上亿行时先用 SQL 的分区 / 哈希桶条件压量。
     *
     * 对齐与输出 `Sequence<Pair<key, List<FieldDiff>>>`：
     *
     * - `tgt` 有、`src` 没有的键：emit `Missing("<row>", Side.SRC)`——`"<row>"` 是占位字段名
     *   （整行缺失，没有具体列可比），读的时候按 `side` 判断哪端缺。
     * - `tgt` 流消费完后，`src` 里没被命中的键：emit `Missing("<row>", Side.TGT)`。
     * - 两侧都命中：调 [check] 比字段，原样返回它的结果（含 [FieldDiff.Equal]）。
     *
     * 重复键保留**首次出现**的那一行：`src` 建索引时同名键后来者被丢弃（README §4 约定）；
     * `tgt` 侧重复键则各自 emit，是否去重由调用方决定。
     *
     * ```kotlin
     * engine.compareRows(
     *     src = srcConn.stream("SELECT order_id, amount, status FROM read_parquet('$path')"),
     *     tgt = tgtConn.stream("SELECT order_id, amount, status FROM read_parquet('$tgtPath')"),
     *     key = { row -> row["order_id"] ?: error("missing order_id column") },
     *     check = { s, t -> engine.compare(s, t) { field("amount") { tolerance(abs = 0.01) } } },
     * ).toList()   // 必须消费，计数器才生效
     * ```
     *
     * 计数器在序列**被消费**时累加（同 [keySet] 的陷阱，不消费全为 0）：
     *
     * - [srcRowCount] / [tgtRowCount]：流过 `src`、`tgt` 的每一行各 ++，与是否命中无关。
     * - [rowDiffCount]：[check] 结果含非 [FieldDiff.Equal] 时 ++；缺行时直接 ++。
     *
     * 计数器只增不减、非线程安全，一次对账用一个实例（见类注释）。
     */
    fun compareRows(
        src: Sequence<Row>,
        tgt: Sequence<Row>,
        key: (Row) -> Any,
        check: (Row, Row) -> List<FieldDiff>,
    ): Sequence<Pair<Any, List<FieldDiff>>> = sequence {
        val srcIndex: HashMap<Any, Row> = HashMap()
        src.forEach { row ->
            srcRowCount++
            val k = key(row)
            // 重复键按 README §4 约定：保留首次出现。
            if (!srcIndex.containsKey(k)) srcIndex[k] = row
        }

        val seen: HashSet<Any> = HashSet(srcIndex.size.coerceAtMost(1024))
        tgt.forEach { tRow ->
            tgtRowCount++
            val k = key(tRow)
            seen.add(k)
            val sRow = srcIndex[k]
            if (sRow == null) {
                rowDiffCount++
                yield(k to listOf<FieldDiff>(FieldDiff.Missing("<row>", Side.SRC)))
            } else {
                val diffs = check(sRow, tRow)
                if (diffs.any { it !is FieldDiff.Equal }) rowDiffCount++
                yield(k to diffs)
            }
        }
        // src 中没有对应 tgt 的键：另一侧的 Missing。
        for ((k, _) in srcIndex) {
            if (k in seen) continue
            rowDiffCount++
            yield(k to listOf<FieldDiff>(FieldDiff.Missing("<row>", Side.TGT)))
        }
    }

    // -------- Summary --------

    /**
     * 把运行期计数器折叠成 [DiffSummary]，三档结果合并成一份。
     *
     * - `diffCount = aggDiffCount + keyDiffCount + rowDiffCount`：三档差异**直接相加**，不去重。
     *   同一主键既在 L1 又在 L3 命中时会各算一次（两档口径本就不同）。
     * - `srcCount` / `tgtCount` 只取 [srcRowCount] / [tgtRowCount]，即**只来自 [compareRows]**。
     *   只跑了 [aggregate]（L1）时两者是 0，别误读成「两端无数据」。
     * - [DiffSummary.level] 是保守估计：任一计数 > 0 升 [DiffLevel.WARN]，否则 [DiffLevel.INFO]。
     *
     * 关键限制：**当前 level 最高只到 WARN**。计数器只是数字，[summary] 无法区分
     * [FieldDiff.Mismatch]（值不同）与 [FieldDiff.Missing]（整行缺失）——后者语义上该是
     * [DiffLevel.ERROR]。需要 ERROR 档时，调用方自己保留逐行 [FieldDiff] 再升档，
     * 例如把 L3 的结果交给 [DiffSummary.accumulate]：它在遇到 [FieldDiff.Missing] 时会升到
     * ERROR、遇到 [FieldDiff.Mismatch] 升到 WARN，因为那边手里有完整的 [DiffRow]。
     *
     * 幂等、无副作用：连续调用返回同样的值，不会重置计数器。
     */
    fun summary(): DiffSummary {
        val hasAnyDiff = aggDiffCount > 0L || keyDiffCount > 0L || rowDiffCount > 0L
        val level = if (hasAnyDiff) DiffLevel.WARN else DiffLevel.INFO
        return DiffSummary(
            srcCount = srcRowCount,
            tgtCount = tgtRowCount,
            diffCount = aggDiffCount + keyDiffCount + rowDiffCount,
            level = level,
        )
    }

    // -------- 内部工具 --------

    /** 把 [Row] 按 [keys] 拼成不可变的 `Map<String, Any?>`，缺列填 null。 */
    private fun keyOf(row: Row, keys: List<String>): Map<String, Any?> =
        keys.associateWith { row[it] }

    /**
     * 单主键两端的字段差。
     *
     * - 任一侧整行缺失：只有 [keys] 是可比信息，逐 key 列 emit [FieldDiff.Missing]。
     * - 两侧都在：委托 [compareInternal]，字段范围是 `s.columns ∪ t.columns`，
     *   逐列按 [rules]（未声明则 `==`）判定；key 列必然相等，保留 Equal 占位便于 Reporter 画表。
     */
    private fun diffPair(s: Row?, t: Row?, keys: List<String>, rules: FieldRules): List<FieldDiff> =
        when {
            s == null && t == null -> emptyList()
            s == null -> keys.map { FieldDiff.Missing(it, Side.SRC) }
            t == null -> keys.map { FieldDiff.Missing(it, Side.TGT) }
            else -> compareInternal(s, t, rules)
        }

    /**
     * [compare] 的实现：字段集合是 [s] 与 [t] 的并集。
     * - 列在 [FieldRules] 里：用规则判断 → true → Equal，false → Mismatch。
     * - 未声明规则的列：默认 `==`。
     * - 一侧缺列：emit [FieldDiff.Missing]。
     */
    private fun compareInternal(s: Row, t: Row, rules: FieldRules): List<FieldDiff> {
        val cols = s.values.keys + t.values.keys
        return cols.map { field ->
            when {
                !s.values.keys.contains(field) -> FieldDiff.Missing(field, Side.SRC)
                !t.values.keys.contains(field) -> FieldDiff.Missing(field, Side.TGT)
                else -> {
                    val sv = s[field]
                    val tv = t[field]
                    val rule = rules.ruleFor(field)
                    val ok = if (rule != null) rule(sv, tv) else sv == tv
                    if (ok) FieldDiff.Equal else FieldDiff.Mismatch(field, sv, tv)
                }
            }
        }
    }
}
