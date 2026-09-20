package com.kxxnzstdsw.sync_diff.core

import java.util.*

/**
 * 序列差集：保留 [this] 中未出现在 `other` 里的元素。
 *
 * 实现为 `other.toHashSet().let { ex -> filter { it !in ex } }`，所以：
 *
 * - **`other` 在调用时就被消费掉并物化成 [HashSet]**，额外内存 `O(|other|)`；被减序列本身
 *   保持惰性。换句话说，"减数"那条流会在你还没遍历结果时就整条跑完，用它时要心里有数。
 * - 只做过滤、不去重：[this] 里重复的元素按原次数保留（要唯一先 `distinct()`）。
 * - 签名要求 `T : Any`（不接受 `T?`），可空列先 `mapNotNull`。
 *
 * L2 主键集合差的真实用法（`DiffEngine.keySet` 内部就是这一套）：
 *
 * ```kotlin
 * val srcKeys = src.stream("SELECT order_id FROM read_parquet('$path')")
 *     .mapNotNull { it.string("order_id") }
 * val tgtKeys = tgt.stream("SELECT order_id FROM ods.orders WHERE dt = '$dt'")
 *     .mapNotNull { it.string("order_id") }
 *
 * val onlySrc = srcKeys - tgtKeys     // 目标端漏掉的键
 * val onlyTgt = tgtKeys - srcKeys     // 上游多出来的键
 * ```
 *
 * 注意：上面两次 `-` 会让 `tgtKeys` 跑两遍、`srcKeys` 跑一遍（[Sequence] 每次终端操作都
 * 重新驱动上游，JDBC 实现即重跑 SQL）。要少跑一次就把两侧的键先落成一个 `Set` /
 * `toList()`；百万行级别的大表则相反——保留 [Sequence]，别为了省一次查询把整表物化。
 */
operator fun <T> Sequence<T>.minus(other: Sequence<T>): Sequence<T> where T : Any {
    val excluded = other.toHashSet()
    return filter { it !in excluded }
}

/**
 * 概率抽样：每条元素以 [ratio] 概率保留，用于"顶层 N 抽样"这类降采样。
 *
 * - **逐条独立判定**（`SplittableRandom.nextDouble() < ratio`），不是等距取样、也不是取前 N 条；
 *   输出条数期望是 `ratio * N`，但**不保证**精确条数。
 * - [seed] 默认固定为 `42L`：同一份输入 + 同一个 ratio，每次跑出来的样本完全一致，
 *   对账结论才可回归；要换一批样本就显式传别的 seed。
 * - 前提是**上游顺序稳定**，否则同一 seed 也会样出不同子集——先 `sortedBy` / SQL `ORDER BY`，
 *   或确认底层扫描顺序确定。
 * - 保持惰性：下游 `take(n)` / `first()` 会让上游提前停止，不会读完整个流。
 * - 边界：`ratio <= 0.0` 得到空序列；`ratio >= 1.0` 原样保留全部（`nextDouble()` 值域是 `[0, 1)`）。
 *
 * ```kotlin
 * val sample = src.stream("SELECT order_id, updated_at FROM read_parquet('$path')")
 *     .filter { it.instant("updated_at")!!.isAfter(cutoff) }
 *     .distinctBy { it.string("order_id") }
 *     .sampled(ratio = 0.01)      // 约 1% 的订单
 *     .take(1000)
 * ```
 *
 * 注意：**不要用它做分片对账**——两侧各自独立抽样会样出不同子集，缺行判定会全是假阳性。
 * 分片要靠 `WHERE hash(pk) % 100 < k` 这类两侧**同一哈希、同一分片规则**的 SQL 条件。
 */
fun <T> Sequence<T>.sampled(ratio: Double, seed: Long = 42L): Sequence<T> = sequence {
    val rng = SplittableRandom(seed)
    forEach { if (rng.nextDouble() < ratio) yield(it) }
}