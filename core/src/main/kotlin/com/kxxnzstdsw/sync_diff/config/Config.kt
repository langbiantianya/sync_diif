package com.kxxnzstdsw.sync_diff.config

import com.kxxnzstdsw.sync_diff.connectors.ImpalaConnector
import com.kxxnzstdsw.sync_diff.connectors.ParquetConnector
import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row

/**
 * Impala 下游配置（阶段 1 固定为 Impala）。
 *
 * JDBC URL 形态：`jdbc:impala://host:21050/default;AuthMech=0`——库名可省，驱动参数用 `;` 接在后面。
 * ```kotlin
 * ImpalaConfig("jdbc:impala://impala-prod:21050/ods")                                 // 无认证
 * ImpalaConfig("jdbc:impala://impala-prod:21050/ods", "etl", "secret")                // 用户名 + 密码
 * ImpalaConfig("jdbc:impala://impala-prod:21050/ods;AuthMech=1;KrbRealm=EXAMPLE.COM")  // Kerberos
 * ```
 *
 * 三种认证模式：
 * - **无认证**（`AuthMech=0`，测试 / 内网）：[user] / [password] 都留 `null`。
 * - **LDAP / 用户名密码**（`AuthMech=3`）：凭据可写进 url，也可交给 [user] / [password]。
 *   填了 [user] 时 [ImpalaConnector] 会用 JDBC `Properties` 把 user（以及非空 password）
 *   传给驱动；[user] 为空则完全不带凭据，直接 `DriverManager.getConnection(url)`。
 * - **Kerberos**（`AuthMech=1`，另加 `Principal=` / `KrbRealm=`）：认证信息在 url 与 ticket 里，
 *   [user] / [password] 通常留空。
 *
 * [user] / [password] 分开可空，是为了只在需要 `Properties` 的 LDAP 场景下才填。
 */
data class ImpalaConfig(
    val jdbcUrl: String,
    val user: String? = null,
    val password: String? = null,
)

/**
 * 顶层配置。
 *
 * 阶段 1 只有下游 Impala（[impala]）；阶段 2+ 按 README §6 往这里加 source 字段
 * （postgres / mysql / mssql / maxcompute …），届时 [GlobalConfig] 的 env 加载逻辑同步扩展。
 */
data class AppConfig(val impala: ImpalaConfig)

/**
 * 全局配置 holder。
 *
 * 阶段 1 默认值指向本地 Impala，方便开发者 `git clone && ./gradlew run` 直接跑起来；
 * 生产部署用 [loadFromEnv] 重新填充。
 *
 * 三个环境变量都由 [loadFromEnv] 读取，未设的字段沿用 [current] 里的值：
 *
 * | 环境变量          | 落到                     |
 * |:------------------|:-------------------------|
 * | `IMPALA_JDBC_URL` | `AppConfig.impala.jdbcUrl` |
 * | `IMPALA_USER`     | `AppConfig.impala.user`    |
 * | `IMPALA_PASSWORD` | `AppConfig.impala.password`|
 *
 * 优先级（高 → 低）：[loadFromEnv] 的显式参数 > 同名环境变量 > [current] 当前值。
 * 显式参数是给 CLI `--impala-url` 用的，所以「命令行 > env > 默认」这条链天然成立。
 *
 * ```kotlin
 * GlobalConfig.loadFromEnv()                               // 只吃环境变量
 * GlobalConfig.loadFromEnv("jdbc:impala://staging:21050")  // 覆盖 url，凭据仍走 env
 * ```
 *
 * 测试想彻底摆脱环境依赖，用 [configure] 直接换一份 [AppConfig]。
 */
object GlobalConfig {
    /**
     * 当前生效的配置；`Check.checkConfig` / [Sources] / [Targets] 读的都是它。
     *
     * setter 是 private：只能经 [configure] / [loadFromEnv] 整体替换，避免调用方各自散改字段。
     */
    @Volatile
    var current: AppConfig = AppConfig(ImpalaConfig("jdbc:impala://localhost:21050"))
        private set

    /**
     * 直接替换整份 [AppConfig]；测试与 CLI 启动期一次性调用。
     *
     * 这是运行期换配置的唯一入口（[current] 的 setter 不对外）；写完立刻对所有新构造的
     * [Sources] / [Targets] 生效，已在跑的 [Connector] 不受影响。
     */
    fun configure(cfg: AppConfig) {
        current = cfg
    }

    /**
     * 从环境变量重建 [AppConfig]，并写回 [current]（返回同一个新实例）。
     *
     * 三个变量：[impalaUrl] 参数 / `IMPALA_JDBC_URL` 决定 url，`IMPALA_USER` 决定 user，
     * `IMPALA_PASSWORD` 决定 password；优先级（高 → 低）为
     * 显式参数 > 环境变量 > [current] 当前值，env 没设的字段保持原样。
     *
     * [impalaUrl] 优先级最高，专供 CLI `--impala-url` 覆盖 env（命令行 > env > 默认）。
     */
    fun loadFromEnv(impalaUrl: String? = null): AppConfig {
        val url = impalaUrl ?: System.getenv("IMPALA_JDBC_URL") ?: current.impala.jdbcUrl
        val user = System.getenv("IMPALA_USER") ?: current.impala.user
        val password = System.getenv("IMPALA_PASSWORD") ?: current.impala.password
        return AppConfig(ImpalaConfig(url, user, password)).also { current = it }
    }
}

/**
 * 上游 [Connector] 工厂。
 *
 * 阶段 1 只有 [parquet] / [impala]，阶段 2+ 按 README §6 逐个补 postgres / mysql / mssql / maxcompute。
 *
 * ```kotlin
 * val src = Sources(GlobalConfig.current)
 * src.parquet("/data/orders/dt=2026-09-20/part-0.parquet").use { conn ->
 *     conn.query("SELECT COUNT(*) AS c FROM read_parquet('/data/orders/dt=2026-09-20/part-0.parquet')")
 * }
 * ```
 *
 * 在 `Check` 里可以用更省的 DSL：`source parquet "..."` 等价于 `source.parquet(...)`。
 * 工厂造出来的 [Connector] 是 `Closeable`，谁造谁负责 `use { }` 关闭。
 */
class Sources(private val cfg: AppConfig) {
    /**
     * DuckDB 读 Parquet；[memoryLimit] 默认 `4GB`（README §10 推荐值）。
     *
     * `path` 支持本地路径与对象存储（`s3://` / `oss://` 时构造器自动 `LOAD httpfs`），
     * 也可以带 glob（例如指向某分区目录下所有 part 文件）。
     *
     * 本参数实际只用于判断是否需要 `httpfs` 与配置 DuckDB；**真正读哪些文件由 SQL 里的
     * `read_parquet('...')` 决定**，两者不一致时以 SQL 为准。
     */
    fun parquet(path: String, memoryLimit: String = "4GB"): ParquetConnector =
        ParquetConnector(path, memoryLimit = memoryLimit)

    /**
     * 上游侧的 Impala 直连（少见，但有些流水线会把上游 Impala 表作为比对源）。
     *
     * 注意：[table] 目前**未被使用**。返回的 [ImpalaConnector] 只带连接信息（url / user /
     * password 取自 [cfg]），查询 SQL 必须由调用方写全限定表名——
     * `source.impala("ods.orders")` 之后仍要 `src query "SELECT ... FROM ods.orders WHERE dt = '...'"`。
     * 保留该参数只为对齐 README §8 的工厂签名（`fun impala(table: String): Connector`）。
     */
    fun impala(table: String): ImpalaConnector =
        ImpalaConnector(cfg.impala.jdbcUrl, cfg.impala.user, cfg.impala.password)
}

/**
 * 下游 [Connector] 工厂：阶段 1 固定只有 Impala。
 *
 * ```kotlin
 * val tgt = Targets(GlobalConfig.current)
 * tgt.impala("ods.orders").use { conn ->
 *     conn.stream("SELECT * FROM ods.orders WHERE dt = '2026-09-20'").take(1000).toList()
 * }
 * ```
 *
 * DSL 写法：`target impala "ods.orders"` 等价于 `target.impala("ods.orders")`。
 */
class Targets(private val cfg: AppConfig) {
    /**
     * 连下游 Impala；连接信息取自 [cfg]（见 [ImpalaConfig] 的三种认证模式）。
     *
     * 注意：[table] 与 [Sources.impala] 的同名参数一样**未被使用**，SQL 里的表名要写全限定。
     */
    fun impala(table: String): ImpalaConnector =
        ImpalaConnector(cfg.impala.jdbcUrl, cfg.impala.user, cfg.impala.password)
}

// --- 顶层中缀（README §4.9 末尾） ---

/** `source parquet "/path"` —— DSL 入口；等价于 `source.parquet("/path")`，返回 [Connector]。 */
infix fun Sources.parquet(path: String): Connector = parquet(path)

/**
 * `target impala "table"` —— DSL 入口；等价于 `target.impala("table")`，返回 [Connector]。
 *
 * 与 [Targets.impala] 一样，`table` 参数当前未被使用，SQL 需自带全限定表名。
 */
infix fun Targets.impala(table: String): Connector = impala(table)

/** `src query "SELECT ..."` —— DSL 入口；等价于 `src.query("SELECT ...")`，返回 `List<Row>`。 */
infix fun Connector.query(sql: String): List<Row> = query(sql)

/** `src stream "SELECT ..."` —— DSL 入口；等价于 `src.stream(...)`，返回惰性 `Sequence<Row>`。 */
infix fun Connector.stream(sql: String): Sequence<Row> = stream(sql)