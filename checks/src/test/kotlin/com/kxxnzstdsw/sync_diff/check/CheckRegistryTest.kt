package com.kxxnzstdsw.sync_diff.check

import com.kxxnzstdsw.sync_diff.checks.OrderSyncCheck
import com.kxxnzstdsw.sync_diff.checks.WilsonActivityApplyDetailCheck
import com.kxxnzstdsw.sync_diff.checks.WilsonActivityEventHeaderCheck
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * CheckRegistry.discover(path) + DiscoverBuiltin 的契约：
 *
 * - 文件缺失 / 空 → fallback 到 DiscoverBuiltin（= `@BuiltinCheck` 注解扫描出的内置 Check）
 * - 文件含 1+ 个 FQCN 行 → 反射加载对应 `object` Check
 * - 注释 (`#`) / 空白 → 跳过
 * - 反射失败（类不存在 / 不是 object / 不是 Check）→ IllegalArgumentException
 * - 注册同名 → 后注册覆盖？——参考 [CheckRegistry.register]：抛 IllegalStateException
 */
class CheckRegistryTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("check-registry-test-")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun writeRegistryFile(name: String, content: String): File {
        val f = tempDir.resolve(name).toFile()
        f.writeText(content)
        return f
    }

    @Test
    fun `discover returns DiscoverBuiltin when path is missing`() {
        val registry = CheckRegistry.discover(tempDir.resolve("nonexistent.txt").toString())
        val names = registry.all().map { it.name }
        assertTrue(
            names.contains(OrderSyncCheck.name),
            "expected fallback to DiscoverBuiltin (OrderSyncCheck), got $names"
        )
    }

    @Test
    fun `discover returns DiscoverBuiltin when file is empty`() {
        val file = writeRegistryFile("empty.txt", "")
        val registry = CheckRegistry.discover(file.absolutePath)
        assertNotNull(registry[OrderSyncCheck.name])
    }

    @Test
    fun `discover returns DiscoverBuiltin when file has only comments`() {
        val file = writeRegistryFile(
            "comments.txt", """
            # this is a comment
              # indented comment
            ${'\t'}
        """.trimIndent()
        )
        val registry = CheckRegistry.discover(file.absolutePath)
        assertNotNull(registry[OrderSyncCheck.name])
    }

    @Test
    fun `discover loads FQCN entries via reflection`() {
        val file = writeRegistryFile(
            "checks.txt", """
            # comment line
            com.kxxnzstdsw.sync_diff.checks.OrderSyncCheck

            # blank line then another entry (same FQCN — duplicate-name test below)
        """.trimIndent()
        )
        val registry = CheckRegistry.discover(file.absolutePath)
        assertSame(OrderSyncCheck, registry[OrderSyncCheck.name])
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `discover with duplicate name throws on second register`() {
        val file = writeRegistryFile(
            "dup.txt", """
            com.kxxnzstdsw.sync_diff.checks.OrderSyncCheck
            com.kxxnzstdsw.sync_diff.checks.OrderSyncCheck
        """.trimIndent()
        )
        assertFailsWith<IllegalStateException> {
            CheckRegistry.discover(file.absolutePath)
        }
    }

    @Test
    fun `discover rejects unknown class with IllegalArgumentException`() {
        val file = writeRegistryFile("bad.txt", "com.example.NotARealClass")
        val ex = assertFailsWith<IllegalArgumentException> {
            CheckRegistry.discover(file.absolutePath)
        }
        assertTrue(
            ex.message!!.contains("com.example.NotARealClass"),
            "expected error message to mention the offending class, got: ${ex.message}"
        )
    }

    @Test
    fun `discover rejects non-object class with IllegalArgumentException`() {
        // com.kxxnzstdsw.sync_diff.check.Check is a sealed interface, not an object
        val file = writeRegistryFile("non-obj.txt", "com.kxxnzstdsw.sync_diff.check.Check")
        val ex = assertFailsWith<IllegalArgumentException> {
            CheckRegistry.discover(file.absolutePath)
        }
        assertTrue(
            ex.message!!.contains("must be a Kotlin 'object'"),
            "expected 'must be a Kotlin object' error, got: ${ex.message}"
        )
    }

    @Test
    fun `DiscoverBuiltin registerAll scans annotated checks in order`() {
        val reg = CheckRegistry()
        DiscoverBuiltin.registerAll(reg)
        // 顺序 = (order, FQCN) 升序 = CLI 的执行顺序；漏一个或顺序变了这里就红。
        assertEquals(
            listOf(
                OrderSyncCheck.name,
                WilsonActivityApplyDetailCheck.name,
                WilsonActivityEventHeaderCheck.name,
            ),
            reg.all().map { it.name },
        )
        assertSame(OrderSyncCheck, reg[OrderSyncCheck.name])
        assertSame(WilsonActivityApplyDetailCheck, reg[WilsonActivityApplyDetailCheck.name])
        assertSame(WilsonActivityEventHeaderCheck, reg[WilsonActivityEventHeaderCheck.name])
    }
}
