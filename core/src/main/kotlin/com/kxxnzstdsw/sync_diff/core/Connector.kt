package com.kxxnzstdsw.sync_diff.core

import java.io.Closeable

/**
 * 两端统一的数据源抽象：上游（Parquet / DuckDB）和下游（Impala / Hive JDBC）都实现它，
 * 于是 DiffEngine 与 Check 只认 SQL + [Row]，不认具体引擎。
 *
 * 选哪个方法：
 *
 * | 层次 | 典型查询 | 方法 |
 * | L1 聚合 | `COUNT(*)` / `SUM(…)`，结果恒为几行 | [query] |
 * | L2 主键集合 | 全表扫描主键，可能上百万行 | [stream] |
 * | L3 行级点查 | `WHERE pk = …`，一次一行 | [one] |
 *
 * ## 实现一个新 Connector 要遵守什么
 *
 * 1. **构造期建连**：在属性初始化里建立连接 / 文件句柄（参考 `ParquetConnector` /
 *    `ImpalaConnector`），不要拖到第一次 [query] 才 lazy 建连——调用方靠"构造成功"等价于"连通"。
 * 2. **[close] 释放**：由调用方通过 `use { … }` 或 Check 生命周期统一关闭；实例用完即弃，
 *    不要设计成跨请求复用的连接池。
 * 3. **失败必须抛 [ConnectorError]**：不要让裸 [java.sql.SQLException] /
 *    [java.io.IOException] 一路冒泡。方法体内统一用 [guard] 把副作用包起来。
 * 4. **[stream] 必须是服务端游标逐行 `yield`**：不许写 `query(sql).asSequence()` 那种"先全量
 *    物化再假装惰性"的实现，否则 L2 扫描会把上亿条抽进内存。参考 `ImpalaConnector` 的
 *    `fetchSize` 服务端游标。
 *
 * ```kotlin
 * class MyConnector(url: String) : Connector {
 *     private val conn: java.sql.Connection = openConnection(url)      // 构造期建连，失败即抛
 *
 *     override fun query(sql: String): List<Row> = guard(sql) {
 *         conn.prepareStatement(sql).use { st -> st.executeQuery().use { rs -> rs.toRows() } }
 *     }
 *
 *     override fun stream(sql: String): Sequence<Row> = sequence {
 *         conn.prepareStatement(sql).use { st ->
 *             st.executeQuery().use { rs -> while (rs.next()) yield(rs.toRow()) }
 *         }
 *     }
 *
 *     override fun one(sql: String): Row? = query("$sql LIMIT 1").firstOrNull()
 *
 *     override fun close() = conn.close()
 * }
 * ```
 *
 * 已知差额：构造期建连失败目前**没有**被 [guard] 包住，原生异常会直接冒出来
 * （`ImpalaConnectorTest` 就是按 `assertFailsWith<SQLException>` 断言的）；上面第 3 条契约
 * 只覆盖 [query] / [stream] / [one] 这类方法调用。
 */
interface Connector : Closeable {

    /**
     * 小结果集，一次性读完并返回 `List<Row>`：L1 聚合（`SELECT COUNT(*) …`、`SUM(amount)`、
     * `SUM(hash(pk))`）这类**几十行以内**的查询用它。
     *
     * 结果大就会 OOM——大结果集请走 [stream]。执行失败抛 [ConnectorError.QueryFailed]，
     * 异常的 `sql` 就是本次 SQL。
     *
     * ```kotlin
     * val agg = src.query("SELECT COUNT(*) AS c, SUM(amount) AS s FROM read_parquet('$path')")
     * agg.single().longVal("c")      // 行数（COUNT 归一是 Long，不是 Int）
     * agg.single().decimal("s")      // SUM 归一是 BigDecimal
     * ```
     */
    fun query(sql: String): List<Row>

    /**
     * 大结果集，惰性 [Sequence]：L2 主键集合扫描、全表遍历用它。
     *
     * 实现契约是**服务端游标逐行 `yield`**，所以规模由调用方用惰性算子兜住：
     * `take(n)` / `first()` / `forEach` 只驱动需要的行数并提前终止上游；
     * 反之 `toList()` / `count()` 会把整条查询拉完。
     *
     * ```kotlin
     * src.stream("SELECT order_id FROM read_parquet('$path')")
     *     .mapNotNull { it.string("order_id") }
     *     .take(1000)
     *     .toList()
     * ```
     *
     * 注意：[Sequence] 每次终端操作都会**重新驱动上游**（JDBC 实现会重跑这条 SQL），
     * 别把它当可反复读的集合用；需要复用就自己 `toList()`。
     */
    fun stream(sql: String): Sequence<Row>

    /**
     * 单行点查，无结果返回 `null`：L3 按主键取一行做字段比对用它。
     *
     * 实现通常是把 [sql] 拼上 `LIMIT 1` 再走 [query]，所以别在 [sql] 末尾自己写分号。
     *
     * ```kotlin
     * val s = src.one("SELECT amount, status FROM read_parquet('$path') WHERE order_id = 'o-1'")
     *     ?: return
     * val t = tgt.one("SELECT amount, status FROM ods.orders WHERE order_id = 'o-1'")
     *     ?: return
     * val diffs = DiffEngine().compare(s, t) { field("status") { ignore() } }
     * ```
     */
    fun one(sql: String): Row?
}
