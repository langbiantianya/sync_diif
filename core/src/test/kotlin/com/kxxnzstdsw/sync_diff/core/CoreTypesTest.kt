package com.kxxnzstdsw.sync_diff.core

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.*

class CoreTypesTest {

    private val now: Instant = Instant.parse("2026-09-20T00:00:00Z")
    private val later: Instant = Instant.parse("2026-09-20T01:00:00Z")

    private fun rowOf(vararg pairs: Pair<String, Any?>): Row = Row(mapOf(*pairs))

    // --- Row ---

    @Test
    fun `row operator get returns values map entry`() {
        val row = rowOf("id" to 42, "name" to "alice")
        assertEquals(42, row["id"])
        assertEquals("alice", row["name"])
    }

    @Test
    fun `row getAs casts when type matches and returns null otherwise`() {
        val row = rowOf("n" to 1, "s" to "x", "big" to BigDecimal("3.14"))
        assertEquals(1, row.getAs<Int>("n"))
        assertEquals("x", row.getAs<String>("s"))
        assertEquals(BigDecimal("3.14"), row.getAs<BigDecimal>("big"))
        // wrong type
        assertNull(row.getAs<String>("n"))
        // missing
        assertNull(row.getAs<Int>("absent"))
    }

    @Test
    fun `row invoke returns default for missing key and overrides null`() {
        val row = rowOf("present" to "value", "blank" to null)
        assertEquals("value", row("present"))
        // null stored explicitly is returned as-is (null != default isn't triggered)
        assertNull(row("blank"))
        assertEquals("fallback", row("missing", default = "fallback"))
        assertNull(row("missing"))
    }

    @Test
    fun `row str and columns expose the underlying map`() {
        val row = rowOf("a" to 1, "b" to "x")
        assertEquals(setOf("a", "b"), row.columns)
        assertEquals(row.values, row.str)
    }

    @Test
    fun `row typed extensions return values via lambda form`() {
        val row = rowOf(
            "i" to 7,
            "l" to 9L,
            "d" to BigDecimal("1.50"),
            "t" to now,
            "s" to "hello",
        )
        assertEquals(7, row.intVal("i"))
        assertEquals(9L, row.longVal("l"))
        assertEquals(BigDecimal("1.50"), row.decimal("d"))
        assertEquals(now, row.instant("t"))
        assertEquals("hello", row.string("s"))
    }

    @Test
    fun `row firstValue returns the first stored value (primary key helper)`() {
        val row = rowOf("id" to "order-1", "amount" to 100)
        assertEquals("order-1", row.firstValue)
    }

    // --- FieldDiff ---

    @Test
    fun `fielddiff sealed hierarchy - equality and when-exhaustive`() {
        val equal1: FieldDiff = FieldDiff.Equal
        val equal2: FieldDiff = FieldDiff.Equal
        val mismatch = FieldDiff.Mismatch("amount", 1, 2)
        val missing = FieldDiff.Missing("phone", Side.SRC)

        // data object Equal
        assertEquals(equal1, equal2)
        assertEquals(equal1.hashCode(), equal2.hashCode())

        // data class equality
        assertEquals(FieldDiff.Mismatch("amount", 1, 2), mismatch)
        assertEquals(FieldDiff.Missing("phone", Side.SRC), missing)

        // when exhaustive
        val kinds: List<String> = listOf(equal1, mismatch, missing).map { d ->
            when (d) {
                FieldDiff.Equal -> "equal"
                is FieldDiff.Mismatch -> "mismatch"
                is FieldDiff.Missing -> "missing"
            }
        }
        assertEquals(listOf("equal", "mismatch", "missing"), kinds)
    }

    // --- DiffRow ---

    @Test
    fun `diffrow hasDiff false when only Equal entries present`() {
        val row = DiffRow(
            key = mapOf("id" to 1),
            diffs = listOf(FieldDiff.Equal, FieldDiff.Equal),
        )
        assertFalse(row.hasDiff)
    }

    @Test
    fun `diffrow hasDiff true when any non-Equal entry present`() {
        val row = DiffRow(
            key = mapOf("id" to 1),
            diffs = listOf(FieldDiff.Equal, FieldDiff.Mismatch("amount", 1, 2)),
        )
        assertTrue(row.hasDiff)
    }

    // --- DiffSummary ---

    @Test
    fun `diffsummary accumulate counts rows with non-equal diffs and picks worst level`() {
        val rows = sequenceOf(
            DiffRow(mapOf("k" to 1), listOf(FieldDiff.Equal, FieldDiff.Equal)),
            DiffRow(mapOf("k" to 2), listOf(FieldDiff.Mismatch("a", 1, 2))),
            DiffRow(mapOf("k" to 3), listOf(FieldDiff.Missing("b", Side.TGT))),
        )
        val s = DiffSummary.accumulate(srcCount = 10, tgtCount = 12, rows = rows)
        assertEquals(10L, s.srcCount)
        assertEquals(12L, s.tgtCount)
        assertEquals(2L, s.diffCount)
        assertEquals(DiffLevel.ERROR, s.level)
        assertTrue(s.hasDiff)
    }

    @Test
    fun `diffsummary accumulate stays INFO when only Equal entries`() {
        val rows = sequenceOf(
            DiffRow(mapOf("k" to 1), listOf(FieldDiff.Equal)),
        )
        val s = DiffSummary.accumulate(3, 4, rows)
        assertEquals(0L, s.diffCount)
        assertEquals(DiffLevel.INFO, s.level)
        assertFalse(s.hasDiff)
    }

    @Test
    fun `diffsummary empty constant is zero-zero-zero-info`() {
        val e = DiffSummary.EMPTY
        assertEquals(0L, e.srcCount)
        assertEquals(0L, e.tgtCount)
        assertEquals(0L, e.diffCount)
        assertEquals(DiffLevel.INFO, e.level)
        assertFalse(e.hasDiff)
    }

    // --- SequenceExt.minus ---

    @Test
    fun `sequence minus removes elements present in the other sequence`() {
        val a = sequenceOf(1, 2, 3, 4, 5)
        val b = sequenceOf(2, 4, 6)
        assertEquals(listOf(1, 3, 5), a.minus(b).toList())
    }

    @Test
    fun `sequence minus with empty other returns all elements`() {
        val a = sequenceOf("a", "b", "c")
        val b = emptySequence<String>()
        assertEquals(listOf("a", "b", "c"), a.minus(b).toList())
    }

    @Test
    fun `sequence minus stays lazy`() {
        // upstream is infinite; minus must not force it into a list
        val naturals = generateSequence(0) { it + 1 }
        val excluded = generateSequence(0) { it + 1 }.take(3) // 0,1,2
        val first10 = naturals.minus(excluded).take(10).toList()
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12), first10)
    }

    // --- SequenceExt.sampled ---

    @Test
    fun `sequence sampled with zero ratio returns empty`() {
        val sampled = sequenceOf(1, 2, 3, 4, 5).sampled(ratio = 0.0, seed = 1).toList()
        assertTrue(sampled.isEmpty())
    }

    @Test
    fun `sequence sampled with full ratio returns all elements in order`() {
        val sampled = sequenceOf("a", "b", "c").sampled(ratio = 1.0, seed = 7).toList()
        assertEquals(listOf("a", "b", "c"), sampled)
    }

    @Test
    fun `sequence sampled with fixed seed is deterministic`() {
        val first = (1..100).asSequence().sampled(ratio = 0.5, seed = 42).toList()
        val second = (1..100).asSequence().sampled(ratio = 0.5, seed = 42).toList()
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
        assertTrue(first.size < 100)
    }

    // --- FieldRules DSL (§11.2 builder form is the canonical one) ---

    @Test
    fun `fieldrules builder form registers rules via field name with rule-factory block`() {
        // §11.2 形式：`field("amount") { tolerance(abs = 0.1) }`
        val rules = FieldRules().apply {
            field("amount") { tolerance(abs = 0.1) }
            field("phone") { phoneNumber() }
            field("status") { ignore() }
            field("ts") { toUtc }
            field("tax") { amountWithTax(BigDecimal("1.06")) }
        }
        assertEquals(setOf("amount", "phone", "status", "ts", "tax"), rules.declared)
        // tolerance 0.1
        assertTrue(rules.ruleFor("amount")!!(0.5, 0.55))
        assertFalse(rules.ruleFor("amount")!!(0.5, 0.7))
        // phoneNumber ignores separators
        assertTrue(rules.ruleFor("phone")!!("+86 138-0013-8000", "8613800138000"))
        assertFalse(rules.ruleFor("phone")!!("13800138000", "13800138001"))
        // ignore always true
        assertTrue(rules.ruleFor("status")!!("anything", "else"))
        // toUtc uses ==
        assertTrue(rules.ruleFor("ts")!!(now, now))
        assertFalse(rules.ruleFor("ts")!!(now, later))
        // amountWithTax
        val r = rules.ruleFor("tax")!!
        assertTrue(r(BigDecimal("100"), BigDecimal("106.0")))
        assertFalse(r(BigDecimal("100"), BigDecimal("107")))
    }

    @Test
    fun `fieldrules builder form registers multiple rules via field name with rule-factory block`() {
        // §11.2 builder 形式：`field("name") { ruleFactory() }`
        val rules = FieldRules().apply {
            field("amount") { tolerance(abs = 0.01) }
            field("x") { custom { a, b -> a == b } }
        }
        assertEquals(2, rules.declared.size)
        assertTrue(rules.ruleFor("amount")!!(1.0, 1.005))
        assertFalse(rules.ruleFor("amount")!!(1.0, 1.02))
        assertTrue(rules.ruleFor("x")!!("a", "a"))
    }

    @Test
    fun `fieldrules custom via builder block returns a Function2`() {
        val rules = FieldRules().apply {
            field("lower") { custom { a, b -> a?.toString()?.lowercase() == b?.toString()?.lowercase() } }
        }
        assertTrue(rules.ruleFor("lower")!!("HELLO", "hello"))
        assertFalse(rules.ruleFor("lower")!!("HELLO", "world"))
    }

    @Test
    fun `fieldrules string-invoke-operator registers rule`() {
        val rules = FieldRules().apply {
            "x" { a, b -> a == b }
        }
        assertTrue(rules.ruleFor("x")!!(1, 1))
        assertFalse(rules.ruleFor("x")!!(1, 2))
    }

    @Test
    fun `fieldrules bind registers rule programmatically`() {
        val rules = FieldRules()
        rules.bind("y") { a, b -> (a as? Int)?.let { it > 0 } == (b as? Int)?.let { it > 0 } }
        assertTrue(rules.ruleFor("y")!!(1, 2))
        assertFalse(rules.ruleFor("y")!!(-1, 1))
    }

    @Test
    fun `fieldrules tolerance with both abs and rel`() {
        val t = FieldRules().tolerance(abs = 1.0, rel = 0.1)
        // 9 vs 10, base=10, rel*|10|=1, abs+rel=2.0, |9-10|=1 <= 2
        assertTrue(t(9, 10))
        // 100 vs 200, |100-200|=100, abs=1, rel=20, total=21
        assertFalse(t(100, 200))
    }

    @Test
    fun `fieldrules tolerance rejects non-Number sides`() {
        val t = FieldRules().tolerance(abs = 0.0, rel = 0.0)
        assertFalse(t("not a number", 1))
        assertFalse(t(1, null))
    }

    @Test
    fun `fieldrules can be built via the rules map constructor`() {
        val rules = FieldRules(mutableMapOf<String, (Any?, Any?) -> Boolean>())
        rules.run { "amount" { a, b -> a == b } }
        assertTrue(rules.ruleFor("amount")!!(1, 1))
    }

    @Test
    fun `fieldrules infix field-by form registers the same rules as the builder form`() {
        // §4.3 中缀形式与 §11.2 builder 形式必须等价。
        val infix = FieldRules().apply {
            field("amount") by tolerance(abs = 0.01)
            field("status") by ignore()
            field("phone") by phoneNumber()
            field("tax") by amountWithTax(BigDecimal("1.06"))
        }
        val builder = FieldRules().apply {
            field("amount") { tolerance(abs = 0.01) }
            field("status") { ignore() }
            field("phone") { phoneNumber() }
            field("tax") { amountWithTax(BigDecimal("1.06")) }
        }
        assertEquals(builder.declared, infix.declared)

        // 每个字段一组「源值 / 目标值 / 期望判定」，两种写法必须给出同一结论。
        val cases = listOf(
            Triple("amount", 100.004 to 100.0, true),                        // |差异| 0.004 ≤ 0.01
            Triple("amount", 100.5 to 100.0, false),                         // |差异| 0.5 > 0.01
            Triple("status", "PAID" to "CANCELLED", true),                   // ignore 恒真
            Triple("phone", "138-0013-8000" to "13800138000", true),         // 去分隔符后相等
            Triple("tax", BigDecimal("100") to BigDecimal("106.0"), true),   // 100 * 1.06 == 106.0
            Triple("tax", BigDecimal("100") to BigDecimal("107"), false),
        )
        cases.forEach { (field, values, expected) ->
            val (s, t) = values
            assertEquals(
                expected,
                infix.ruleFor(field)!!(s, t),
                "infix form: '$field' ($s vs $t)",
            )
            assertEquals(
                expected,
                builder.ruleFor(field)!!(s, t),
                "builder form: '$field' ($s vs $t)",
            )
        }
    }
}