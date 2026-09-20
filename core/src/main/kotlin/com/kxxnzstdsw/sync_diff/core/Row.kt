package com.kxxnzstdsw.sync_diff.core

import java.math.BigDecimal
import java.time.Instant

/**
 * 一行结果：键值对形式的通用载荷，key 是列名。
 * 下游 Connector 在 [values] 里只放已归一的 [Instant] / [BigDecimal] / [String] / [Number]。
 *
 * 三种取数方式语义不同，别混着猜用：
 *
 * - `row[name]`：原样返回 `Any?`，键不存在 → `null`，不做类型检查，调用方自己 cast。
 * - `row.getAs<T>(name)`：类型匹配才返回；类型不匹配或键不存在都得到 `null`，
 *   **不会**抛 `ClassCastException`——所以拿到的 `null` 无法区分"没这列"和"类型不对"。
 * - `row(name, default)`：键不存在**或值本身是 null** 时返回 `default`（内部是 `?:`），
 *   适合给可选列兜底，不适合用来判断列是否存在。
 *
 * ```kotlin
 * val row = Row(mapOf("order_id" to "o-1", "amount" to BigDecimal("100.00"), "note" to null))
 *
 * val raw: Any? = row["amount"]                  // BigDecimal("100.00")，原样，无转换
 * val big: BigDecimal? = row.getAs("amount")     // 类型对 → BigDecimal("100.00")
 * val bad: String? = row.getAs("amount")         // 类型不对 → null（不抛，也不 toString）
 * val note: Any? = row("note", "n/a")            // 存进去的 null 也走 default → "n/a"
 * val miss: Any? = row("absent", 0)              // 键不存在 → 0
 * ```
 *
 * 注意：`row(...)` 的默认值无法区分"列缺失"和"显式 null"——两者在 [values] 里都表现为
 * `values[name] == null`。需要区分时用 [columns] 判断 key 是否存在。
 */
data class Row(val values: Map<String, Any?>) {

    /**
     * 操作符索引：`row["amount"]`。键 [name] 不存在返回 `null`；值和类型都原样给出，不做归一。
     */
    operator fun get(name: String): Any? = values[name]

    /**
     * 类型安全的取数：`row.getAs<BigDecimal>("amount")`。[name] 不存在、或存的类型不是目标 `T`
     * 都返回 `null`，不抛 `ClassCastException`。
     */
    inline fun <reified T : Any> getAs(name: String): T? = values[name] as? T

    /**
     * 默认值兜底：`row("amount", 0)` 形式，替换 Map 的 `getOrDefault`——键不存在**或值本身是
     * null** 时返回 [default]。
     */
    operator fun invoke(name: String, default: Any? = null): Any? =
        values[name] ?: default
}

/**
 * 解构快捷访问：等价于 `values.values.firstOrNull()`，把"主键"作为首个值。
 *
 * 因为 [Row] 是 data class，编译器生成的 `component1()` 永远返回第一个构造属性 `values` 本身
 * （类型是 `Map<String, Any?>`），而且成员函数优先于扩展函数解析，外部**无法**用扩展把它覆盖成
 * "第一个值"。所以换个名字 `firstValue` 表达真实意图：
 *
 * ```kotlin
 * val row = Row(mapOf("order_id" to "order-1", "amount" to 100))
 * val pk: Any? = row.firstValue      // "order-1"
 * ```
 *
 * 契约：每个 [Row] 应当有且仅有一个主键列。实现是 `values.values.firstOrNull()`——
 * 结果依赖 map 的迭代顺序（`mapOf` / `LinkedHashMap` 保留插入顺序），空 `Row` 得到 `null`。
 * 多列主键不要用它，直接读对应列名。
 */
val Row.firstValue: Any? get() = values.values.firstOrNull()

/**
 * 原始 map，便于需要整体下沉的场景（序列化 / 调试 / 交给别的库）：
 *
 * ```kotlin
 * val payload: Map<String, Any?> = row.str
 * row.str == row.values       // 就是同一个 map，不是拷贝
 * ```
 *
 * 注意：拿到的是**内部引用**，别往里写（也不保证可变）；只读用途。
 */
val Row.str: Map<String, Any?> get() = values

/**
 * 列名集合，便于遍历 / 校验完整性：
 *
 * ```kotlin
 * val row = Row(mapOf("a" to 1, "b" to "x"))
 * row.columns                       // setOf("a", "b")
 * "b" in row.columns                // true —— 区分"列缺失"与"值为 null"的正确姿势
 * row.columns.filter { !it.startsWith("_") }
 * ```
 */
val Row.columns: Set<String> get() = values.keys

/**
 * 类型化取数 lambda：把 [getAs] 挂成 `(String) -> T?` 的形态，让"列名"能晚绑定——
 * 既可以链式 `rows.map { it.decimal("amount") }`，也可以把整列取数器当函数传出去：
 *
 * ```kotlin
 * val pick: (Row) -> BigDecimal? = { it.decimal("amount") }
 * rows.map { it.decimal("amount") }     // List<BigDecimal?>
 * val row = Row(mapOf("i" to 7, "amount" to BigDecimal("1.50")))
 * pick(row)                             // BigDecimal("1.50")
 * ```
 *
 * 语义与 [getAs] 完全一致：类型不匹配 / 列不存在一律**静默返回 `null`**，不抛异常，
 * 所以对账里"取不到值"和"值本身为 null"要靠 [columns] 区分。
 *
 * ```kotlin
 * row.intVal("i")       // 7
 * row.intVal("absent")  // null
 * row.decimal("i")      // null —— 存的不是 BigDecimal，静默落空
 * ```
 *
 * 注意：每个访问器属性都会新建一个捕获接收者的 lambda，热循环里先取出来复用，
 * 或直接写 `row.getAs<Int>(name)`。另外 `Int` 与 `Long` **不互通**：JDBC 的 `COUNT(*)` /
 * `BIGINT` 列归一后是 `Long`，用 [intVal] 取会得到 `null`，应改用 [longVal]。
 */
val Row.intVal: (String) -> Int? get() = { name -> getAs<Int>(name) }

/** [Long] 取数；语义与陷阱同 [intVal]，`COUNT(*)` / `BIGINT` 列走这个。 */
val Row.longVal: (String) -> Long? get() = { name -> getAs<Long>(name) }

/** [BigDecimal] 取数；DECIMAL / NUMERIC 列归一后的类型，别指望用 `Double` 取到它。 */
val Row.decimal: (String) -> BigDecimal? get() = { name -> getAs<BigDecimal>(name) }

/** [Instant] 取数；TIMESTAMP 列归一后的类型（时区处理已在 Connector 内完成）。 */
val Row.instant: (String) -> Instant? get() = { name -> getAs<Instant>(name) }

/** [String] 取数；VARCHAR / CHAR 列用。 */
val Row.string: (String) -> String? get() = { name -> getAs<String>(name) }