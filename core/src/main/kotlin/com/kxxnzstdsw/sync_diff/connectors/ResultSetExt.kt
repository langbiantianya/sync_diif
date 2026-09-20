package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Row
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 把 [ResultSet] 当前行归一成一个 [Row]（README §9）。只读当前行，**不** `next()`。
 *
 * 行键取 `metaData.getColumnLabel(i)`：尊重 SQL 里的 `AS` 别名（`SELECT amount AS amt`
 * 得到的键是 `amt`），而不是物理列名 `getColumnName(i)`。值走 [coerceType]。
 *
 * 归一规则，类型名（`getColumnTypeName`）优先，命中不了再按运行时类兜底：
 *
 * | 源类型 | 归一到 | 说明 |
 * |:---|:---|:---|
 * | `TIMESTAMP` | `Instant`（按 UTC 解释） | naive，不带时区，见下 |
 * | `TIMESTAMP_WITH_TIMEZONE` / `DATETIME` / `DATETIMEOFFSET` | `Instant` | naive 按 UTC |
 * | `DECIMAL` / `NUMERIC` | `BigDecimal` | 经字符串构造，保精度 |
 * | 字符串（`VARCHAR` / `CHAR` 等） | `String` | 驱动已按 UTF-8 解码 |
 * | `NULL` | `null` | 短路，不再看类型名 |
 * | `java.sql.Date` / `java.time.LocalDate` | `Instant`（当日 00:00 UTC） | 兜底 |
 * | `java.time.Duration` | `Instant`（`Instant.EPOCH` + duration） | 少见，兜底 |
 * | `Number` 且 className 为 `java.math.BigDecimal` | `BigDecimal` | DECIMAL 以 Number 落地时兜底 |
 * | 其它 | 透传 `getObject` | 未覆盖类型的默认路径 |
 *
 * 带偏移型（`TIMESTAMP_WITH_TIMEZONE` / `DATETIMEOFFSET`）按其自身偏移归一；naive 型
 * （`TIMESTAMP` 与 Hive 的 `DATETIME`）**一律按 UTC 解释**，走 `LocalDateTime -> Instant(UTC)`，
 * 故意**不走** `Timestamp.toInstant()` —— 后者吃 JVM 默认时区，同一份数据在不同机器上会
 * 归一出不同的 `Instant`，跨源 `==` 当场失效。这是"跨源可比较"的前提。
 *
 * 扩展新上游源时：驱动返回的类型这里没覆盖，就**在本文件加分支**，不要在 Check 里散落
 * 转换。归一职责集中在此，[Row.values] 对外只有 `Instant` / `BigDecimal` / `String` /
 * `Number` / `null`，L1~L3 才敢直接比。
 *
 * ```kotlin
 * // 键 = SQL 里的列标签（别名优先）
 * conn.prepareStatement(
 *     "SELECT order_id, amount AS amt FROM read_parquet('/data/orders/part-*.parquet')",
 * ).use { st ->
 *     st.executeQuery().use { rs ->
 *         while (rs.next()) {
 *             val row: Row = rs.toRow()
 *             println(row["order_id"])
 *             println(row["amt"]) // AS 别名；用 getColumnName 拿不到它
 *         }
 *     }
 * }
 * ```
 *
 * 注意：`SELECT * FROM a JOIN b` 这类同名列会产生重复的列标签，重复键在组装 [Row] 时
 * 会被后者覆盖（只剩一个键）—— SQL 里显式起别名。
 *
 * 注意：类型名命中时间分支、但驱动给的对象压不平（例如把 `TIMESTAMP` 列读成 `String`），
 * [coerceType] 会抛 `IllegalStateException("Cannot coerce ... to Instant")`；这是留给新源的
 * 显式故障点，别用 catch 吞掉。
 */
fun ResultSet.toRow(): Row = Row((1..metaData.columnCount).associate { i ->
    metaData.getColumnLabel(i) to coerceType(i)
})

/**
 * 把 [ResultSet] 全量消费成 [List]<[Row]>：一路 `next()` 到底，**并关闭 `this`**（接收者
 * 被消耗掉，之后不能再复用）。
 *
 * 只适合小结果集（L1 聚合那种几行）。大结果集用 Connector 的 `stream()` 逐行拿，别在
 * JVM 堆里再堆一份完整拷贝。
 */
fun ResultSet.toRows(): List<Row> {
    val out = ArrayList<Row>()
    use { rs -> while (rs.next()) out.add(rs.toRow()) }
    return out
}

/**
 * 按列下标做类型归一，规则与边界见 [toRow] 的表格。
 *
 * 匹配顺序：
 * 1. 先按 JDBC 类型名（`TIMESTAMP` / `TIMESTAMP_WITH_TIMEZONE` / `DATETIME` /
 *    `DATETIMEOFFSET` / `DECIMAL` / `NUMERIC`）匹配；类型名读取失败按空串处理。
 * 2. 类型名匹配不上时，再 fallback 到运行时类型（`Timestamp` / `java.time.*` / `Number`）。
 * 3. 都不匹配就透传 `getObject(i)`。
 *
 * `getObject(i)` 返回 `null` 时直接返回 `null`，不会再读类型名 —— 所以 NULL 列不挑驱动。
 *
 * 代价：每列都会读一次 `getObject` + 最多两次元数据（类型名、类名），元数据查询本身包在
 * `runCatching` 里，驱动不支持也不会炸，只是退化成运行时类型匹配。
 */
fun ResultSet.coerceType(i: Int): Any? {
    val raw = getObject(i) ?: return null

    val typeName = runCatching { metaData.getColumnTypeName(i).uppercase() }.getOrNull().orEmpty()
    val className = runCatching { metaData.getColumnClassName(i) }.getOrNull().orEmpty()

    return when {
        typeName == "TIMESTAMP" || typeName == "TIMESTAMP_WITH_TIMEZONE" ||
            typeName == "DATETIME" || typeName == "DATETIMEOFFSET" -> raw.toInstant()

        typeName == "DECIMAL" || typeName == "NUMERIC" -> when (raw) {
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            else -> raw
        }

        raw is Timestamp -> raw.toInstant()
        raw is OffsetDateTime -> raw.toInstant()
        raw is LocalDateTime -> raw.toInstant(ZoneOffset.UTC)
        raw is java.time.LocalDate -> raw.atStartOfDay().toInstant(ZoneOffset.UTC)
        raw is java.sql.Date -> raw.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
        raw is java.time.Duration -> Instant.EPOCH.plus(raw)
        raw is Number && className == "java.math.BigDecimal" -> BigDecimal(raw.toString())

        else -> raw
    }
}

/**
 * 兜底：把**已判定为时间型**的对象压平成 `Instant`。
 *
 * 压不平（驱动给了 `String` / `Boolean` 之类）就抛 [IllegalStateException]，不做静默透传
 * —— 类型名与运行时对象对不上属于驱动/元数据异常，早点炸掉比后面比出一堆假差异好。
 *
 * `Number` 按 epoch 毫秒解释（`Instant.ofEpochMilli`）。`java.time.Duration` 不是时间点，
 * 按 `EPOCH + duration` 处理，只作为兜底存在。
 */
private fun Any.toInstant(): Instant = when (this) {
    is Instant -> this
    // DuckDB 把不带时区的 TIMESTAMP 当作 naive local-time；按 README §9 一律按 UTC 解释。
    // 不走 Timestamp.toInstant()（它会按 JVM 默认时区），改用 LocalDateTime -> UTC 路径。
    is Timestamp -> toLocalDateTime().toInstant(ZoneOffset.UTC)
    is OffsetDateTime -> toInstant()
    is LocalDateTime -> toInstant(ZoneOffset.UTC)
    is java.sql.Date -> toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
    is java.time.LocalDate -> atStartOfDay().toInstant(ZoneOffset.UTC)
    is Number -> Instant.ofEpochMilli(this.toLong())
    else -> throw IllegalStateException("Cannot coerce $this (${this::class}) to Instant")
}