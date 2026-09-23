package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import java.sql.Connection
private const val HIVE_DRIVER: String = "org.apache.hive.jdbc.HiveDriver"


/**
 * Impala 连接配置：放在连接器内部，因为只有 [ImpalaConnector] 消费它的字段。
 *
 * JDBC URL 形态：`jdbc:hive2://host:21050/default`——驱动已切到 `org.apache.hive:hive-jdbc`，
 * scheme 必须用 `jdbc:hive2://`（旧的 `jdbc:impala://` 找不到匹配驱动）。库名可省，驱动参数用 `;` 接在后面。
 * ```kotlin
 * ImpalaConfig("jdbc:hive2://impala-prod:21050/ods")                          // 无认证
 * ImpalaConfig("jdbc:hive2://impala-prod:21050/ods", "etl", "secret")         // 用户名 + 密码（LDAP）
 * ImpalaConfig("jdbc:hive2://impala-prod:21050/ods;principal=hive/_HOST@REALM")  // Kerberos
 * ```
 *
 * 三种认证模式（hive-jdbc 语义）：
 * - **无认证**（测试 / 内网）：url 不带 `auth=` / `principal=`，[user] / [password] 都留 `null`。
 * - **LDAP / 用户名密码**（url 带 `auth=LDAP`）：凭据可写进 url，也可交给 [user] / [password]。
 *   填了 [user] 时 [ImpalaConnector] 会用 JDBC `Properties` 把 user（以及非空 password）
 *   传给驱动；[user] 为空则完全不带凭据，直接 `DriverManager.getConnection(url)`。
 * - **Kerberos**（url 带 `principal=hive/_HOST@REALM`）：认证信息在 url 与 ticket cache 里，
 *   [user] / [password] 通常留空。
 *
 * [user] / [password] 分开可空，是为了只在需要 `Properties` 的 LDAP 场景下才填。
 *
 * 环境变量注入：[fromEnv] 是唯一的入口——env 名 (`IMPALA_JDBC_URL` / `IMPALA_USER` /
 * `IMPALA_PASSWORD`) 跟字段绑在一起，新增 / 改名都在这里改。
 */
data class ImpalaConfig(
    val jdbcUrl: String,
    val user: String? = null,
    val password: String? = null,
) {
    companion object {
        /**
         * 兜底默认 JDBC URL：开发者 `git clone && ./gradlew run` 直接跑起来的最小配置。
         * 改地址请走 env (`IMPALA_JDBC_URL`)，不要改这个常量。
         *
         * 驱动已切到 `org.apache.hive:hive-jdbc`，URL scheme 必须从 `jdbc:impala://`
         * 改为 `jdbc:hive2://`，否则 `DriverManager` 找不到匹配驱动直接抛。
         */
        const val DEFAULT_JDBC_URL: String = "jdbc:hive2://localhost:21050"

        /** 默认值：URL 用 [DEFAULT_JDBC_URL]，无凭据。多数 Check 的 [fromEnv] 起点。 */
        val DEFAULT: ImpalaConfig = ImpalaConfig(DEFAULT_JDBC_URL)

        /**
         * 从 env 重建一份 [ImpalaConfig]，未设的字段沿用 [current]。
         *
         * - `IMPALA_JDBC_URL`：url 的 env 兜底；未设沿用 [current.jdbcUrl]。
         * - `IMPALA_USER` / `IMPALA_PASSWORD`：env 没设沿用 [current]，覆盖即生效。
         *
         * 优先级（高 → 低）：env > [current]。测试想隔离环境时用 [envProvider] 注入假 env，
         * 不必真去改进程环境变量。
         */
        fun fromEnv(
            current: ImpalaConfig,
            envProvider: (String) -> String? = System::getenv,
        ): ImpalaConfig {
            val url = envProvider("IMPALA_JDBC_URL") ?: current.jdbcUrl
            val user = envProvider("IMPALA_USER") ?: current.user
            val password = envProvider("IMPALA_PASSWORD") ?: current.password
            return ImpalaConfig(url, user, password)
        }
    }
}

/**
 * 下游 Connector 实现之一：Impala（Hive JDBC 直连）。Check 自己 new 它即可，没有
 * 框架层面的"默认下游"。
 *
 * 建连 / 取数 / 失败包装的公共实现见 [JdbcConnector]（[stream] 服务端游标逐行 `yield`、
 * 失败抛 `ConnectorError.QueryFailed`），本类只负责 hive-jdbc 的握手细节。
 *
 * 流式读的两个必要条件（README §6 风险对照），缺一个 HiveServer2 就把结果一次全量拉回：
 * 1. `autoCommit = false` —— Hive JDBC 只在非自动提交模式下才启用服务端游标；
 * 2. `Statement.setFetchSize(fetchSize)` —— 每次 fetch 向服务端要的行数，默认 10000。
 * 两者都在 [JdbcConnector] 里落实（`autoCommit` 由本类建连时设置，`fetchSize` 由基类对每个
 * 语句单独设置），所以 `query()` 也是按批拉取，只是最后会在 JVM 堆里聚成 `List<Row>`。
 *
 * 凭据三种形态，[ImpalaConfig] 的 `jdbcUrl` / `user` / `password` 与之配套（hive-jdbc 语义）：
 * - 无认证：url 不带 `auth=` / `principal=`，`user` / `password` 留空；
 * - LDAP：url 里 `auth=LDAP`，凭据通过 `user` / `password` 以 JDBC `Properties` 下发；
 * - Kerberos：url 里配 `principal=hive/_HOST@REALM`（Hive 3 也接受 `;transportMode=http` 等可选），
 *   票据由 JVM 从 Kerberos ticket cache 取，`user` / `password` 留空。
 *
 * 凭据仍由 [jdbcConnection] 下发：`user` / `password` 都为空时不带 `Properties`、直接按 url
 * 建连；只要给了 `user` 就走 `Properties`（`password` 为空则只下发 `user`）。驱动类
 * （`org.apache.hive.jdbc.HiveDriver`）在建连前显式 `Class.forName`，不依赖 fat jar 里被合并的
 * service 文件——注意该驱动是 Java 21 字节码，**JVM 需要 21 及以上**，在 JDK 17 上会直接
 * `UnsupportedClassVersionError`。
 *
 * 生命周期：建连不便宜（Kerberos 还要判票据），而且构造即建连——连不上当场抛
 * `SQLException`（fail fast，测试锁定了这个契约）。一个 Check 内复用同一个实例，
 * 不要放进循环里 new。
 *
 * 构造有两种入口：
 * - 收一份 [ImpalaConfig]（推荐，Check 自己从 env 拿 + `@Volatile var` 持有）；
 * - 直接传 url + 可选凭据 + fetchSize（临时调试 / 测试用）。
 *
 * ```kotlin
 * val cfg = ImpalaConfig("jdbc:hive2://impala-host:21050/default")
 *
 * ImpalaConnector(cfg).use { tgt ->
 *     tgt.stream("SELECT order_id, amount FROM ods.orders WHERE dt = '2026-09-01'")
 *         .take(1000)
 *         .forEach { row -> println("${row["order_id"]} -> ${row.decimal("amount")}") }
 * }
 * ```
 *
 * 注意：[Connector.one] 会在你的 SQL 后面直接追加 ` LIMIT 1`，别自己再写 `LIMIT`；
 * 从 impala-shell / Hue 里贴过来的 SQL 记得去掉结尾分号，否则拼出来是语法错误。
 */
class ImpalaConnector(
    jdbcUrl: String,
    user: String? = null,
    password: String? = null,
    fetchSize: Int = DEFAULT_FETCH_SIZE,
) : JdbcConnector(
    jdbcConnection(HIVE_DRIVER, jdbcUrl, user, password).also { it.autoCommit = false },
    fetchSize,
) {

    /** 构造自 [ImpalaConfig]：常见于 `ImpalaConnector(myImpalaConfig)` 这类 Check 内联用。 */
    constructor(cfg: ImpalaConfig, fetchSize: Int = DEFAULT_FETCH_SIZE) : this(
        jdbcUrl = cfg.jdbcUrl,
        user = cfg.user,
        password = cfg.password,
        fetchSize = fetchSize,
    )
}
