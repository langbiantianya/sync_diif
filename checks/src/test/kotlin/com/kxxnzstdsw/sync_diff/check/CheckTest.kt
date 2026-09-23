package com.kxxnzstdsw.sync_diff.check

import com.kxxnzstdsw.sync_diff.check.Check.Args
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.*

/** 记录最近一次 [Ctx.args] 的探针 Check：用 `object` 自带字段存，测试之间不共享可变全局。 */
private object ArgsCaptureCheck : CheckBase("args_capture") {
    @Volatile
    var captured: Args? = null

    override suspend fun Ctx.run() {
        captured = args
    }
}

class CheckTest {

    @Test
    fun `check base exposes name from constructor`() {
        assertEquals("args_capture", ArgsCaptureCheck.name)
    }

    @Test
    fun `runWith injects Args into Ctx before run`() {
        val args = Args(dt = "2026-09-20", params = mapOf("k" to "v"))
        ArgsCaptureCheck.captured = null

        runBlocking { ArgsCaptureCheck.runWith(args) }

        assertEquals(args, ArgsCaptureCheck.captured)
    }

    @Test
    fun `registry register by name and lookup are reversible`() {
        val reg = CheckRegistry()
        reg.register("args_capture", ArgsCaptureCheck)

        assertSame(ArgsCaptureCheck, reg["args_capture"])
        assertEquals(listOf<Check>(ArgsCaptureCheck), reg.all())
    }

    @Test
    fun `registry re-registering the same name throws`() {
        val reg = CheckRegistry()
        reg.register("dup", ArgsCaptureCheck)

        assertFailsWith<IllegalStateException> { reg.register("dup", ArgsCaptureCheck) }
    }

    @Test
    fun `registry operator get returns null for an unknown name`() {
        assertEquals(null, CheckRegistry()["nope"])
    }

    @Test
    fun `Args defaults to today as an ISO date and no params`() {
        val args = Args()

        assertEquals(LocalDate.now(), LocalDate.parse(args.dt), "默认 dt 是今天的 ISO-8601 日期")
        assertEquals(emptyMap(), args.params)
    }
}
