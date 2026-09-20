package com.kxxnzstdsw.sync_diff.check

import java.io.File
import java.util.jar.JarFile
import kotlin.reflect.KClass

/**
 * Check 注册表：名字 ↔ [Check] 实例。
 *
 * 设计要点：
 * - 名字 = [Check.name]；`object` 自身即可作 singleton。
 * - 反射版 [register] 用 `T::class.simpleName!!`，启动期跑一次，没有运行时开销。
 * - 顺序保留（[LinkedHashMap]），CLI 按声明顺序跑。
 * - 启动期用 [discover] 从注册清单文件加载用户自定义 Check；空 / 缺失文件 fallback 到
 *   [DiscoverBuiltin] 提供的内置 Check。
 */
class CheckRegistry {

    private val byName: MutableMap<String, Check> = LinkedHashMap()

    /**
     * 注册 [check]，用 [name] 作为 key。
     *
     * 重复注册同名会抛 [IllegalStateException]，而不是静默覆盖：清单文件里同一个 Check
     * 写两遍是典型手误，覆盖会让「以为跑了 A + B、实际只剩 B」的问题一路漏到生产；
     * 启动期直接失败，排查成本最低。
     */
    fun register(name: String, check: Check) {
        check(byName.put(name, check) == null) {
            "Check '$name' already registered"
        }
    }

    /** 操作符索引：`registry["foo"]` 直接拿；没注册过返回 `null`（不抛）。 */
    operator fun get(name: String): Check? = byName[name]

    /** 按注册顺序返回所有 Check；顺序 = 清单文件里的书写顺序，也是 CLI 的执行顺序。 */
    fun all(): List<Check> = byName.values.toList()

    /**
     * 反射注册：`CheckRegistry.register<OrderSyncCheck>()`。
     *
     * key 取 `T::class.simpleName`（= `OrderSyncCheck`），**不是** [Check.name]
     * （= `order_sync`）——清单文件与 CLI `--check` 用的是后者，两者不要混。
     * 实例来自 [instance]，所以只适用于 `object` 声明的 Check；
     * 多实例 / 带构造参数的 Check 请用 [register] 的两参版本显式注册。
     *
     * 注意它与两参的 [register] 同名，`register<Foo>()` 的重载决议在部分调用场景下
     * 容易被误选，拿不准时直接写 `register("foo", Foo)`。
     */
    inline fun <reified T : Check> register() {
        register(T::class.simpleName!!, instance())
    }

    /**
     * 取 `object` 声明的单例实例。
     *
     * `inline reified` 需要拿到对象实例，而 [Check] 继承自 `Any`，
     * 所以这里天然满足 `KClass.objectInstance` 的 `T : Any` 约束。
     * 普通 `class` / 抽象类没有 `objectInstance`，会走到 `error(...)`：
     * 这类 Check 需要多个实例或构造参数时，请自己 `register(name, MyCheck(...))`。
     */
    inline fun <reified T : Check> instance(): T = T::class.objectInstance
        ?: error("CheckRegistry.register<T>() only works on 'object' checks (got non-object class ${T::class})")

    companion object {
        /**
         * 从 [registryPath] 加载 Check：
         *
         * - 每行一条记录：要么是 [Check] 子类的 FQCN，要么是空行 / `#` 开头注释。
         * - 文件不存在 / 读不到 / 无有效行 → fallback 到 [DiscoverBuiltin.registerAll] 的内置 Check。
         * - 反射失败的行 → 抛 [IllegalArgumentException]，调用方（CLI）转成退出码。
         *
         * 清单文件长这样（`#` 后的内容被裁掉，所以整行注释与行尾注释都支持）：
         * ```text
         * # 用户自定义 Check 清单；文件缺省或全空 → 退回内置 Check
         * com.example.checks.UserCountCheck
         * com.example.checks.PaymentOrderCheck  # 行尾注释同样被裁掉
         * ```
         *
         * 三种失败行为的差异，决定了它是「静默兜底」还是「启动期快速失败」：
         * - 清单文件缺失 / 不可读 / 没有任何有效行 → **静默**用内置 Check，不报错。
         * - 某行类加载不到（类名敲错、类不在 classpath）→ [IllegalArgumentException]。
         * - 加载到了但不是 Kotlin `object`，或不实现 [Check] → [IllegalArgumentException]。
         *   后两类异常都带出问题的那行原文，宁可启动就卡住，也不静默少跑一个 Check。
         *
         * 同一个 FQCN 写两遍会走到第二次 [register] 并抛 [IllegalStateException]。
         *
         * 反射开销只发生在启动期，调用一次 [Check.run] 的代价远大于此。
         */
        fun discover(registryPath: String): CheckRegistry {
            val registry = CheckRegistry()
            val file = File(registryPath)
            val entries: List<String> = if (!file.exists() || !file.canRead()) {
                emptyList()
            } else {
                file.readLines()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
            }
            if (entries.isEmpty()) {
                DiscoverBuiltin.registerAll(registry)
                return registry
            }
            entries.forEach { line ->
                val klass: KClass<*> = runCatching { Class.forName(line).kotlin }
                    .getOrElse { e ->
                        throw IllegalArgumentException(
                            "Registry entry '$line' cannot be loaded: ${e.message}", e,
                        )
                    }
                val instance = klass.objectInstance
                    ?: throw IllegalArgumentException(
                        "Registry entry '$line' must be a Kotlin 'object' (got ${klass.qualifiedName ?: klass.java.name})",
                    )
                val check = instance as? Check
                    ?: throw IllegalArgumentException(
                        "Registry entry '$line' (${klass.qualifiedName}) does not implement ${Check::class.qualifiedName}",
                    )
                registry.register(check.name, check)
            }
            return registry
        }
    }
}

/**
 * 内置 Check 标记：打在 `object` 声明上，[DiscoverBuiltin] 启动期扫描注册。
 *
 * ```kotlin
 * @BuiltinCheck
 * object UserSyncCheck : CheckBase("user_sync")
 * ```
 *
 * 只对 Kotlin `object` 有意义：注册表存的是一份实例，普通 `class` 没有 `objectInstance`，
 * 扫描到就按启动期错误处理（不会静默跳过）。
 *
 * 运行期保留（[AnnotationRetention.RUNTIME]）：扫描发生在启动期、用反射读注解，没有注解
 * 处理器，也不会生成任何 `META-INF` 清单文件。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class BuiltinCheck(
    /**
     * 执行顺序：小者先跑，同值按类名字典序（稳定）。
     *
     * 默认 `0`，即「按类名字典序跑」；只有确实需要某一档先于其它档执行时才显式写它。
     */
    val order: Int = 0,
)

/**
 * 内置 Check 注册：启动期 fallback。
 *
 * [CheckRegistry.discover] 在用户清单为空时调用一次 [registerAll]，它扫描
 * [DiscoverBuiltin] 自身所在 code source 里的 [PACKAGE] 包——开发期是
 * `checks/build/classes/kotlin/main/` 目录，跑 fat jar 时是 `sync_diff-all.jar`——
 * 把带 [BuiltinCheck] 的 Kotlin `object` 全部注册进去。
 *
 * 扫描范围的边界是刻意的：
 * - 只认 [PACKAGE] 这一个包：全 classpath 扫描会把用户自己的 `@BuiltinCheck` 也一起注册，
 *   与「清单文件显式列出要跑的 Check」的语义冲突。
 * - 只认注册器所在的那一个 code source：内置 Check 与 [DiscoverBuiltin] 同在 `checks`
 *   模块，必然同源；放到别的 jar / 别的模块里就扫不到。
 *
 * 顺序：先按 [BuiltinCheck.order] 升序，同 order 按 FQCN 字典序；该顺序即
 * [CheckRegistry.all] 的顺序，也就是 CLI 的执行顺序。
 *
 * 所以新增内置 Check = 在 `checks/src/main/java/com/kxxnzstdsw/sync_diff/checks/` 下加一个
 * `@BuiltinCheck object FooCheck : CheckBase("foo")`，不用回来改这里——漏登记这件事从
 * 「记得改代码」变成「记得加注解」，而注解就在 Check 自己头上。
 */
object DiscoverBuiltin {

    /** 内置 Check 所在包；扫描限定在这个包（含子包）内。 */
    private const val PACKAGE = "com.kxxnzstdsw.sync_diff.checks"

    /** [PACKAGE] 的目录形式：目录扫描的相对前缀 / jar 条目前缀都用它。 */
    private const val PACKAGE_DIR = "com/kxxnzstdsw/sync_diff/checks/"

    /**
     * 注册全部内置 Check，顺序见类 KDoc。
     *
     * 扫描 + 反射实例化都发生在启动期，一次 [Check.run] 的代价远大于此。
     */
    fun registerAll(registry: CheckRegistry) {
        discovered().forEach { registry.register(it.name, it) }
    }

    /**
     * 扫描并实例化内置 Check，按 (order, FQCN) 排好序返回。
     *
     * 一个都没扫到时抛 [IllegalStateException]：空注册表会让 CLI 报「Unknown check」，
     * 而真实原因（打包漏了 `checks`、classpath 配错）就在这条信息里，不该让用户去猜名字。
     */
    private fun discovered(): List<Check> {
        val source = codeSource()
        val found: List<Pair<Int, Check>> = classNames(source).mapNotNull { checkAt(it) }
        check(found.isNotEmpty()) {
            "No @${BuiltinCheck::class.simpleName} check found under $PACKAGE_DIR in $source"
        }
        // sortedBy 稳定：同 order 保持 classNames() 的 FQCN 字典序。
        return found.sortedBy { it.first }.map { it.second }
    }

    /**
     * [DiscoverBuiltin] 自身所在的 classpath 条目（目录或 jar）。
     *
     * 取不到说明运行环境异常（无 `protectionDomain.codeSource`），没有兜底路径——
     * 猜一个目录去扫只会得到误导性的空结果。
     */
    private fun codeSource(): File {
        val location = DiscoverBuiltin::class.java.protectionDomain?.codeSource?.location
            ?: error(
                "Cannot locate the classpath entry of ${DiscoverBuiltin::class.qualifiedName}: " +
                    "protectionDomain.codeSource is unavailable",
            )
        return File(location.toURI())
    }

    /**
     * 列出 [source] 里 [PACKAGE_DIR] 下的全部类 FQCN，已按字典序排序。
     *
     * 类名里含 `$` 的（嵌套类 / 匿名类 / lambda）一律排除：它们不会带 [BuiltinCheck]，
     * 加载它们纯属浪费且可能抛出噪声异常。
     */
    private fun classNames(source: File): List<String> {
        val relative: List<String> = if (source.isDirectory) {
            directoryNames(source)
        } else {
            jarNames(source)
        }
        return relative
            .filter { '$' !in it }
            .map { "$PACKAGE.${it.replace('/', '.')}" }
            .sorted()
    }

    /** 目录形态（开发期 / `gradle run`）：直接走 [PACKAGE_DIR] 子目录。 */
    private fun directoryNames(source: File): List<String> {
        val packageDir = source.resolve(PACKAGE_DIR)
        if (!packageDir.isDirectory) return emptyList()
        return packageDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.relativeTo(packageDir).invariantSeparatorsPath.removeSuffix(".class") }
            .toList()
    }

    /** jar 形态（`sync_diff-all.jar`）：按条目前缀过滤，不整包枚举成类。 */
    private fun jarNames(source: File): List<String> = JarFile(source).use { jar ->
        jar.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith(PACKAGE_DIR) && it.endsWith(".class") }
            .map { it.removePrefix(PACKAGE_DIR).removeSuffix(".class") }
            .toList()
    }

    /**
     * 读 [fqcn] 上的 [BuiltinCheck] 并实例化；没有注解就返回 `null`（跳过）。
     *
     * `Class.forName(..., initialize = false)`：先用反射读注解、判断要不要这个类，
     * 只有确定要注册时才走 [KClass.objectInstance] 触发它的初始化（`object` 单例构造）。
     */
    private fun checkAt(fqcn: String): Pair<Int, Check>? {
        val klass: KClass<*> = Class.forName(fqcn, false, DiscoverBuiltin::class.java.classLoader).kotlin
        val annotation: BuiltinCheck = klass.java.getAnnotation(BuiltinCheck::class.java) ?: return null
        val instance = klass.objectInstance ?: error(
            "$fqcn is annotated with @${BuiltinCheck::class.simpleName} but is not a Kotlin 'object' " +
                "(got ${klass.java.name}); declare it as 'object' or register it explicitly",
        )
        val check = instance as? Check ?: error(
            "@${BuiltinCheck::class.simpleName} $fqcn (${klass.qualifiedName}) does not implement " +
                Check::class.qualifiedName,
        )
        return annotation.order to check
    }
}