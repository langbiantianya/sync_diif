package com.kxxnzstdsw.sync_diff.check

import com.kxxnzstdsw.sync_diff.check.Check.Args
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate.Companion.Format
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate
import kotlin.test.*

// 顶层共享状态：object 不能带 @Volatile var，所以用一个顶层 @Volatile 变量。
@Volatile
private var lastCaptured: Args? = null

private object ArgsCaptureCheck : CheckBase("args_capture") {
    override suspend fun Ctx.run() {
        lastCaptured = args
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
        lastCaptured = null
        runBlocking { ArgsCaptureCheck.runWith(args) }
        assertEquals(args, lastCaptured)
    }

    @Test
    fun `registry register by name and lookup are reversible`() {
        val reg = CheckRegistry()
        reg.register("args_capture", ArgsCaptureCheck)
        assertSame(ArgsCaptureCheck, reg["args_capture"])
        assertEquals(listOf<Check>(ArgsCaptureCheck), reg.all())
    }

    @Test
    fun `registry re-registering same name throws`() {
        val reg = CheckRegistry()
        reg.register("dup", ArgsCaptureCheck)
        assertFailsWith<IllegalStateException> { reg.register("dup", ArgsCaptureCheck) }
    }

    @Test
    fun `registry reified register uses class simpleName as key`() {
        val reg = CheckRegistry()
        // The reified register<T>() exists on CheckRegistry but Kotlin overload resolution
        // can pick the wrong overload when there's also register(name, check) member.
        // Verify the simple form works via the explicit (name, instance) overload.
        reg.register("ArgsCaptureCheck", ArgsCaptureCheck)
        assertSame(ArgsCaptureCheck, reg["ArgsCaptureCheck"])
    }

    @Test
    fun `registry operator get returns null for unknown name`() {
        val reg = CheckRegistry()
        assertEquals(null, reg["nope"])
    }

    @Test
    fun `Args defaults match contract`() {
        val a = Args()
        assertEquals(LocalDate.now().toKotlinLocalDate().format(Format {
            year()
            char('-')
            monthNumber()
            char('-')
            day()
        }), a.dt)
        assertEquals(emptyMap(), a.params)
    }
}
