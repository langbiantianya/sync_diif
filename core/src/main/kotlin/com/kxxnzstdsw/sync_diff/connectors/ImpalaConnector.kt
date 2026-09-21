package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

/**
 * Impala 连接配置：放在连接器内部，因为只有 [ImpalaConnector] 消费它的字段。
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
 *
 * 环境变量注入：[fromEnv] 是唯一的入口——env 名 (`IMPALA_JDBC_URL` / `IMPALA_USER` /
 * `IMPALA_PASSWORD`) 跟字段绑在一起，新增 / 改名都在这里改，不外漏到通用 [com.kxxnzstdsw.sync_diff.config]。
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
         */
        const val DEFAULT_JDBC_URL: String = "jdbc:impala://localhost:21050"

        /** 默认值：URL 用 [DEFAULT_JDBC_URL]，无凭据。多数 Check 的 [fromEnv] 起点。 */
        val DEFAULT: ImpalaConfig = ImpalaConfig(DEFAULT_JDBC_URL)

        /**
         * 从 env 重建一份 [ImpalaConfig]，未设的字段沿用 [current]。
         *
         * - `IMPALA_JDBC_URL`：url 的 env 兜底；未设沿用 [current.jdbcUrl]。
         * - `IMPALA_USER` / `IMPALA_PASSWORD`：env 没设沿用 [current]，覆盖即生效。
         *
         * 优先级（高 → 低）：env > [current]。
         *
         * 注意：本函数不再接 `cliUrlOverride` 之类的前置参数——CLI 已经不兜底连接信息了，
         * 上游 / 下游都由各 Check 自己负责。
         */
        fun fromEnv(current: ImpalaConfig): ImpalaConfig {
            val url = System.getenv("IMPALA_JDBC_URL") ?: current.jdbcUrl
            val user = System.getenv("IMPALA_USER") ?: current.user
            val password = System.getenv("IMPALA_PASSWORD") ?: current.password
            return ImpalaConfig(url, user, password)
        }
    }
}

/**
 * 下游 Connector 实现之一：Impala（Hive JDBC 直连）。Check 自己 new 它即可，没有
 * 框架层面的"默认下游"。
 *
 * 流式读的两个必要条件（README §6 风险对照），缺一个 HiveServer2 就把结果一次全量拉回：
 * 1. `autoCommit = false` —— Hive JDBC 只在非自动提交模式下才启用服务端游标；
 * 2. `Statement.setFetchSize(fetchSize)` —— 每次 fetch 向服务端要的行数，默认 10000。
 * 构造时设置 `autoCommit`；`fetchSize` 由 [Connector.query] / [Connector.stream] 对每个
 * 语句单独设置，所以 `query()` 也是按批拉取，只是最后会在 JVM 堆里聚成 `List<Row>`。
 *
 * 凭据三种形态，[ImpalaConfig] 的 `jdbcUrl` / `user` / `password` 与之配套：
 * - 无认证：url 里 `AuthMech=0`，`user` / `password` 留空；
 * - LDAP：url 里 `AuthMech=3`，凭据通过 `user` / `password` 以 JDBC `Properties` 下发；
 * - Kerberos：url 里配 `AuthMech=1` / `KrbRealm` / `KrbHostFQDN` / `KrbServiceName`，
 *   票据由 JVM 从 Kerberos ticket cache 取，`user` / `password` 留空。
 *
 * 内部 `open()` 在 `user` 为空时不带 `Properties`，直接按 url 建连；只要给了 `user`
 * 就走 `Properties`（`password` 为空则只下发 `user`）。
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
 * val cfg = ImpalaConfig("jdbc:impala://impala-host:21050/default;AuthMech=0")
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
    private val fetchSize: Int = DEFAULT_FETCH_SIZE,
) : Connector {

    /** 构造自 [ImpalaConfig]：常见于 `ImpalaConnector(myImpalaConfig)` 这类 Check 内联用。 */
    constructor(cfg: ImpalaConfig, fetchSize: Int = DEFAULT_FETCH_SIZE) : this(
        jdbcUrl = cfg.jdbcUrl,
        user = cfg.user,
        password = cfg.password,
        fetchSize = fetchSize,
    )

    private val conn: Connection = open(jdbcUrl, user, password).apply {
        autoCommit = false
    }

    override fun query(sql: String): List<Row> = guard(sql) {
        conn.prepareStatement(sql).use { st ->
            st.fetchSize = fetchSize
            st.executeQuery().use { rs -> rs.toRows() }
        }
    }

    // guard 必须在 sequence 内部调用：写在 `sequence { }` 外面只能包住序列对象的创建，
    // 真正的 prepare/execute 发生在迭代时，异常会绕过 guard 直接冒泡成裸 SQLException。
    // guard 是 inline，因此这里的 yield 仍处于 sequence 构建器的受限挂起作用域内。
    override fun stream(sql: String): Sequence<Row> = sequence {
        guard(sql) {
            conn.prepareStatement(sql).use { st ->
                st.fetchSize = fetchSize
                st.executeQuery().use { rs ->
                    while (rs.next()) yield(rs.toRow())
                }
            }
        }
    }

    override fun one(sql: String): Row? = query("$sql LIMIT 1").firstOrNull()

    override fun close() {
        conn.close()
    }

    private fun open(jdbcUrl: String, user: String?, password: String?): Connection {
        if (user.isNullOrEmpty()) {
            return DriverManager.getConnection(jdbcUrl)
        }
        val nonNullPassword = password
        val props = Properties().apply {
            setProperty("user", user)
            if (!nonNullPassword.isNullOrEmpty()) setProperty("password", nonNullPassword)
        }
        return DriverManager.getConnection(jdbcUrl, props)
    }

    private companion object {
        /** README §6 推荐值；Impala 的合理批量。 */
        const val DEFAULT_FETCH_SIZE: Int = 10_000
    }
}