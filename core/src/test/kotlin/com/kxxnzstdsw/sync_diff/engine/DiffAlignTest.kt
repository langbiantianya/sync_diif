package com.kxxnzstdsw.sync_diff.engine

import com.kxxnzstdsw.sync_diff.core.FieldDiff
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.Side
import kotlin.test.*

/**
 * [DiffEngine.align]（共用内核）与 [DiffEngine.reconcile]（自定义档）的契约。
 *
 * 这两个入口是"加一档自定义对账"的接口面：对齐顺序、缺行、重复键、惰性、计数口径在这里
 * 锁定；[DiffEngine.aggregate] / [DiffEngine.compareRows] 重建到内核上之后共用同一套语义。
 */
class DiffAlignTest {

    // -------- align：对齐语义 --------

    @Test
    fun `align yields tgt stream order first then src-only keys`() {
        val engine = DiffEngine()

        val out = engine.align(
            src = sequenceOf(row("id" to 1), row("id" to 2), row("id" to 3)),
            tgt = sequenceOf(row("id" to 3), row("id" to 1)),
            key = { it["id"]!! },
            onMissing = { _, side -> "missing:$side" },
            compare = { _, _ -> "both" },
        ).toList()

        // tgt 流序（3、1）在前，src 独有的 2 在最后。
        assertEquals(listOf(3, 1, 2), out.map { it.first })
        assertEquals(listOf("both", "both", "missing:TGT"), out.map { it.second })
    }

    @Test
    fun `align points Side at the side that lacks the row`() {
        val engine = DiffEngine()

        val out = engine.align(
            src = sequenceOf(row("id" to 1)),
            tgt = sequenceOf(row("id" to 2)),
            key = { it["id"]!! },
            onMissing = { _, side -> side },
            compare = { _, _ -> error("no key is shared here") },
        ).toList()

        val byKey = out.associate { it.first to it.second }
        assertEquals(Side.SRC, byKey[2], "tgt 独有 → 缺的是 src")
        assertEquals(Side.TGT, byKey[1], "src 独有 → 缺的是 tgt")
    }

    @Test
    fun `align compares the rows of a matched key`() {
        val engine = DiffEngine()

        val out = engine.align(
            src = sequenceOf(row("id" to 1, "v" to "s")),
            tgt = sequenceOf(row("id" to 1, "v" to "t")),
            key = { it["id"]!! },
            onMissing = { _, _ -> error("shared key must go through compare") },
            compare = { s, t -> s["v"] to t["v"] },
        ).single()

        assertEquals("s" to "t", out.second)
    }

    @Test
    fun `align keeps the first src row per key and emits every tgt duplicate`() {
        val engine = DiffEngine()

        val out = engine.align(
            src = sequenceOf(row("id" to 1, "v" to "first"), row("id" to 1, "v" to "second")),
            tgt = sequenceOf(row("id" to 1, "v" to "x"), row("id" to 1, "v" to "y")),
            key = { it["id"]!! },
            onMissing = { _, _ -> error("both tgt rows have a match") },
            compare = { s, _ -> s["v"] },
        ).toList()

        // src 重复键保留首次出现；tgt 重复键各自产出（同一个键出现两次）。
        assertEquals(listOf(1, 1), out.map { it.first })
        assertEquals(listOf("first", "first"), out.map { it.second })
    }

    @Test
    fun `align stays lazy and never touches the counters`() {
        val engine = DiffEngine()
        val srcScans = mutableListOf<Int>()
        val tgtScans = mutableListOf<Int>()

        val out = engine.align(
            src = sequenceOf(row("id" to 1)).onEach { srcScans += it["id"] as Int },
            tgt = sequenceOf(row("id" to 1)).onEach { tgtScans += it["id"] as Int },
            key = { it["id"]!! },
            onMissing = { _, side -> side },
            compare = { _, _ -> null },
        )

        // 只建序列不消费：两侧都不许被拉。
        assertTrue(srcScans.isEmpty() && tgtScans.isEmpty(), "序列未消费前不该读数据")

        assertEquals(1, out.count())
        assertEquals(listOf(1), srcScans)
        assertEquals(listOf(1), tgtScans)
        // 内核不碰计数器：行数 / 差异怎么记是各档自己的事（见 reconcile）。
        assertEquals(0L, engine.srcRowCount)
        assertEquals(0L, engine.tgtRowCount)
        assertEquals(0L, engine.customDiffCount)
        assertEquals(0L, engine.rowDiffCount)
    }

    // -------- reconcile：自定义档 --------

    @Test
    fun `reconcile feeds a custom diff row into the counters and the summary`() {
        val engine = DiffEngine()

        val diffs = engine.amountDiff(
            src = sequenceOf(row("id" to 1, "amount" to 10), row("id" to 2, "amount" to 20)),
            tgt = sequenceOf(row("id" to 1, "amount" to 10), row("id" to 3, "amount" to 30)),
        ).toList()

        val byKey = diffs.associate { it.first to it.second }
        assertEquals(3, diffs.size)
        assertEquals("amount 10 vs 10", byKey["1"]!!.detail)
        assertEquals("missing on TGT", byKey["2"]!!.detail, "src 独有 → 缺的是 tgt")
        assertEquals("missing on SRC", byKey["3"]!!.detail, "tgt 独有 → 缺的是 src")

        assertEquals(2L, engine.customDiffCount, "id=2 与 id=3 各算一次，id=1 不算")
        assertEquals(2L, engine.srcRowCount)
        assertEquals(2L, engine.tgtRowCount)

        val summary = engine.summary()
        assertEquals(2L, summary.diffCount)
        assertEquals(2L, summary.srcCount)
        assertEquals(2L, summary.tgtCount)
        assertTrue(summary.hasDiff)
    }

    @Test
    fun `reconcile counts nothing when isDiff reports no difference`() {
        val engine = DiffEngine()

        val diffs = engine.amountDiff(
            src = sequenceOf(row("id" to 1, "amount" to 10)),
            tgt = sequenceOf(row("id" to 1, "amount" to 10)),
        ).toList()

        assertEquals(1, diffs.size, "命中两侧都要产出，哪怕不算差异")
        assertEquals(0L, engine.customDiffCount)
        assertFalse(engine.summary().hasDiff)
    }

    @Test
    fun `reconcile leaves the missing-row verdict to isDiff`() {
        val engine = DiffEngine()

        // "缺行不算差异"是调用方的口径，引擎不猜：isDiff 说 false 就不计。
        engine.reconcile(
            src = sequenceOf(row("id" to 1)),
            tgt = emptySequence(),
            key = { it["id"]!! },
            onMissing = { k, side -> AmountDiff(k.toString(), "missing on $side", true) },
            compare = { _, _ -> error("no shared key") },
            isDiff = { false },
        ).toList()

        assertEquals(0L, engine.customDiffCount)
        assertFalse(engine.summary().hasDiff)
        assertEquals(1L, engine.srcRowCount, "行数照常统计")
    }

    @Test
    fun `reconcile stays lazy until the sequence is consumed`() {
        val engine = DiffEngine()

        val out = engine.amountDiff(
            src = sequenceOf(row("id" to 1, "amount" to 10)),
            tgt = sequenceOf(row("id" to 1, "amount" to 99)),
        )

        assertEquals(0L, engine.customDiffCount, "不消费不计数")
        assertEquals(1, out.count())
        assertEquals(1L, engine.customDiffCount)
    }

    // -------- 内核语义在 L1 上同样成立 --------

    @Test
    fun `aggregate follows the kernel row order and duplicate rule`() {
        val engine = DiffEngine()

        val rows = engine.aggregate(
            src = listOf(row("dt" to "src-only", "c" to 1L), row("dt" to "shared", "c" to 2L)),
            tgt = listOf(row("dt" to "shared", "c" to 2L), row("dt" to "tgt-only", "c" to 3L)),
            keys = listOf("dt"),
        )

        // 先 tgt 流序（shared、tgt-only），再 src 独有（src-only）。
        assertEquals(listOf("shared", "tgt-only", "src-only"), rows.map { it.key["dt"] })
        assertEquals(
            FieldDiff.Missing("dt", Side.TGT),
            rows.single { it.key["dt"] == "src-only" }.diffs.single(),
        )
        assertEquals(2L, engine.aggDiffCount, "两个单侧主键各算一次差异")
    }

    // -------- helpers --------

    /** 自定义口径的差异行：两侧都命中时比 amount，缺行单独记一条。 */
    private data class AmountDiff(val key: String, val detail: String, val isDiff: Boolean)

    private fun DiffEngine.amountDiff(src: Sequence<Row>, tgt: Sequence<Row>) = reconcile(
        src = src,
        tgt = tgt,
        key = { it["id"].toString() },
        onMissing = { k, side -> AmountDiff(k, "missing on $side", true) },
        compare = { s, t ->
            AmountDiff(
                key = s["id"].toString(),
                detail = "amount ${s["amount"]} vs ${t["amount"]}",
                isDiff = s["amount"] != t["amount"],
            )
        },
        isDiff = { it.isDiff },
    )

    /** DSL-friendly row builder（与 DiffEngineTest 一致）。 */
    private fun row(vararg pairs: Pair<String, Any?>): Row =
        Row(pairs.associate { it.first to it.second })
}
