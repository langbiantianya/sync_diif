package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

/**
 * 下游固定为 Impala（Hive JDBC 直连）。
 *
 * 流式读的两个必要条件（README §6 风险对照），缺一个 HiveServer2 就把结果一次全量拉回：
 * 1. `autoCommit = false` —— Hive JDBC 只在非自动提交模式下才启用服务端游标；
 * 2. `Statement.setFetchSize(fetchSize)` —— 每次 fetch 向服务端要的行数，默认 10000。
 * 构造时设置 `autoCommit`；`fetchSize` 由 [Connector.query] / [Connector.stream] 对每个
 * 语句单独设置，所以 `query()` 也是按批拉取，只是最后会在 JVM 堆里聚成 `List<Row>`。
 *
 * 凭据三种形态，`jdbcUrl` / `user` / `password` 与之配套：
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
 * ```kotlin
 * val url = "jdbc:impala://impala-host:21050/default;AuthMech=0"
 *
 * ImpalaConnector(url).use { tgt ->
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