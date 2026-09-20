package com.kxxnzstdsw.sync_diff.core

/**
 * 一次对账的聚合结果：源端 / 目标端行数 + 实际产生差异的主键数。
 *
 * - [srcCount] / [tgtCount] 是参与比对的两侧行数（来自 L1 聚合或 stream 后计数）。
 * - [diffCount] 是非 [FieldDiff.Equal] 的主键条数。
 * - [level]    是本次对账最严重的一档：INFO < WARN < ERROR。
 *
 * 用 [accumulate] 把 [DiffRow] 流折叠成 [DiffSummary]，避免在调用方手写 reduce。
 */
data class DiffSummary(
    val srcCount: Long,
    val tgtCount: Long,
    val diffCount: Long,
    val level: DiffLevel,
) {
    /** 是否需要上报告警。 */
    val hasDiff: Boolean get() = diffCount > 0L

    companion object {
        /**
         * 初始空状态：双侧 0 行、零差异、INFO。
         *
         * 用途是表示"还没有任何结果"，例如流式消费前先占位，或让 Reporter 在无数据分支也能
         * 拿到一个合法对象：
         *
         * ```kotlin
         * var acc: DiffSummary = DiffSummary.EMPTY
         * report.webhook(env("ALERT_URL"), DiffSummary.EMPTY)   // 空 url / 零差异 → no-op
         * ```
         *
         * 注意：它**不是** [accumulate] 的单位元——两个 [DiffSummary] 之间没有合并运算，
         * [accumulate] 要的是"两侧行数 + [DiffRow] 流"，不是"两个 summary"。
         */
        val EMPTY: DiffSummary = DiffSummary(0L, 0L, 0L, DiffLevel.INFO)

        /**
         * 把 [DiffRow] 流累计成 [DiffSummary]：一次遍历，边数差异主键边定 level。
         *
         * - `srcCount` / `tgtCount` 是**已经知道**的两侧行数（通常是 Connector 直接吐出 /
         *   stream 后计数）；本函数不会回读 [rows] 去数行，传错就是错。
         * - [rows] 是每条主键的差异描述；含非 [FieldDiff.Equal] 项的 [DiffRow] 记作 1 个差异主键，
         *   所以 [diffCount] 的单位是**主键条数**而不是字段数。只有 [FieldDiff.Equal] 的
         *   [DiffRow] 完全不计。
         * - [level] 取所有出现过差异里最严重的一档：[FieldDiff.Missing] → [DiffLevel.ERROR]、
         *   [FieldDiff.Mismatch] → [DiffLevel.WARN]；全程无差异则保持 [DiffLevel.INFO]。
         *   这是保守估计（只看差异种类，不看业务严重度）；要更细的档位请在 Reporter 侧按行自判。
         *
         * ```kotlin
         * val summary = DiffSummary.accumulate(
         *     srcCount = 10,
         *     tgtCount = 12,
         *     rows = sequenceOf(
         *         DiffRow(mapOf("k" to 1), listOf(FieldDiff.Equal)),
         *         DiffRow(mapOf("k" to 2), listOf(FieldDiff.Mismatch("amount", 1, 2))),
         *         DiffRow(mapOf("k" to 3), listOf(FieldDiff.Missing("phone", Side.TGT))),
         *     ),
         * )
         * summary.diffCount   // 2 —— 两个主键有差异，Equal 那条不计
         * summary.level       // DiffLevel.ERROR —— Missing 盖过 Mismatch
         * summary.hasDiff     // true（只看 diffCount > 0）
         * ```
         *
         * 注意：[rows] 是 [Sequence]，遍历即消耗；要复用就先 `toList()`（此时内存换可复读）。
         */
        fun accumulate(
            srcCount: Long,
            tgtCount: Long,
            rows: Sequence<DiffRow>,
        ): DiffSummary {
            var diffs_ = 0L
            var worst = DiffLevel.INFO
            rows.forEach { row ->
                if (!row.hasDiff) return@forEach
                diffs_++
                row.diffs.forEach { d ->
                    worst = when (d) {
                        is FieldDiff.Missing -> maxOf(worst, DiffLevel.ERROR)
                        is FieldDiff.Mismatch -> maxOf(worst, DiffLevel.WARN)
                        FieldDiff.Equal -> worst
                    }
                }
            }
            return DiffSummary(srcCount, tgtCount, diffs_, worst)
        }
    }
}