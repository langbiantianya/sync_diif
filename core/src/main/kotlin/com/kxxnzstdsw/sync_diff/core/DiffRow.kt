package com.kxxnzstdsw.sync_diff.core

/**
 * 单条主键对应的全部字段差异。
 *
 * - [key]  ：拼成的主键值；多列主键拼成一个 `Map<String, Any?>`。`mapOf(...)` 保留书写/插入
 *   顺序，所以 `mapOf("dt" to …, "order_id" to …)` 与 `aggregate(…, keys = listOf("dt", "order_id"))`
 *   逐列对应。单列主键就是只有一个条目的 map。
 * - [diffs]：该主键下每列的 [FieldDiff] 列表；[FieldDiff.Equal] 仍保留（便于 Reporter 画完整
 *   表格），要只拿差异自己 `filter`。
 * - [hasDiff]：惰性判定的"是否真有差异"，见下。
 *
 * ```kotlin
 * val row = DiffRow(
 *     key = mapOf("dt" to "2026-09-01", "order_id" to "o-1"),
 *     diffs = listOf(FieldDiff.Equal, FieldDiff.Mismatch("amount", 100, 99)),
 * )
 *
 * row.key["order_id"]                            // "o-1"
 * row.diffs.size                                 // 2 —— Equal 也算一项
 * row.diffs.count { it !is FieldDiff.Equal }     // 1 —— 真正不一样的列
 * row.hasDiff                                    // true
 * ```
 *
 * 注意：两侧该主键完全一致时 [diffs] 只剩 [FieldDiff.Equal]，`aggregate` / `compareRows`
 * 照样会 emit 这个 [DiffRow]（下游可按 [hasDiff] 自行过滤）；[hasDiff] 是 `by lazy`，
 * 首次访问才计算并缓存，[DiffRow] 不可变所以缓存不会失效，可放心跨线程读。
 */
data class DiffRow(
    val key: Map<String, Any?>,
    val diffs: List<FieldDiff>,
) {
    /** 是否真存在差异（[FieldDiff.Equal] 不算）。 */
    val hasDiff: Boolean by lazy { diffs.any { it !is FieldDiff.Equal } }
}