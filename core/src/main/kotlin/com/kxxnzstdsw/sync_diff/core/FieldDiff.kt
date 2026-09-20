package com.kxxnzstdsw.sync_diff.core

/**
 * 哪一侧在说话：源端 / 目标端。
 *
 * 在 [FieldDiff.Missing] 里，`side` 指的是**缺的那一侧**，不是"有值的那一侧"：
 * `Missing("phone", Side.TGT)` 读作"目标端没有 phone 这一列 / 这一行"。
 */
enum class Side { SRC, TGT }

/** 差异严重等级，决定后续 Reporter 怎么渲染 / 是否触发 webhook。 */
enum class DiffLevel { INFO, WARN, ERROR }

/**
 * 单字段比对结果。`when` 强制穷尽，避免漏写新增分支。
 *
 * - [Equal]      ：完全一致。
 * - [Mismatch]   ：值存在但不相等（容忍规则失败也算）。
 * - [Missing]    ：某侧没有这个字段 / 整行不存在。
 *
 * 消费端统一用 `when` 分派：[Equal] 是 `data object`，直接写名字；另外两个是 `data class`，
 * 用 `is` 并取出字段：
 *
 * ```kotlin
 * val line: String = when (val d = diffs.first()) {
 *     FieldDiff.Equal       -> "ok"
 *     is FieldDiff.Mismatch -> "${d.field}: ${d.expected} -> ${d.actual}"
 *     is FieldDiff.Missing  -> "${d.field} missing on ${d.side}"
 * }
 *
 * // 只关心"哪些列真的不一样"：
 * val bad: List<FieldDiff> = diffs.filter { it !is FieldDiff.Equal }
 * ```
 *
 * 注意：[DiffRow.diffs] 里会**保留** [Equal] 占位（便于 Reporter 画完整表格），
 * 所以遍历前通常先按上面那样过滤，别把每个元素都当差异处理。
 */
sealed interface FieldDiff {

    data object Equal : FieldDiff

    /**
     * 值存在但不相等（含规则判 false 的情况）。
     *
     * [expected] 恒为**源端**值、[actual] 恒为**目标端**值（`DiffEngine` 按
     * `Mismatch(field, s, t)` 构造），别按"谁对谁错"去理解这两个名字。
     */
    data class Mismatch(
        val field: String,
        val expected: Any?,
        val actual: Any?,
    ) : FieldDiff

    /**
     * 某侧整个缺了：缺列，或 L3 里整行不存在（此时 [field] 固定为 `"<row>"`）。
     *
     * [side] 是缺的一侧。L1 聚合只会对 key 列产出它（见 `DiffEngine.aggregate`），
     * 因为两侧都缺某 key 时会被直接跳过。
     */
    data class Missing(
        val field: String,
        val side: Side,
    ) : FieldDiff
}