package com.kxxnzstdsw.sync_diff.check

import com.kxxnzstdsw.sync_diff.config.AppConfig
import com.kxxnzstdsw.sync_diff.config.GlobalConfig
import com.kxxnzstdsw.sync_diff.config.Sources
import com.kxxnzstdsw.sync_diff.config.Targets
import com.kxxnzstdsw.sync_diff.engine.DiffEngine
import com.kxxnzstdsw.sync_diff.reporter.Reporter
import kotlinx.datetime.LocalDate.Companion.Format
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate

/**
 * 单条对账契约：一个唯一的 [name]，加一个 [run] 实现，别的一律走默认值。
 *
 * 写一个新 Check 的完整模板（照抄即可编译，把 SQL / 路径换成自己的）：
 * ```kotlin
 * object OrderSyncCheck : CheckBase("order_sync") {
 *     override suspend fun Ctx.run() {
 *         val dt = args.dt                                             // ① 本次运行参数
 *         val src = source parquet "/data/orders/dt=$dt/part-0.parquet" // ② 上游工厂
 *         val tgt = target impala "ods.orders"                         // ③ 下游工厂
 *
 *         val sql = "SELECT COUNT(*) AS c FROM read_parquet('/data/orders/dt=$dt/part-0.parquet')"
 *         diff.aggregate(src query sql, tgt query sql, keys = listOf("dt"))  // ④ 本次执行
 *         report.markdown("reports/order_sync.md", diff.summary())           // ⑤ 本次执行
 *     }
 * }
 * ```
 *
 * 注意：KDoc 里不要写 `glob` 通配路径原文。Kotlin 的块注释会嵌套，路径里的斜杠加星号
 * 会被当成新注释的起点，导致整个注释块不闭合、后续所有声明连带编译失败；需要举例时
 * 写具体的 `part-N.parquet` 或改述为「目录下所有 part 文件」。同上，路径里出现星号紧跟
 * 斜杠也会提前闭合注释。
 * 五处引用各自的来源：
 * - ① `args`：[Ctx.args]，由 [runWith] 注入；CLI 从 `--dt` 构造 [Args]。
 * - ② `source` / ③ `target`：[source] / [target] 两个工厂属性，读 [checkConfig]
 *   （默认 [GlobalConfig.current]；测试里 `GlobalConfig.configure(...)` 换掉即可改指向）。
 * - ④ `diff` / ⑤ `report`：[Ctx.diff] / [Ctx.report]，**每次执行新建**，见下。
 *
 * `tgt` 侧把 SQL 换成 Impala 方言即可，其余写法不变（两侧 SQL 各自指向自己的数据源）。
 * 模板里的 `c` 是计数，精确比即可；一旦 L1 带上 `SUM(...)` 这类浮点聚合列，
 * 就该给 [DiffEngine.aggregate] 传 `rules` 挂容忍度，否则行级容忍的漂移会在聚合层被误报。
 *
 * [Ctx] 把 `args` / `diff` / `report` 打包成一个 `this`，配合 receiver lambda 写起来像 DSL；
 * 每次 [runWith] 都新建一个 [Ctx]，所以同一个 Check（`object` 单例）被并发执行时
 * 各自拿到独立的 [DiffEngine] 计数器与 [Reporter]，不会互相污染。
 * [Ctx] 放在顶层（不是 `Check.Ctx`）是为了让子类的 `override fun Ctx.run()` 不需要额外 import。
 */
sealed interface Check {

    /** Check 名称；在 [CheckRegistry] 里唯一。 */
    val name: String

    /** 默认空参数；具体 Check 可以 override 出带配置字段的 `args`。 */
    val args: Args get() = Args()

    /** 默认走 [GlobalConfig.current]；测试里用 `GlobalConfig.configure(...)` 整体替换。 */
    val checkConfig: AppConfig get() = GlobalConfig.current

    /** 工厂入口：上游侧 [Sources]。 */
    val source: Sources get() = Sources(checkConfig)

    /** 工厂入口：下游侧 [Targets]。 */
    val target: Targets get() = Targets(checkConfig)

    /** Check 主体。实现方在 [Ctx] 的 receiver 作用域里写业务逻辑。 */
    suspend fun Ctx.run()

    /**
     * 为本次执行新建一个 [Ctx] 再跑 [run]——给顶层 runner（CLI / main）用。
     *
     * 每次调用都拿到独立的 [Ctx]（含独立 [DiffEngine] / [Reporter]），
     * 因此同一个 Check 并发跑多次不会互相踩计数器。见 [Ctx] 的说明。
     */
    suspend fun runWith(args: Args = this.args) {
        Ctx(args).run()
    }

    /**
     * Check 运行参数。
     *
     * - [dt]    ：上游分区日；用 `dt='2026-09-01'` 这种字符串方便和 SQL 直接拼。
     * - [params]：业务自定义参数（库名、表名、白名单……）。
     *
     * 两个字段都有默认值，所以 `Args()` 永远合法；只想改一个字段时用 `copy`：
     * ```kotlin
     * val base  = Args(dt = "2026-09-20")                        // 只指定分区日
     * val next  = base.copy(dt = "2026-09-21")                   // 换分区日，params 原样保留
     * val sized = base.copy(params = mapOf("take" to "1000"))    // 只补业务参数
     * ```
     *
     * [params] 是自由字典：实现方按约定取 `args.params["take"]`，取不到就当作未配置
     * （`null`），不要给它编造默认值的语义。
     */
    data class Args(
        /** 上游分区日，形如 `2026-09-20`；默认 `1970-01-01` 兼容未传 dt 的场景。 */
        val dt: String = LocalDate.now().toKotlinLocalDate().format(Format {
            year()
            char('-')
            monthNumber()
            char('-')
            day()
        }),
        /** 业务自定义参数，默认空表；键 / 值都是调用方自定义的字符串。 */
        val params: Map<String, String> = emptyMap(),
    )
}

/**
 * Check 执行时的上下文：`this` 拿到 args / diff / report 三个常用对象。
 *
 * 设计要点：
 * - 顶层类（不是 `Check.Ctx`），让 `override fun Ctx.run()` 在子类里直接用 `Ctx` 不用 import。
 * - **每次执行一个实例**：由 [Check.runWith] 新建。[DiffEngine] 带着可变计数器、
 *   [Reporter] 不是线程安全的，共享单例会让并发跑同一 Check 时互相污染，
 *   所以这里不做 `object`。同一次执行内两侧 `Connector` 仍由调用方 `use { }` 管理生命周期。
 * - `args` 走 [Args]（含默认值），少传字段用 `args.copy(dt = ...)`。
 */
class Ctx(
    /** 本次执行的运行参数。 */
    val args: Check.Args,
    /** diff 引擎；留给 §3.2 的 L1 / L2 / L3 使用。 */
    val diff: DiffEngine = DiffEngine(),
    /** Markdown + Webhook 输出。 */
    val report: Reporter = Reporter(),
)

/**
 * 简化版 [Check]：具体 Check 只需要 `object Foo : CheckBase("foo")` 就能开写。
 *
 * 它把 [Check] 收窄成「构造函数传名字 + 只实现 [Check.run]」的单行声明：名字走 `super`
 * 参数，[Check.args] / [Check.checkConfig] / [Check.source] / [Check.target] 全部沿用
 * 接口默认实现，子类不必重复写。
 *
 * 把 [Check.run] 拍平成 receiver-lambda 形式（`override fun Ctx.run()` 在 CheckBase
 * 这一层就是合法签名），子类的 `object` 直接继承即可。
 */
abstract class CheckBase(name: String) : Check {
    override val name: String = name
}

/**
 * 需要接收 CLI 告警 webhook 的 Check 实现它。
 *
 * 为什么用接口而不是反射：CLI 只拿到一个 [Check]，不知道具体类型。有了这个接口，
 * 注入就是一句 `(check as? Alertable)?.alertUrl = ...`——不用嗅探字段名，新增 Check
 * 也不用回头改 CLI。不关心告警的 Check 不实现即可。
 *
 * ```kotlin
 * object OrderSyncCheck : CheckBase("order_sync"), Alertable {
 *     // env 兜底，CLI --alert-url 覆盖；@Volatile 因为 CLI 线程写、执行线程读。
 *     @Volatile override var alertUrl: String = System.getenv("ALERT_URL") ?: ""
 * }
 * ```
 *
 * 拿到值后发不发由 Check 自己决定，惯例是空串即跳过：
 * `if (alertUrl.isNotEmpty()) report.webhook(alertUrl, summary)`。
 */
interface Alertable {
    /** 告警 webhook；空字符串表示不发。 */
    var alertUrl: String
}