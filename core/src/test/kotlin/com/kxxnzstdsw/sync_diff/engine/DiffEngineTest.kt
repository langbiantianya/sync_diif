package com.kxxnzstdsw.sync_diff.engine

import com.kxxnzstdsw.sync_diff.core.FieldDiff
import com.kxxnzstdsw.sync_diff.core.FieldRules
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.Side
import kotlin.test.*

/**
 * DiffEngine 的核心契约：aggregate / compare / compareRows / summary。
 *
 * 不引入 mock / 不引入 Spring：所有 Row 都是 data class，引擎是纯逻辑。
 *
 * DSL 用法说明：`"x" field by <rule>` 的 `by` 是 `core` 包内的 infix 扩展，跨包调用
 * 受 `by` 是 Kotlin 关键字的限制无法 `import`。这里测试全部走 `FieldRules.bind(name, rule)`
 * ——和 `field by` 完全等价（[FieldRules.bind] 就是 `field by` 的绑定入口），仅绕过解析层。
 */
class DiffEngineTest {

    // -------- aggregate --------

    @Test
    fun `aggregate equal rows produces only Equal diffs and no count`() {
        val engine = DiffEngine()
        val src = listOf(row("dt" to "2026-09-01", "c" to 100L))
        val tgt = listOf(row("dt" to "2026-09-01", "c" to 100L))

        val rows = engine.aggregate(src, tgt, keys = listOf("dt"))

        assertEquals(1, rows.size)
        val diffs = rows.single().diffs
        assertEquals(setOf("dt"), rows.single().key.keys)
        // aggregate 只看 keys 列；都是 100L → 都是 Equal。
        assertTrue(diffs.all { it is FieldDiff.Equal }, "expected all Equal, got $diffs")
        assertFalse(rows.single().hasDiff)
        assertEquals(0L, engine.aggDiffCount)
    }

    @Test
    fun `aggregate divergent rows produce Mismatch and bump aggDiffCount`() {
        val engine = DiffEngine()
        val src = listOf(row("dt" to "2026-09-01", "c" to 100L))
        val tgt = listOf(row("dt" to "2026-09-01", "c" to 99L))

        val rows = engine.aggregate(src, tgt, keys = listOf("dt"))

        val row = rows.single()
        assertTrue(row.hasDiff)
        val m = row.diffs.filterIsInstance<FieldDiff.Mismatch>().single()
        assertEquals("c", m.field)
        assertEquals(100L, m.expected)
        assertEquals(99L, m.actual)
        assertEquals(1L, engine.aggDiffCount)
    }

    @Test
    fun `aggregate with key missing on one side produces Missing diffs per key column`() {
        val engine = DiffEngine()
        val src = listOf(row("dt" to "2026-09-01", "c" to 1L))
        val tgt = emptyList<Row>()

        val rows = engine.aggregate(src, tgt, keys = listOf("dt"))

        val diffs = rows.single().diffs
        val missing = diffs.filterIsInstance<FieldDiff.Missing>()
        // aggregate 只对 `keys` 列产生 Missing（不是整行的所有列）。
        assertEquals(1, missing.size)
        assertEquals("dt", missing.single().field)
        assertEquals(Side.TGT, missing.single().side)
        assertTrue(rows.single().hasDiff)
        assertEquals(1L, engine.aggDiffCount)
    }

    @Test
    fun `aggregate with two distinct keys aligns correctly`() {
        val engine = DiffEngine()
        val src = listOf(
            row("dt" to "2026-09-01", "c" to 10L),
            row("dt" to "2026-09-02", "c" to 20L),
        )
        val tgt = listOf(
            row("dt" to "2026-09-01", "c" to 10L), // equal
            row("dt" to "2026-09-02", "c" to 21L), // divergent
        )

        val rows = engine.aggregate(src, tgt, keys = listOf("dt"))
        val byKey = rows.associateBy { it.key["dt"] }

        assertEquals(2, rows.size)
        assertTrue(byKey["2026-09-01"]!!.diffs.all { it is FieldDiff.Equal })
        assertFalse(byKey["2026-09-01"]!!.hasDiff)
        assertTrue(byKey["2026-09-02"]!!.hasDiff)
        assertEquals(1L, engine.aggDiffCount)
    }

    // -------- compare --------

    @Test
    fun `compare with no rules uses structural equality`() {
        val engine = DiffEngine()
        val s = row("a" to 1, "b" to "x")
        val t = row("a" to 1, "b" to "x")

        val diffs = engine.compare(s, t) {}

        assertTrue(diffs.all { it is FieldDiff.Equal }, "expected all Equal, got $diffs")
    }

    @Test
    fun `compare with tolerance rule tolerates numeric drift`() {
        val engine = DiffEngine()
        val s = row("amount" to 100.00)
        val t = row("amount" to 100.005) // 差 0.005

        // abs=0.01 的容忍：通过 FieldRules.bind() 注入，等价于 "amount" field by tolerance(abs=0.01)。
        val toleranceRule = FieldRules().tolerance(abs = 0.01)
        val diffs = engine.compare(s, t) {
            bind("amount", toleranceRule)
        }

        val amountDiff = diffs.single()
        assertIs<FieldDiff.Equal>(amountDiff, "tolerance(abs=0.01) should accept 0.005 drift")
    }

    @Test
    fun `compare without rule fails on numeric drift`() {
        val engine = DiffEngine()
        val s = row("amount" to 100.00)
        val t = row("amount" to 100.005)

        val diffs = engine.compare(s, t) {}

        val m = diffs.filterIsInstance<FieldDiff.Mismatch>().single()
        assertEquals("amount", m.field)
    }

    @Test
    fun `compare reports Missing when src is missing a column tgt has`() {
        val engine = DiffEngine()
        val s = row("a" to 1)
        val t = row("a" to 1, "b" to 2)

        val diffs = engine.compare(s, t) {}

        val missing = diffs.filterIsInstance<FieldDiff.Missing>().single()
        assertEquals("b", missing.field)
        assertEquals(Side.SRC, missing.side)
    }

    @Test
    fun `compare reports Missing when tgt is missing a column src has`() {
        val engine = DiffEngine()
        val s = row("a" to 1, "b" to 2)
        val t = row("a" to 1)

        val diffs = engine.compare(s, t) {}

        val missing = diffs.filterIsInstance<FieldDiff.Missing>().single()
        assertEquals("b", missing.field)
        assertEquals(Side.TGT, missing.side)
    }

    // -------- compareRows --------

    @Test
    fun `compareRows emits Missing on src when tgt has extra key`() {
        val engine = DiffEngine()
        val src = sequenceOf(row("id" to 1))
        val tgt = sequenceOf(row("id" to 1), row("id" to 2))

        val pairs = engine.compareRows(src, tgt, key = { it["id"]!! }) { s, t ->
            listOf<FieldDiff>(FieldDiff.Equal)
        }.toList()

        assertEquals(2, pairs.size)
        val byKey = pairs.associateBy { it.first }
        assertEquals(1, byKey[1]!!.second.size)
        assertIs<FieldDiff.Equal>(byKey[1]!!.second.single())

        val missing = byKey[2]!!.second.single() as FieldDiff.Missing
        assertEquals("<row>", missing.field)
        assertEquals(Side.SRC, missing.side)
        assertEquals(1L, engine.rowDiffCount)
        assertEquals(1L, engine.srcRowCount)
        assertEquals(2L, engine.tgtRowCount)
    }

    @Test
    fun `compareRows emits Missing on tgt when src has extra key`() {
        val engine = DiffEngine()
        val src = sequenceOf(row("id" to 1), row("id" to 2))
        val tgt = sequenceOf(row("id" to 1))

        val pairs = engine.compareRows(src, tgt, key = { it["id"]!! }) { s, t ->
            listOf<FieldDiff>(FieldDiff.Equal)
        }.toList()

        assertEquals(2, pairs.size)
        val missingSide = pairs.single { it.first == 2 }.second.single() as FieldDiff.Missing
        assertEquals(Side.TGT, missingSide.side)
        assertEquals(1L, engine.rowDiffCount)
        assertEquals(2L, engine.srcRowCount)
        assertEquals(1L, engine.tgtRowCount)
    }

    @Test
    fun `compareRows invokes check on matching keys and counts non-Equal diffs`() {
        val engine = DiffEngine()
        val src = sequenceOf(row("id" to 1, "v" to "old"), row("id" to 2, "v" to "same"))
        val tgt = sequenceOf(row("id" to 1, "v" to "new"), row("id" to 2, "v" to "same"))

        val pairs = engine.compareRows(src, tgt, key = { it["id"]!! }) { s, t ->
            val sv = s["v"]; val tv = t["v"]
            if (sv == tv) listOf(FieldDiff.Equal)
            else listOf(FieldDiff.Mismatch("v", sv, tv))
        }.toList()

        assertEquals(2, pairs.size)
        val byKey = pairs.associateBy { it.first }
        val diff1 = byKey[1]!!.second.single() as FieldDiff.Mismatch
        assertEquals("v", diff1.field)
        assertEquals("old", diff1.expected)
        assertEquals("new", diff1.actual)

        assertIs<FieldDiff.Equal>(byKey[2]!!.second.single())
        // 仅 id=1 命中差异
        assertEquals(1L, engine.rowDiffCount)
        assertEquals(2L, engine.srcRowCount)
        assertEquals(2L, engine.tgtRowCount)
    }

    // -------- summary --------

    @Test
    fun `summary starts at EMPTY equivalent and flips hasDiff after a difference`() {
        val engine = DiffEngine()
        assertFalse(engine.summary().hasDiff, "fresh engine must report no diff")

        engine.aggregate(
            src = listOf(row("dt" to "x", "c" to 1L)),
            tgt = listOf(row("dt" to "x", "c" to 2L)),
            keys = listOf("dt"),
        )
        val s = engine.summary()
        assertTrue(s.hasDiff, "after divergent aggregate, hasDiff must flip true")
        assertEquals(1L, s.diffCount)
        assertEquals(0L, s.srcCount) // aggregate 不动 srcRowCount / tgtRowCount
        assertEquals(0L, s.tgtCount)
    }

    @Test
    fun `summary accumulates counts from compareRows`() {
        val engine = DiffEngine()
        val src = sequenceOf(row("id" to 1), row("id" to 2))
        val tgt = sequenceOf(row("id" to 1))

        engine.compareRows(src, tgt, key = { it["id"]!! }) { _, _ ->
            listOf(FieldDiff.Equal)
        }.toList() // 必须消费 Sequence，side effect 才生效

        val s = engine.summary()
        assertTrue(s.hasDiff)
        assertEquals(2L, s.srcCount)
        assertEquals(1L, s.tgtCount)
        assertEquals(1L, s.diffCount)
    }

    @Test
    fun `summary hasDiff stays false when only equal rows are compared`() {
        val engine = DiffEngine()
        engine.aggregate(
            src = listOf(row("dt" to "x", "c" to 1L)),
            tgt = listOf(row("dt" to "x", "c" to 1L)),
            keys = listOf("dt"),
        )
        engine.compareRows(
            src = sequenceOf(row("id" to 1)),
            tgt = sequenceOf(row("id" to 1)),
            key = { it["id"]!! },
            check = { _, _ -> listOf(FieldDiff.Equal) },
        ).toList()

        assertFalse(engine.summary().hasDiff)
    }

    // -------- keySet --------

    @Test
    fun `keySet returns symmetric difference and counts each emitted key`() {
        val engine = DiffEngine()
        val src = sequenceOf(row("k" to "a"), row("k" to "b"), row("k" to "c"))
        val tgt = sequenceOf(row("k" to "b"), row("k" to "d"))

        val out = engine.keySet(src, tgt, key = "k").toList()

        // a 仅 src, c 仅 src, d 仅 tgt → {a, c, d}（顺序：先 src 差，再 tgt 差）
        assertEquals(setOf("a", "c", "d"), out.toSet())
        assertEquals(3L, engine.keyDiffCount)
    }

    @Test
    fun `keySet emits a duplicated key once per side and scans each side once`() {
        val engine = DiffEngine()
        // 同一侧重复主键只算一次：L2 是"主键集合"的差集。
        val srcScans = mutableListOf<String>()
        val tgtScans = mutableListOf<String>()
        val src = sequenceOf("a", "a", "b").onEach { srcScans += it }.map { row("k" to it) }
        val tgt = sequenceOf("b", "c", "c").onEach { tgtScans += it }.map { row("k" to it) }

        val out = engine.keySet(src, tgt, key = "k").toList()

        assertEquals(listOf("a", "c"), out)
        assertEquals(2L, engine.keyDiffCount)
        // 关键：每侧只被拉一遍。若用方向相反的两个差额实现，这里会是 6 / 6。
        assertEquals(listOf("a", "a", "b"), srcScans)
        assertEquals(listOf("b", "c", "c"), tgtScans)
    }

    // -------- helpers --------

    /** DSL-friendly row builder：避免到处写 `mapOf("a" to 1, "b" to 2)`。 */
    private fun row(vararg pairs: Pair<String, Any?>): Row =
        Row(pairs.associate { it.first to it.second })
}
