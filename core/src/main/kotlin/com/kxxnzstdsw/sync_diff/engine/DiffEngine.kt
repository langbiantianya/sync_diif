package com.kxxnzstdsw.sync_diff.engine

import com.kxxnzstdsw.sync_diff.core.*

/**
 * 三档差异（L1 聚合 / L2 主键集合 / L3 行级）+ 自定义档的执行体。
 *
 * 各档的入口、输入与代价：
 *
 * | 档位 | 入口 | 输入 | 额外内存 | 用途 |
 * |:---|:---|:---|:---|:---|
 * | L1 | [aggregate] | `List<Row>`，一行一个分区 | O(分区数)，两侧全量进内存 | count / sum / checksum 粗筛 |
 * | L2 | [keySet] | `Sequence<Row>`，只含主键列 | O(对侧键数)，两次差集合计 O(两端键数) | 主键集合对称差，找缺失行 |
 * | L3 | [compareRows] | `Sequence<Row>`，整行 | O(src 行数 + tgt 去重键数) | 逐行逐字段细比，定位漂移列 |
 * | 自定义 | [reconcile] | `Sequence<Row>`，整行 | O(src 行数 + tgt 去重键数) | 自己定「怎么算对得上」的档位 |
 *
 * ## 共用内核 [align]
 *
 * L1 / L3 / 自定义档都是同一个 [align]（按 key 的全外连接）套上各自的「产出什么、算不算
 * 差异、记哪个计数」：对齐 / 缺行 / 重复键的语义只有一份实现（见 [align] 的 KDoc）。
 * **L2 是刻意的例外**：它只需要主键、不需要行，额外内存 O(两端去重键数) 比内核的
 * O(src 行数) 小一个档次，挂到内核上只会多占内存、换不到任何一致性（见 [keySet] 的 KDoc）。
 *
 * 自定义档就是把内核的四个 lambda 交给调用方（[reconcile]）：怎么取键、两侧都有时比什么、
 * 只有一侧时产出什么、什么算差异。差异进 [customDiffCount]，与内置档一起进 [summary]，
 * 报告侧不需要知道这是第几档。
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
 * - **惰性贯穿**：[keySet] / [compareRows] / [align] / [reconcile] 接收 `Sequence<Row>`，
 *   全程不把流抽进 `List`。
 *   L3 的差异 [Sequence] 也是惰性的；调用方需要 [DiffSummary] 时再 `toList` / fold。
 *   注意惰性的代价：这些序列上的副作用（计数器累加）同样只在消费时发生。
 * - **运行期计数器**：每次 [aggregate] / [keySet] / [compareRows] / [reconcile] 跑完，更新
 *   [aggDiffCount] / [keyDiffCount] / [rowDiffCount] / [customDiffCount] /
 *   [srcRowCount] / [tgtRowCount]；[summary] 把它们折叠成 [DiffSummary]。
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
     * 自定义档差异数：[reconcile] 每产出（`yield`）一个 `isDiff` 判为 true 的结果就 ++。
     *
     * 口径完全由调用方的 `isDiff` 定——缺行算不算差异、缺列算不算，引擎不猜。同
     * [keyDiffCount]，累加发生在序列被**消费**时，拿到序列不消费就永远是 0。
     */
    var customDiffCount: Long = 0L
        private set

    /**
     * 流式档的源端行数：[compareRows] / [reconcile] 里 `src` 每流过一行 ++（重复键、未命中
     * tgt 的行都算）。
     *
     * 该计数**不来自** [aggregate]（L1）：只跑 L1 时它是 0，[summary] 的 `srcCount` 也是 0。
     */
    var srcRowCount: Long = 0L
        private set

    /** 流式档的目标端行数：[compareRows] / [reconcile] 消费 `tgt` 时每读一行 ++（缺行 / 命中都算）。 */
    var tgtRowCount: Long = 0L
        private set

    // -------- 共用内核：按 key 的全外连接 --------

    /**
     * 全外连接内核：按 [key] 把两侧对齐——两侧都有 → [compare]，只有一侧 → [onMissing]，
     * [Side] 指向**缺的那一侧**（tgt 独有 → `Side.SRC`，src 独有 → `Side.TGT`）。
     *
     * 内置档里的 [aggregate]（L1）与 [compareRows]（L3）、自定义档 [reconcile] 都建在它上面，
     * 差别只在"产出什么 [V]"与"什么算差异"。所以**对齐 / 缺行 / 重复键的语义只有这一份**：
     * 想加一档新的对账，传一套 lambda 即可，不用重写 join（见 [reconcile]）。
     *
     * 产出顺序（[compareRows] 的顺序契约也是它）：
     *
     * 1. 按 `tgt` 的流序逐个产出：命中的是两侧比出来的 [V]，没命中的是 `onMissing(k, Side.SRC)`；
     * 2. 再按 src 的首次出现顺序，产出那些 `tgt` 里没有的键的 `onMissing(k, Side.TGT)`。
     *
     * 这个顺序不是随手定的：`tgt` 单向流过一遍、不落堆，只有 `src` 装进 `HashMap`；反过来按
     * src 流序输出就得把 `tgt` 也缓存住，"一侧可控、一侧很大"的场景直接不成立。
     *
     * 重复键：[src] 同名键保留**首次出现**（与 README §4 的约定一致），后面的同键行被丢弃；
     * [tgt] 侧重复键**各自产出**（同一个键可能出现多次），要不要去重由调用方决定——内核不替
     * 调用方猜"哪一行才算数"。
     *
     * 内存与时机：额外内存 O(src 行数 + tgt 去重键数)；`src` 的索引在**序列被消费时**才建，
     * 所以 `align(...)` 这一句本身不读任何一侧，也不碰任何计数器——行数 / 差异怎么计数是各档
     * 自己的事（[reconcile] 就是内核 + 计数）。
     *
     * [key] 取不到值（缺列 / NULL）时怎么办由调用方在 [key] 里决定（[keySet] 选择直接失败）；
     * 想跳过这类行就在 SQL 里过滤。
     *
     * ```kotlin
     * // 只想把两侧 join 起来看看（不计数）：缺行与命中各自拼一段文本
     * engine.align(
     *     src = src.stream("SELECT order_id, amount FROM read_parquet('$path')"),
     *     tgt = tgt.stream("SELECT order_id, amount FROM ods.orders WHERE dt = '$dt'"),
     *     key = { row -> row["order_id"] ?: error("missing order_id") },
     *     onMissing = { _, side -> "missing on ${side.name.lowercase()}" },
     *     compare = { s, t -> "amount ${s["amount"]} vs ${t["amount"]}" },
     * ).forEach { (k, v) -> println("$k -> $v") }
     * ```
     */
    fun <K : Any, V> align(
        src: Sequence<Row>,
        tgt: Sequence<Row>,
        key: (Row) -> K,
        onMissing: (K, Side) -> V,
        compare: (Row, Row) -> V,
    ): Sequence<Pair<K, V>> = sequence {
        val srcIndex: HashMap<K, Row> = HashMap()
        src.forEach { row ->
            val k = key(row)
            // 重复键按 README §4 约定：保留首次出现。
            if (!srcIndex.containsKey(k)) srcIndex[k] = row
        }

        val seen: HashSet<K> = HashSet(srcIndex.size.coerceAtMost(1024))
        tgt.forEach { tRow ->
            val k = key(tRow)
            seen.add(k)
            val sRow = srcIndex[k]
            yield(k to if (sRow == null) onMissing(k, Side.SRC) else compare(sRow, tRow))
        }

        // src 中没有对应 tgt 的键：另一侧的 Missing。
        for ((k, _) in srcIndex) {
            if (k in seen) continue
            yield(k to onMissing(k, Side.TGT))
        }
    }

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
     * 行序与重复键走内核 [align] 的约定：**先 tgt 流序（命中与 tgt 独有交错），再 src 独有**；
     * 同一主键在 src 侧保留首次出现、在 tgt 侧逐条 emit。L1 的输入是 `GROUP BY` 的聚合结果，
     * 正常一个主键一行；真出现重复主键说明上游有问题，规则与 [compareRows] 一处定义，不各写一套。
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
        val rows = align(
            src = src.asSequence(),
            tgt = tgt.asSequence(),
            key = { keyOf(it, keys) },
            onMissing = { _, side -> keys.map { FieldDiff.Missing(it, side) } },
            compare = { s, t -> compareInternal(s, t, fieldRules) },
        ).map { (k, diffs) -> DiffRow(key = k, diffs = diffs) }.toList()
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
     * **故意不走 [align] 内核**：内核为了 `compare` 得把 `src` 的整行留在 `HashMap` 里
     * （O(src 行数)），而本档只需主键一列——收 `LinkedHashSet` 比收行省一个内存档次，
     * 这正是上面那个内存量级的来源。挂到内核上只换来"形式统一"，代价是内存变大。
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
     *
     * **`key` 列为 NULL 时直接失败**（[IllegalStateException]）：NULL 主键无法参与对齐，
     * 静默丢弃会让"上游多了一堆主键为 NULL 的脏行"看起来像"两端一致"——对账工具最不该犯的错。
     * 需要容忍它就在 SQL 里显式处理（`WHERE key IS NOT NULL` 或 `COALESCE(key, '<null>')`）。
     */
    fun keySet(src: Sequence<Row>, tgt: Sequence<Row>, key: String): Sequence<String> {
        // 两侧各物化一次：用 `Sequence.minus` 做两次差额会把每一侧都再跑一遍
        // （`minus` 会物化对侧，两个方向的差额正好把两侧各读两遍）。
        val srcKeys = src.mapTo(LinkedHashSet()) { it.requireKey(key, "src") }
        val tgtKeys = tgt.mapTo(LinkedHashSet()) { it.requireKey(key, "tgt") }
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
     * 实现就是内核 [align] 套上计数与缺行产物（顺序 / 重复键的约定见 [align]，这里不重复）。
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
        for ((k, diffs) in align(
            src = src.onEach { srcRowCount++ },
            tgt = tgt.onEach { tgtRowCount++ },
            key = key,
            onMissing = { _, side -> listOf(FieldDiff.Missing("<row>", side)) },
            compare = check,
        )) {
            if (diffs.any { it !is FieldDiff.Equal }) rowDiffCount++
            yield(k to diffs)
        }
    }

    // -------- 自定义档：自己定义「怎么算对得上」 --------

    /**
     * 自定义对账档：内核同 [align]，外加把行数与差异计进计数器——于是自定义口径也能进
     * [summary] / 报告，"再加一档对账"不必改引擎。
     *
     * 四个 lambda 就是全部口径，缺一不可：
     *
     * - [key]：怎么取对齐键。多列组合、拼接串、归一后的键（大小写 / 去空格 / 去前缀）都行，
     *   只要 `equals` / `hashCode` 是你要的语义。
     * - [compare]：两侧都有时产出什么。[V] 由调用方定义——自定义差异行、一个 `Boolean`、
     *   一段文本、`List<FieldDiff>` 都可以。
     * - [onMissing]：只有一侧时产出什么，[Side] 指向**缺的那一侧**。
     * - [isDiff]：产出物算不算一次差异。**必须显式给**：引擎不猜"缺行算不算差异"。
     *
     * 计数（都在序列**被消费**时累加，同 [keySet] 的坑）：
     *
     * - `src` / `tgt` 流过的每一行 → [srcRowCount] / [tgtRowCount]（命中与否都算，重复键也各算）；
     * - 每个 `isDiff` 为 true 的产出 → [customDiffCount]，进 [summary] 的 `diffCount`。
     *
     * 与其它入口的分工：要进内置计数（[rowDiffCount] 等）就用对应的内置档（[compareRows] 等）；
     * 只想把两侧 join 起来看看、不要计数，用 [align]；要跑好几套不同口径，就每套调一次
     * [reconcile]（计数器累加，一个 Check 一个实例）。
     *
     * ```kotlin
     * /** 自定义差异行：(dt, order_id) 组合键，只比金额。 */
     * data class AmountDiff(val key: String, val detail: String, val isDiff: Boolean)
     *
     * val diffs = engine.reconcile(
     *     src = src.stream("SELECT dt, order_id, amount FROM read_parquet('$path')"),
     *     tgt = tgt.stream("SELECT dt, order_id, amount FROM ods.orders WHERE dt = '$dt'"),
     *     key = { row -> "${row["dt"]}/${row["order_id"]}" },
     *     onMissing = { k, side -> AmountDiff(k, "missing on ${side.name.lowercase()}", true) },
     *     compare = { s, t ->
     *         val sv = s["amount"] as BigDecimal
     *         val tv = t["amount"] as BigDecimal
     *         AmountDiff("${s["dt"]}/${s["order_id"]}", "amount $sv vs $tv", sv.compareTo(tv) != 0)
     *     },
     *     isDiff = { it.isDiff },
     * ).toList()   // 必须消费，计数器才生效
     *
     * engine.summary()   // diffs 里的差异已经并进 diffCount / hasDiff
     * ```
     *
     * 顺序、内存、重复键、惰性与 [align] 完全一致（它就是 [align] 外面套一层计数）。
     */
    fun <K : Any, V> reconcile(
        src: Sequence<Row>,
        tgt: Sequence<Row>,
        key: (Row) -> K,
        onMissing: (K, Side) -> V,
        compare: (Row, Row) -> V,
        isDiff: (V) -> Boolean,
    ): Sequence<Pair<K, V>> = sequence {
        for ((k, v) in align(
            src = src.onEach { srcRowCount++ },
            tgt = tgt.onEach { tgtRowCount++ },
            key = key,
            onMissing = onMissing,
            compare = compare,
        )) {
            if (isDiff(v)) customDiffCount++
            yield(k to v)
        }
    }

    // -------- Summary --------

    /**
     * 把运行期计数器折叠成 [DiffSummary]，所有档的结果合并成一份。
     *
     * - `diffCount = aggDiffCount + keyDiffCount + rowDiffCount + customDiffCount`：各档差异
     *   **直接相加**，不去重。同一主键既在 L1 又在 L3 命中时会各算一次（两档口径本就不同）。
     * - `srcCount` / `tgtCount` 只取 [srcRowCount] / [tgtRowCount]，即**只来自流式档**
     *   （[compareRows] / [reconcile]）。只跑了 [aggregate]（L1）时两者是 0，别误读成「两端无数据」。
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
        val hasAnyDiff = aggDiffCount > 0L || keyDiffCount > 0L || rowDiffCount > 0L ||
            customDiffCount > 0L
        val level = if (hasAnyDiff) DiffLevel.WARN else DiffLevel.INFO
        return DiffSummary(
            srcCount = srcRowCount,
            tgtCount = tgtRowCount,
            diffCount = aggDiffCount + keyDiffCount + rowDiffCount + customDiffCount,
            level = level,
        )
    }

    // -------- 内部工具 --------

    /** 把 [Row] 按 [keys] 拼成不可变的 `Map<String, Any?>`，缺列填 null。 */
    private fun keyOf(row: Row, keys: List<String>): Map<String, Any?> =
        keys.associateWith { row[it] }

    /**
     * L2 取主键：缺列或值为 null 都视为"没有可对齐的主键"，直接失败而不是跳过这一行
     * （理由见 [keySet] 的 KDoc）。[side] 只用于报错信息，指出是哪一侧的数据。
     */
    private fun Row.requireKey(key: String, side: String): String =
        this[key]?.toString()
            ?: error("keySet: $side row has NULL/missing key '$key' ($values); filter it in SQL or map it explicitly")

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
