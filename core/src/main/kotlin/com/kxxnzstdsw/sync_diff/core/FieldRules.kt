package com.kxxnzstdsw.sync_diff.core

import java.math.BigDecimal

/**
 * DSL 边界标记：同一个 lambda 里只允许最近的一层 [FieldRules] 作为隐式接收者，
 * 所以 `field("a") { tolerance(...) }` 的内层块不会意外解析到外层的 `field` / `tolerance`——
 * 误用由编译器直接拒绝，而不是静默绑到错列上。
 */
@DslMarker
annotation class CheckDsl

/**
 * 字段规则集合：声明"每一列怎么算对得上"。规则本体是 `(src, tgt) -> Boolean`，
 * 返回 true 判 [FieldDiff.Equal]，false 判 [FieldDiff.Mismatch]。
 *
 * ## 规则速查
 *
 * | 规则 | 语义 | 适用场景 |
 * | `tolerance(abs = 0.0, rel = 0.0)` | 数值带容差比较，判定式见下 | SUM / 浮点金额、两端口径有精度漂移的指标 |
 * | `ignore()` | 恒 true，整列不比 | 状态列、审计列、两端语义不同的列 |
 * | `toUtc` | 归一后 `==`（**属性，不是函数**） | TIMESTAMP 列；Connector 已把时区归一到 `Instant` |
 * | `custom(check)` | 直接塞一个自备 lambda | 大小写 / 多余空格等一次性规则 |
 * | `phoneNumber()` | 去掉非数字字符后比较 | 电话号码两端格式不一致 |
 * | `amountWithTax(rate)` | 源端不含税 × `rate` 后与目标端比较 | 目标端存的是含税价 |
 *
 * 数值容忍（`abs` 绝对容差、`rel` 相对容差）的判定式：`|s - t| <= abs + |t| * rel`，
 * 两侧各自取 `Number.toDouble()`；任一侧不是 [Number]（`String`、`null` 等）**直接判 false**，
 * 不会退化成字符串比较，也不会抛异常。`rel` 挂在**目标端** `t` 上，源端只出现在差值里。
 * 只写 `abs` 时 `rel = 0.0`，即绝对容差。
 *
 * 未声明规则的列默认按 `==` 比（`DiffEngine` 的行为），所以"少写一条规则"不是跳过该列，
 * 而是拿严格相等去撞——L1 的 SUM 尤其记得声明 [tolerance]。
 *
 * 两种写法完全等价，挑一种即可（同一列写两遍时后者覆盖前者）：
 *
 * ```kotlin
 * // builder 形式
 * diff.compare(s, t) {
 *     field("amount")     { tolerance(abs = 0.01) }
 *     field("updated_at") { toUtc }
 *     field("status")     { ignore() }
 *     field("phone")      { phoneNumber() }
 * }
 *
 * // 中缀形式：同一个 FieldRules，逐条对应
 * diff.compare(s, t) {
 *     field("amount")     by tolerance(abs = 0.01)
 *     field("updated_at") by toUtc
 *     field("status")     by ignore()
 *     field("phone")      by phoneNumber()
 * }
 * ```
 *
 * 注意：
 * - builder 块的**最后一个表达式**必须就是规则 lambda：写 `{ toUtc() }` 编译不过
 *   （`toUtc` 是属性）；要调试已声明的列用 [declared]。
 * - [phoneNumber] / [amountWithTax] 这类扩展规则定义在本包，跨包（如 `checks/`）要
 *   `import com.kxxnzstdsw.sync_diff.core.phoneNumber`。中缀 `field … by` 同样是本包的
 *   infix 扩展，跨包不方便导入时用 [bind] 兜底：`bind("amount", tolerance(abs = 0.01))`，
 *   语义完全一致（[FieldSlot.by] 内部就是调它）。
 * - 构造参数里的 map 会被**直接写入、不复制**，传入共享的 `MutableMap` 时需自行保证
 *   不被并发修改。
 */
@CheckDsl
class FieldRules(
    private val rules: MutableMap<String, (Any?, Any?) -> Boolean> = mutableMapOf(),
) {
    /**
     * 给字段追加 / 替换规则：`"amount".invoke(tolerance(abs = 0.01))`，也就是
     * `"amount"(tolerance(abs = 0.01))` ——最底层的注册入口。
     *
     * DSL（[field] builder / 中缀 [field]）最终都落到这里；日常对账不必直接调，除非要在
     * 循环里按数据动态生成规则表。
     */
    operator fun String.invoke(rule: (Any?, Any?) -> Boolean) {
        rules[this] = rule
    }

    /** [field]（builder 形式）实际调用的绑定入口（非 inline，避免 inline lambda 转发问题）。 */
    fun bind(name: String, rule: (Any?, Any?) -> Boolean) {
        rules[name] = rule
    }

    /**
     * 取出某列的规则；`DiffEngine` 逐列调用它，返回 `null` 表示**未声明**，
     * 此时引擎退回 `==`（见类 KDoc）。
     */
    fun ruleFor(name: String): ((Any?, Any?) -> Boolean)? = rules[name]

    /**
     * 所有声明过的字段名（不含未声明、走默认 `==` 的列），方便排错：
     *
     * ```kotlin
     * val fr = FieldRules().apply {
     *     field("amount") { tolerance(abs = 0.01) }
     * }
     * fr.declared        // setOf("amount")
     * ```
     */
    val declared: Set<String> get() = rules.keys

    /**
     * 数值容忍：`|s - t| <= abs + |t| * rel`；任一侧非 [Number] **直接判 false**。
     *
     * 判定式与适用场景见类 KDoc；这里看具体返回值：
     *
     * ```kotlin
     * val rule = FieldRules().tolerance(abs = 0.01)
     * rule(100.00, 100.005)   // true  —— 差 0.005，在 0.01 容差内
     * rule(100.00, 100.02)    // false —— 差 0.02，超出
     * rule(null, 100.0)       // false —— 任一侧不是 Number 一律不过
     * rule("100", 100.0)      // false —— 不帮你把字符串转成数字
     * ```
     *
     * 参数名 `abs` 是 README §4.3 的公开 API（`tolerance(abs = 0.01)`），
     * 会遮蔽 `kotlin.math.abs`；函数体内一律用全限定名调用，避免解析歧义。
     */
    fun tolerance(abs: Double = 0.0, rel: Double = 0.0): (Any?, Any?) -> Boolean = { s, t ->
        val a = (s as? Number)?.toDouble()
        val b = (t as? Number)?.toDouble()
        a != null && b != null &&
            kotlin.math.abs(a - b) <= abs + kotlin.math.abs(b) * rel
    }

    /**
     * 完全跳过此字段的比对：恒 true。
     *
     * ```kotlin
     * field("status") { ignore() }        // 或 field("status") by ignore()
     * ```
     *
     * 注意它只影响"两侧都有值"的判定：一侧整列缺失时 [FieldDiff.Missing] 在规则之前就已经
     * 定下来了，`ignore()` 掩不掉。
     */
    fun ignore(): (Any?, Any?) -> Boolean = { _, _ -> true }

    /**
     * 时区归一后的相等性：就是 `==`。
     *
     * 类型归一在 Connector 内已经做完（见 README §9，TIMESTAMP → `Instant`），所以这里不再做
     * 任何解析；声明它只是让"这一列按归一后的时刻比"显式可读。
     *
     * 它是**属性不是函数**：builder 块写 `field("updated_at") { toUtc }`（不加括号），
     * 中缀写 `field("updated_at") by toUtc`。
     */
    val toUtc: (Any?, Any?) -> Boolean
        get() = { s, t -> s == t }

    /**
     * 自定义规则：把手写的 `(Any?, Any?) -> Boolean` 直接塞进来，语义与 [tolerance] /
     * [ignore] 平级。
     *
     * ```kotlin
     * diff.compare(s, t) {
     *     field("order_id") {
     *         custom { a, b -> a?.toString()?.lowercase() == b?.toString()?.lowercase() }
     *     }
     * }
     * ```
     *
     * 不会做任何预处理（`null` 会原样传进 lambda），需要自己兜；规则一旦有业务含义且会被
     * 多处复用，建议按 [phoneNumber] / [amountWithTax] 的样式抽成 [FieldRules] 的扩展函数，
     * 便于单测与命名。
     */
    fun custom(check: (Any?, Any?) -> Boolean): (Any?, Any?) -> Boolean = check
}

/**
 * §11.2 builder 形式：`field("amount") { tolerance(abs = 0.01) }`。
 *
 * [name] 是列名（与 [Row] 的 key 一致，大小写敏感）；[init] 的接收者是本 [FieldRules]，
 * 块里最后一个表达式必须是 `(Any?, Any?) -> Boolean` 形态——返回 [tolerance] / [ignore] /
 * [phoneNumber] 等规则 lambda 即可，返回别的类型由编译器拒绝，不会静默注册错规则。
 *
 * ```kotlin
 * diff.compare(s, t) {
 *     field("amount")     { tolerance(abs = 0.01) }
 *     field("updated_at") { toUtc }              // 属性，不写括号
 *     field("status")     { ignore() }
 *     field("phone")      { phoneNumber() }
 * }
 * ```
 *
 * 实现就是 `bind(name, init())`：同一列声明两次时**后者覆盖前者**，未声明的列不受影响
 * （引擎退回 `==`）。等价的中缀写法见 [FieldSlot.by]。
 */
inline fun FieldRules.field(name: String, init: FieldRules.() -> (Any?, Any?) -> Boolean) {
    bind(name, init())
}

/**
 * §4.3 中缀形式：`field("amount") by tolerance(abs = 0.01)`——`field(...)` 只负责"选中这一列"，
 * 真正的绑定由右边的 [FieldSlot.by] 完成。
 *
 * 与 builder 形式（`field("amount") { tolerance(...) }`）等价，只是读起来更像 DSL。
 * 必须在 [FieldRules] 接收者作用域内（即 `compare { }` / `aggregate(… ) { }` 的尾块里）：
 *
 * ```kotlin
 * diff.compare(s, t) {
 *     field("amount")     by tolerance(abs = 0.01)
 *     field("updated_at") by toUtc
 *     field("status")     by ignore()
 *     field("phone")      by phoneNumber()
 * }
 * ```
 *
 * 注意：`field("amount")` 只是构造 [FieldSlot]，漏写 `by` 不会报错——那一列会退回 `==` 比较，
 * 得到的是"看起来声明了、其实没生效"的静默差异。
 */
infix fun FieldRules.field(name: String): FieldSlot = FieldSlot(this, name)

/**
 * [FieldRules.field] 的中缀载体：把 `by` 挂到字段名上，让 `field("x") by rule` 读成一句话。
 *
 * 构造是 `internal` 的，调用方只会经由 [FieldRules.field] 拿到它，不需要自己 new。
 */
class FieldSlot internal constructor(
    private val rules: FieldRules,
    private val name: String,
) {
    /** 把 [check] 绑到 [name]；语义与 [FieldRules.bind] 完全一致（就是转发给它）。 */
    infix fun by(check: (Any?, Any?) -> Boolean) {
        rules.bind(name, check)
    }
}

// --- 扩展规则（业务语义挂到原生类型上，与 §4.2 风格一致） ---

/**
 * 手机号：忽略非数字字符后再比较——`+86 138-0000-0000` 与 `8613800000000` 视为相同。
 *
 * ```kotlin
 * diff.compare(s, t) { field("phone") { phoneNumber() } }
 * ```
 *
 * 注意：不做国家码 / 长度校验，只做"数字序列是否一致"；而且 `as? String` 会把非字符串
 * （含 `null`）一律折成 `null`，于是两个非字符串值会被判成相等，而 `null` 与任何字符串不等。
 * 另外纯符号串（`"+"`）与 `"abc"` 去字符后都是空串，也会判等。
 */
fun FieldRules.phoneNumber(): (Any?, Any?) -> Boolean = { s, t ->
    (s as? String)?.filter(Char::isDigit) == (t as? String)?.filter(Char::isDigit)
}

/**
 * 含税金额：源端不含税、目标端含税，按 `rate` 折算后比较（`s * rate` 对上 `t`）。
 *
 * 用 [BigDecimal.compareTo] 而非 `==`：`BigDecimal` 的 `equals` 连 scale 一起比，
 * `100 * 1.06` 得到 `106.00`（scale 2），与 `106.0`（scale 1）数值相等却 `equals == false`。
 * `compareTo(...) == 0` 只比数值，正是对账想要的语义。
 *
 * ```kotlin
 * diff.compare(s, t) {
 *     field("amount_taxed") { amountWithTax(BigDecimal("1.06")) }
 * }
 * ```
 *
 * 任一侧不是 [BigDecimal]（`null`、`Double`、`Long` 等）直接判 false——金额列应当已被
 * Connector 归一成 [BigDecimal]；`rate` 的 scale 与舍入由调用方负责，这里不做 `setScale`。
 */
fun FieldRules.amountWithTax(rate: BigDecimal): (Any?, Any?) -> Boolean = { s, t ->
    val a = s as? BigDecimal
    val b = t as? BigDecimal
    a != null && b != null && a.multiply(rate).compareTo(b) == 0
}