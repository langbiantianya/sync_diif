package com.kxxnzstdsw.sync_diff.checks

import com.kxxnzstdsw.sync_diff.check.Alertable
import com.kxxnzstdsw.sync_diff.check.BuiltinCheck
import com.kxxnzstdsw.sync_diff.check.CheckBase
import com.kxxnzstdsw.sync_diff.check.Ctx
import com.kxxnzstdsw.sync_diff.connectors.ParquetConnector
import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.DiffSummary
import com.kxxnzstdsw.sync_diff.core.FieldRules
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.engine.DiffEngine
import kotlin.math.abs

/**
 * 阶段 1 示例 Check：订单增量同步对账（注册名 `order_sync`）。
 *
 * 一次 [runCheck] 走两档，全程真实数据：
 *
 * **L1 聚合**（[srcL1Sql] / [tgtL1Sql]，两端各跑一条 `read_parquet` 聚合，按 `dt` 对齐）：
 * ```sql
 * SELECT '$dt' AS dt, COUNT(*) AS c, SUM(amount) AS s, SUM(hash(order_id)) AS h
 * FROM read_parquet('$path')
 * ```
 * 这三列的分工决定了规则怎么挂：
 * - `c`（行数）与 `h`（`SUM(hash(order_id))`）：计数与哈希和，两端必须逐位相等，
 *   所以不挂任何规则，走 `==` 精确比。
 * - `s`（`SUM(amount)`）：浮点求和的结合顺序两端可能不同，末位必然漂移；必须挂
 *   [l1Rules] 的 0.01 容忍度，否则行级已经容忍的误差会在 L1 被误报成差异。
 *
 * **L3 行级**（[srcL3Sql] / [tgtL3Sql]）：按 `order_id` 对齐，只比 `order_id` /
 * `amount` / `status` 三列，规则见 [defaultRules]（`amount` 容忍 0.01，`status` 跳过）。
 * 这里没有单独的 L2 主键集合差集：`DiffEngine.compareRows` 对只有一侧存在的 key 直接
 * emit `Missing("<row>", Side.SRC/TGT)`，「缺行」已经由 L3 覆盖。
 *
 * 两个取舍：
 * - 取数带 `ORDER BY order_id LIMIT $take`（默认 1000，见 [runCheck]）：行级比对要把 src 装进内存，
 *   先限量保证「跑得起来」；全量下钻留待后续阶段。`ORDER BY` 保证这 $take 行是**确定的**子集，
 *   同一份数据两次运行样到同一批主键（否则 `LIMIT` 取哪几行由扫描顺序决定，结论不可回归）。
 * - 只有 `order_id` / `amount` / `status` 进 SQL：`updated_at` / `dt` 不取也不比，
 *   想扩列就同时改 [srcL3Sql] / [tgtL3Sql] 与 [defaultRules]。
 *
 * 数据源与 test seam：
 * - 默认两端都走 Parquet（[parquetPath] / [tgtParquetPath]，见 [defaultConnectors]），
 *   烟囱测试因此零依赖可跑。
 * - 上下游各自在 [defaultConnectors] 里明确指定：上游固定走 Parquet；下游目前也是 Parquet
 *   ——要换 Impala 直接改 [defaultConnectors] 让它返回 `ImpalaConnector(...)`，或在执行前
 *   把 [injected] 设成一对目标 Connector（不改源码，测试也走这条）。注意 `hash()` 是 DuckDB
 *   方言，tgt 换成 Impala 时聚合 SQL 里的 `SUM(hash(order_id))` 要换成 Impala 的
 *   `SUM(fnv_hash(order_id))`，否则 `h` 列必然不等。
 * - 本 Check 实现 [com.kxxnzstdsw.sync_diff.check.Alertable]，CLI 用 `--alert-url` 注入告警地址；空串则不发。
 *
 * 类上的 [BuiltinCheck] 让 [com.kxxnzstdsw.sync_diff.check.DiscoverBuiltin] 在清单文件缺省时
 * 自动注册它（注册名 [name]），不需要在任何清单里登记。
 */
@BuiltinCheck
object OrderSyncCheck : CheckBase("order_sync"), Alertable {

    /**
     * 上游 Parquet 路径；默认从 `ORDERS_PARQUET_PATH` 取，取不到退回 `/tmp/orders.parquet`。
     *
     * test seam：测试直接赋值即可切换数据源（`@Volatile` 因为 CLI / 测试线程写、执行线程读）。
     */
    @Volatile var parquetPath: String = System.getenv("ORDERS_PARQUET_PATH") ?: "/tmp/orders.parquet"

    /** 下游 Parquet 路径；默认 `ORDERS_TGT_PARQUET_PATH`，未设则与 [parquetPath] 同一份。 */
    @Volatile var tgtParquetPath: String = System.getenv("ORDERS_TGT_PARQUET_PATH") ?: "/tmp/orders.parquet"

    /** CLI 注入的告警 webhook；默认 `ALERT_URL`，空字符串表示不发告警。 */
    @Volatile
    override var alertUrl: String = System.getenv("ALERT_URL") ?: ""

    /**
     * Smoke-test / 单元测试注入：直接给出 src / tgt 两个 Connector，跳过 [defaultConnectors]。
     * 生产期留 `null` 即可。
     *
     * 测试用它塞进临时 parquet 的 [com.kxxnzstdsw.sync_diff.connectors.ParquetConnector]；生产用它注入 Impala 而无需改代码。
     */
    @Volatile
    var injected: Pair<Connector, Connector>? = null

    override suspend fun Ctx.run() {
        val dt = args.dt
        val (srcConn, tgtConn) = injected ?: defaultConnectors()

        val summary = runCheck(srcConn, tgtConn, dt, engine = diff)
        report.excel("reports/order_sync_${dt}.xlsx", summary)
        if (alertUrl.isNotEmpty()) {
            report.webhook(alertUrl, summary)
        }
    }

    /**
     * 把核心逻辑抽成可测函数（不依赖 [com.kxxnzstdsw.sync_diff.check.Ctx]），测试直接调它覆盖整条数据流。
     *
     * 与 `Ctx.run()` 的分工：这里只算差异并返回 [com.kxxnzstdsw.sync_diff.core.DiffSummary]，报告 / 告警由调用方负责。
     * 测试传自己的 [com.kxxnzstdsw.sync_diff.core.Connector] 就能绕开 [injected]，不必构造 [com.kxxnzstdsw.sync_diff.check.Ctx]：
     * ```kotlin
     * val summary = OrderSyncCheck.runCheck(src, tgt, dt = "2026-09-20", take = 1000)
     * assertTrue(summary.hasDiff)
     * ```
     *
     * - `src` / `tgt`：两个 [com.kxxnzstdsw.sync_diff.core.Connector]，**本函数负责 `use { }` 关闭**，调用方不要再 close。
     * - `dt`：只用于 L1 的 `dt` 列对齐值（拼进 SQL 字符串）。
     * - `take`：L3 每条 SQL 的行数上限，见 [srcL3Sql]。
     * - `engine`：想复用 / 观察计数器就传外部引擎；默认新建一个（[com.kxxnzstdsw.sync_diff.check.Ctx.run] 传的是 `diff`）。
     *   注意 [com.kxxnzstdsw.sync_diff.engine.DiffEngine] 的计数器是累加的，同一个 engine 跑两次会把两次结果并在一起。
     *
     * 返回 [com.kxxnzstdsw.sync_diff.core.DiffSummary]（由 `engine.summary()` 折叠），调用方负责告警 / 落报告。
     */
    fun runCheck(
        src: Connector,
        tgt: Connector,
        dt: String,
        take: Int = 1000,
        engine: DiffEngine = DiffEngine(),
    ): DiffSummary {
        src.use { s -> tgt.use { t -> runCheckInner(s, t, dt, take, engine) } }
        return engine.summary()
    }

    /**
     * [runCheck] 的实际工作体：在两个活的 [Connector] 上跑 L1 聚合 + L3 行级，状态累加到 [engine]。
     *
     * 与 [runCheck] 的分工：[runCheck] 负责 `use { }` 关连接 + 折叠 [engine] 计数器；
     * 本方法只做两档对账本身。`key` / `check` 闭包集中在这里，避免污染公共签名。
     */
    private fun runCheckInner(s: Connector, t: Connector, dt: String, take: Int, engine: DiffEngine) {
        // L1：count + sum + checksum，按 dt 对齐
        engine.aggregate(
            s.query(srcL1Sql(dt)),
            t.query(tgtL1Sql(dt)),
            keys = listOf("dt"),
            rules = l1Rules,
        )
        // L3：行级比对，按 order_id 对齐
        engine.compareRows(
            src = s.stream(srcL3Sql(dt, take)),
            tgt = t.stream(tgtL3Sql(dt, take)),
            key = { row -> row["order_id"] ?: error("missing order_id column") },
            check = { sRow, tRow -> engine.compare(sRow, tRow, defaultRules) },
        ).toList()
    }

    // -------- 私有：默认 src/tgt 构造（都用 ParquetConnector） --------

    /**
     * [injected] 为空时的默认数据源：两端都是 [com.kxxnzstdsw.sync_diff.connectors.ParquetConnector]。
     *
     * 生产要接 Impala 就改这里（返回 `ImpalaConnector(cfg.impala.jdbcUrl, ...)`），
     * 或者在外面设 `injected` 覆盖；两条路都不影响 [runCheck] 的签名。
     */
    private fun defaultConnectors(): Pair<Connector, Connector> =
        ParquetConnector(parquetPath) to ParquetConnector(tgtParquetPath)

    // -------- 私有：SQL 模板 --------
    //
    // 全部把 dt / 路径直接拼进字符串：这里是内部模板，只为让 Check 能跑通；
    // 接外部输入时须改成预编译参数或白名单校验（README §5.1 注）。

    /** L1 上游聚合：`dt` 常量列 + 行数 `c` + 金额和 `s` + 主键哈希和 `h`。 */
    private fun srcL1Sql(dt: String): String =
        "SELECT '$dt' AS dt, COUNT(*) AS c, SUM(amount) AS s, " +
            "SUM(hash(order_id)) AS h FROM read_parquet('$parquetPath')"

    /** L1 下游聚合：列与 [srcL1Sql] 完全一致，只有数据源路径不同，否则没法按列比。 */
    private fun tgtL1Sql(dt: String): String =
        "SELECT '$dt' AS dt, COUNT(*) AS c, SUM(amount) AS s, " +
            "SUM(hash(order_id)) AS h FROM read_parquet('$tgtParquetPath')"

    /**
     * L3 上游明细：只取参与比对的 `order_id` / `amount` / `status`，`ORDER BY order_id LIMIT $take` 限量。
     *
     * `ORDER BY` 不是装饰：`LIMIT` 没有排序时取哪 $take 行由扫描顺序决定（多 part / 并行扫描下
     * 不保证稳定），两次运行会样到不同子集，报告结论不可回归。带上 `ORDER BY` 后抽样是确定的
     * 「最小的 $take 个 order_id」，两端也按同一顺序对齐。
     *
     * `dt` 参数当前未拼进 SQL——L3 靠 `order_id` 对齐、分区过滤交给文件路径；保留参数只为
     * 与 [tgtL3Sql] 保持同一签名（后续按分区改写 SQL 时直接可用）。
     */
    private fun srcL3Sql(dt: String, take: Int): String =
        "SELECT order_id, amount, status FROM read_parquet('$parquetPath') " +
            "ORDER BY order_id LIMIT $take"

    /** L3 下游明细：列与 [srcL3Sql] 一致（含 `ORDER BY`），只换数据源（同上，`dt` 暂未使用）。 */
    private fun tgtL3Sql(dt: String, take: Int): String =
        "SELECT order_id, amount, status FROM read_parquet('$tgtParquetPath') " +
            "ORDER BY order_id LIMIT $take"

    // -------- 私有：字段规则 lambda --------
    //
    // 用 bind 直接挂规则：`FieldRules.() -> Unit` 的 lambda 形式，匹配
    // DiffEngine.compare(s, t, rules) 的签名。

    /**
     * 金额容忍规则：两侧都是 [Number] 且 `abs(差) <= 0.01` 才算相等。
     *
     * 任一侧为 `null`（或不是数字）一律判不等——不把 `null` 当成 0；真要不比该列请用
     * [ignoreAny] 显式跳过。
     */
    private val toleranceAmount: (Any?, Any?) -> Boolean = { s, t ->
        val a = (s as? Number)?.toDouble()
        val b = (t as? Number)?.toDouble()
        a != null && b != null && abs(a - b) <= 0.01
    }

    /** 保底规则：永远判相等，即「该列不参与判定」（用于状态类、噪声列）。 */
    private val ignoreAny: (Any?, Any?) -> Boolean = { _, _ -> true }

    /**
     * L1 聚合规则：`s` 是 `SUM(amount)`，与行级 `amount` 共用同一容忍度——
     * 否则行级允许的漂移会在聚合层被误判成差异。`c` / `h` 是计数与哈希和，必须精确相等，
     * 所以不在这里声明（未声明的列走 `==`）。
     */
    private val l1Rules: FieldRules.() -> Unit = {
        bind("s", toleranceAmount)
    }

    /** L3 行级规则：`amount` 容忍 0.01，`status` 跳过比对；`order_id` 未声明 → 精确比。 */
    private val defaultRules: FieldRules.() -> Unit = {
        bind("amount", toleranceAmount)
        bind("status", ignoreAny)
    }
}
