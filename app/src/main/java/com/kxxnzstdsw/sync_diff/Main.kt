package com.kxxnzstdsw.sync_diff

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.kxxnzstdsw.sync_diff.check.Alertable
import com.kxxnzstdsw.sync_diff.check.Check
import com.kxxnzstdsw.sync_diff.check.CheckRegistry
import com.kxxnzstdsw.sync_diff.config.GlobalConfig
import kotlinx.coroutines.runBlocking

/**
 * sync_diff 命令行入口（README §13 / §3）。
 *
 * 一次进程跑一条 Check：CLI 只负责「参数 → [Check.Args] → [Check.runWith]」的装配，
 * 对账逻辑全在 Check 自己身上（写自己的 Check 见 [Check] 的 KDoc）。5 步启动流程见 [run]。
 *
 * ## 选项
 *
 * | 选项 | 默认值 | 环境变量 | 说明 |
 * |:---|:---|:---|:---|
 * | `--check` | 无，必填 | 无 | 要跑的 Check 名，与 [Check.name] 对齐 |
 * | `--dt` | `1970-01-01` | 无 | 上游分区日，原样透传给 [Check.Args.dt] |
 * | `--impala-url` | `jdbc:impala://localhost:21050` | `IMPALA_URL` | 下游 Impala JDBC URL |
 * | `--alert-url` | 空字符串（不发告警） | `ALERT_URL` | 告警 webhook 地址 |
 * | `--registry` | `checks.txt` | 无 | Check 注册清单文件路径 |
 *
 * 取值优先级：**命令行 > 同名环境变量 > 默认值**。[impalaUrl] / [alertUrl] 是仅有的两个带
 * envvar 绑定的选项；其余选项只认命令行。`--help` 由 Clikt 自动附带，打印用法后退出；
 * 选项缺失或非法同样由 Clikt 报告并以非零码退出。
 *
 * ## 用法
 *
 * 先 `./gradlew build shadowJar` 产出 `build/libs/sync_diff-all.jar`（`Main-Class` 见
 * `build.gradle.kts` 的 `shadowJar` 配置），然后：
 *
 * ```bash
 * # 1) 跑内置 Check：registry 文件不存在时自动 fallback 到内置 Check
 * java -jar build/libs/sync_diff-all.jar --check order_sync --dt 2026-09-20
 *
 * # 2) 指定注册清单：每行一个 Check 子类的 FQCN，空行与 `#` 开头的注释行忽略
 * cat > checks.txt <<'EOF'
 * com.kxxnzstdsw.sync_diff.checks.OrderSyncCheck
 * EOF
 * java -jar build/libs/sync_diff-all.jar --check order_sync --dt 2026-09-20 --registry checks.txt
 *
 * # 3) 带告警地址：只有实现了 Alertable 的 Check 才会用到它
 * java -jar build/libs/sync_diff-all.jar --check order_sync --dt 2026-09-20 \
 *     --alert-url https://hooks.example.com/sync-diff
 *
 * # 4) 用环境变量给默认值；命令行仍可逐项覆盖
 * IMPALA_URL='jdbc:impala://impala-prod:21050/default' \
 * ALERT_URL='https://hooks.example.com/sync-diff' \
 *     java -jar build/libs/sync_diff-all.jar \
 *         --check order_sync --impala-url jdbc:impala://canary:21050
 * ```
 *
 * ## 失败与 fallback
 *
 * - `--check` 在注册表里查不到：[run] 抛 [CliktError]，错误信息里列出当前可用的 Check 名，
 *   Clikt 渲染成一行 `Error: ...` 后以非零码退出；名字区分大小写。
 * - `--registry` 指向的文件不存在 / 读不到 / 没有任何有效行：[CheckRegistry.discover]
 *   fallback 到 [DiscoverBuiltin] 的内置 Check（`checks` 模块里带 `@BuiltinCheck` 的
 *   `object`，启动期注解扫描得到）——这是正常路径，不是错误。
 * - 清单里某行的 FQCN 反射不出 `object` / 不实现 [Check]：[CheckRegistry.discover] 抛
 *   [IllegalArgumentException]，[run] 把它转成 [CliktError] 并以非零码退出。
 *
 * 上述失败统一走 [CliktError]，因此调度日志里是一行可读的错误信息，不是 JVM 堆栈；
 * 退出码始终非零，配置错误不会被伪装成"对账通过"。
 */
class SyncDiffCli : CliktCommand(name = "sync_diff") {

    /**
     * 要跑的 Check 名；与 [Check.name] 对齐。
     *
     * 必填、无 env 绑定。去处：[run] 第 3 步的 `registry[check]`；取不到就失败。
     */
    val check by option("--check", help = "Check name to run").required()

    /**
     * 上游分区日；默认 `1970-01-01` 兼容未设 dt 的场景。
     *
     * 无 env 绑定。去处：装进 `Check.Args(dt = dt)` 交给 Check，通常被拼进 SQL 或
     * Parquet 路径（如某分区目录 `dt=2026-09-20` 下的 part 文件）。
     */
    val dt by option("--dt", help = "Date partition").default("1970-01-01")

    /**
     * Impala JDBC URL；可被 `IMPALA_URL` env 覆盖（合同保留 envvar 名字以便与现有调度脚本兼容）。
     *
     * 去处：[run] 第 1 步 `GlobalConfig.loadFromEnv(impalaUrl = ...)`，它比
     * `IMPALA_JDBC_URL` 优先级更高。
     *
     * 注意：Clikt 的 `.default(...)` 保证本属性永远非空，所以 [GlobalConfig.loadFromEnv]
     * 里的 `IMPALA_JDBC_URL` 兜底在 CLI 路径上不会生效；换地址请用本选项或 `IMPALA_URL`。
     */
    val impalaUrl by option("--impala-url", envvar = "IMPALA_URL")
        .default("jdbc:impala://localhost:21050")

    /**
     * 告警 webhook；空字符串表示不发。可被 `ALERT_URL` env 覆盖。
     *
     * 去处：[run] 第 4 步 `(selected as? Alertable)?.alertUrl = alertUrl`。没实现
     * [Alertable] 的 Check 收不到它，也不会因此报错。
     */
    val alertUrl by option("--alert-url", envvar = "ALERT_URL").default("")

    /**
     * Check 注册清单文件路径；不存在 fallback 到 [DiscoverBuiltin]。
     *
     * 无 env 绑定。去处：[run] 第 2 步 `CheckRegistry.discover(registryPath)`。
     */
    val registryPath by option("--registry", help = "Path to a file listing Check FQCNs")
        .default("checks.txt")

    /**
     * 启动流程，顺序即语义：
     *
     * 1. `GlobalConfig.loadFromEnv(impalaUrl = impalaUrl)` —— 用环境变量重建全局配置，
     *    命令行 [impalaUrl] 优先级最高（命令行 > env > 默认）。
     * 2. `CheckRegistry.discover(registryPath)` —— 按清单加载 Check；清单缺失 / 无有效行时
     *    fallback 到内置 Check。
     * 3. `registry[check]` —— 按名字取 Check；查不到就抛 [CliktError]，错误信息里带当前
     *    可用的 Check 名，随后进程以非零码退出。
     * 4. `(selected as? Alertable)?.alertUrl = alertUrl` —— 注入告警地址；只有实现了
     *    [Alertable] 的 Check 才吃到 [alertUrl]，其余静默忽略。
     * 5. `runBlocking { selected.runWith(args = Check.Args(dt = dt)) }` —— 跑 Check 主体。
     *    [Check.runWith] 是 `suspend`（Check 内部可以开协程并发抓两侧数据），而 Clikt 的
     *    `run()` 是同步签名，所以用 [runBlocking] 在调用线程上把它跑完再返回；CLI 一次进程
     *    只跑一条 Check，不需要常驻调度器或额外的 scope。
     *
     * 第 5 步的 [Ctx] 由 [Check.runWith] 现建，本方法不持有 [DiffEngine] / [Reporter]，
     * 因此同一条 Check 重复执行（测试、循环跑）不会共享计数器。
     */
    override fun run() {
        // 1) 加载 env，再用 --impala-url 覆盖（命令行 > env > 默认）。
        GlobalConfig.loadFromEnv(impalaUrl = impalaUrl)

        // 2) 加载 Check 注册清单；文件缺失 / 无有效行时 fallback 到内置 Check。
        //    清单里某行写错（类不存在 / 不是 object / 不实现 Check）时 discover 抛
        //    IllegalArgumentException——转成 CliktError，让调度日志看到一行可读信息
        //    而不是 JVM 堆栈；退出码仍是非零，不会把配置错误伪装成成功。
        val registry: CheckRegistry = runCatching { CheckRegistry.discover(registryPath) }
            .getOrElse { e -> throw CliktError(e.message ?: "Cannot load registry: $registryPath") }

        // 3) 解析当前 Check；未命中时把可用名字一起抛出来
        val selected: Check = registry[check] ?: throw CliktError(
            "Unknown check: $check. Available: ${registry.all().joinToString { it.name }}",
        )

        // 4) 把告警 webhook 注入给声明了 Alertable 的 Check；其它 Check 不关心告警
        (selected as? Alertable)?.alertUrl = alertUrl

        // 5) 跑 Check 主体：suspend 函数用 runBlocking 桥到同步 main
        runBlocking { selected.runWith(args = Check.Args(dt = dt)) }
    }
}

/**
 * Top-level entry：JVM 启动入口（`Main-Class: com.kxxnzstdsw.sync_diff.MainKt`）。
 *
 * 委托给 [CliktCommand.main]：它负责解析 argv、打印用法 / 错误，并在出错时以非零码退出；
 * 本函数不做任何参数处理，`build.gradle.kts` 的 `shadowJar` 直接指向它。
 */
fun main(args: Array<String>) = SyncDiffCli().main(args)
