package com.kxxnzstdsw.sync_diff.core

/**
 * Connector 的领域错误：替代裸 [java.sql.SQLException] / [java.io.IOException] 的冒泡。
 *
 * 实现是 [Throwable]（便于日志 / 监控直接拿到 cause 和 stacktrace），但通过 `when`
 * 强制穷尽，避免上层靠 `instanceof` 嗅探。
 *
 * 两条规约：
 * 1. 每个错误都带 `sql`（点查可能没有原始 SQL，但 [TypeCoercion] 用 `<type-coercion>` 占位）。
 * 2. `cause` 永远保留原始异常，便于日志 / 监控定位。
 *
 * 捕获之后拿得到的东西：
 *
 * ```kotlin
 * try {
 *     src.query(sql)
 * } catch (e: ConnectorError.QueryFailed) {
 *     log.error("query failed: sql=${e.sql}", e.cause)     // e.cause 恒为底层异常
 * } catch (e: ConnectorError.TypeCoercion) {
 *     log.error("column ${e.column}: ${e.from} -> ${e.to}")
 * }
 *
 * // 或用 when 一次穷尽分派
 * val msg: String = when (e) {
 *     is ConnectorError.QueryFailed -> "sql=${e.sql} cause=${e.cause.message}"
 *     is ConnectorError.TypeCoercion -> "column=${e.column}"
 * }
 * ```
 *
 * 注意：[QueryFailed] 是 `data class`，展示文本由覆写的 `message` 拼出，[sql] / [cause]
 * 是结构化字段——日志里请显式打 `e.sql`，不要去 parse `message` 字符串。
 *
 * 设计说明：Kotlin 里 `interface` 不能 `extends Throwable`（[Throwable] 是 `open class`，
 * 接口只能实现它、不能被它继承），所以这里用 `sealed class`；在 `when` 里的穷尽性与
 * `sealed interface` 等价。代价是子类必须共享 [RuntimeException] 这一条继承链。
 */
sealed class ConnectorError : RuntimeException() {

    /** 触发该错误的 SQL；[TypeCoercion] 没有原始 SQL 时为 `<type-coercion>`。 */
    abstract val sql: String

    /** 查询执行失败：把任意 [Throwable] 包成领域错误。 */
    data class QueryFailed(
        override val sql: String,
        override val cause: Throwable,
    ) : ConnectorError() {
        override val message: String
            get() = "QueryFailed(sql=$sql): ${cause.javaClass.simpleName}: ${cause.message}"
    }

    /**
     * 类型归一失败：源端实际类型与目标类型无法互转——[from] 是拿到的类型名、[to] 是期望的类型名，
     * [column] 是列名（`sql` 用 `<type-coercion>` 占位，因为没有对应的一条 SQL）。
     *
     * 现状说明：**本仓库暂无抛出点**。`ResultSetExt.coerceType` 遇到无法归一的类型是原样透传，
     * 真正兜不住时才在内部 `IllegalStateException` + `guard` 变成 [QueryFailed]。
     * 保留该分支是为了给"归一失败"一个可穷尽的类型位，接新 Connector 时可自行抛出。
     */
    data class TypeCoercion(
        val column: String,
        val from: String,
        val to: String,
    ) : ConnectorError() {
        override val sql: String get() = "<type-coercion>"
        override val message: String
            get() = "TypeCoercion(column=$column, from=$from, to=$to)"
    }
}

/**
 * 把任意可能抛异常的 block 包成 [ConnectorError.QueryFailed]：Connector 实现里**每个**触碰
 * 外部 IO 的 block（prepareStatement / executeQuery / 遍历 ResultSet）都该用它包起来。
 *
 * ```kotlin
 * override fun query(sql: String): List<Row> = guard(sql) {
 *     conn.prepareStatement(sql).use { st ->
 *         st.executeQuery().use { rs -> rs.toRows() }
 *     }
 * }
 * ```
 *
 * 注意：
 * - 实现是 `runCatching`，捕获的是 [Throwable] 且**不做透传**：block 里已经抛出的
 *   [ConnectorError] 会被再包一层 `QueryFailed`。所以不要在 guard 块里手动抛 [ConnectorError]，
 *   也不要用它包可能抛 `Error` / `CancellationException` 的代码——这类"不该算查询失败"的异常
 *   同样会被吞掉并改写成连接错误。
 * - 不要拿它做控制流（例如用异常表达"这一行不存在"）：查不到就返回 `null`。
 */
inline fun <T> Connector.guard(sql: String, block: () -> T): T =
    runCatching(block).getOrElse { e ->
        throw ConnectorError.QueryFailed(sql, e)
    }
