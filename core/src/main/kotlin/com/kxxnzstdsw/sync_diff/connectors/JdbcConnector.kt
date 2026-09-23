package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * JDBC 系 Connector 的公共骨架：`query` / `stream` / `one` / `close` 四个方法的唯一实现，
 * 各源连接器只负责**建连**（方言 URL / 凭据 / 会话参数）与**方言差异**（[oneSql]）。
 *
 * 实现契约（见 [Connector] 的 KDoc）在这里一次落实到位，新增 JDBC 源不必再逐条复述：
 *
 * - **失败一律抛 [com.kxxnzstdsw.sync_diff.core.ConnectorError.QueryFailed]**：三个方法体内都
 *   用 [guard] 包住，包括 [stream] —— 后者的 `prepare/execute` 发生在**迭代时**，所以
 *   `guard` 写在 `sequence { }` **内部**；写在外面只能包住序列对象的创建，裸 `SQLException`
 *   会从消费点冒出来。
 * - **[stream] 是服务端游标逐行 `yield`**，不 `query(sql).asSequence()`（那会把整表拉进堆）。
 * - **[close] 幂等交给驱动**，调用方用 `use { }` 管生命周期。
 *
 * 子类的写法（以 PostgreSQL 为例）——只需在建连与 [oneSql] 两处落脚：
 *
 * ```kotlin
 * class PgConnector(
 *     jdbcUrl: String,
 *     user: String? = null,
 *     password: String? = null,
 *     fetchSize: Int = DEFAULT_FETCH_SIZE,
 * ) : JdbcConnector(
 *     DriverManager.getConnection(jdbcUrl, user, password).also { it.autoCommit = false },
 *     fetchSize,
 * )
 * ```
 *
 * 构造期建连：基类拿到的 [conn] 已经建好，连不上就在构造时抛（fail fast，测试锁定了这个
 * 契约）；[conn] 的会话参数（`autoCommit` / `fetchSize` 之外的 SET）由子类在传给基类之前设好。
 */
abstract class JdbcConnector(
    protected val conn: Connection,
    private val fetchSize: Int = DEFAULT_FETCH_SIZE,
) : Connector {

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

    override fun one(sql: String): Row? = query(oneSql(sql)).firstOrNull()

    /**
     * [one] 实际执行的语句：默认在 [sql] 尾部追加 `LIMIT 1`（PostgreSQL / MySQL / ClickHouse /
     * H2 / Impala / MaxCompute 都认）。
     *
     * **不支持 `LIMIT` 的方言必须覆写它**——目前只有 SQL Server：`LIMIT` 不是 T-SQL 语法，
     * 追加进去只会得到一条语法错误。[MSSQLConnector] 覆写成在 `SELECT` 后插 `TOP 1`。
     */
    protected open fun oneSql(sql: String): String = "$sql LIMIT 1"

    override fun close() {
        conn.close()
    }

    companion object {
        /** 默认 fetchSize：各源通用的一批行数（README §6 推荐值）。 */
        const val DEFAULT_FETCH_SIZE: Int = 10_000
    }
}

/**
 * 建连前**显式注册**驱动类，再交给 `DriverManager`。
 *
 * 为什么不靠 JDBC 4 的自动注册：fat jar 里 `META-INF/services/java.sql.Driver` 被
 * `mergeServiceFiles()` 合成**一份**，只要其中任一条驱动类在当前 JVM 上加载不了，`ServiceLoader`
 * 的扫描就在那条中断，排在它后面的驱动全部退化成「No suitable driver found」。
 * 实测：`hive-jdbc:4.2.1` 的 `HiveDriver` 是 Java 21 字节码（major 65），JDK 17 上抛
 * `UnsupportedClassVersionError`，而它在合并后的清单里排第 2 —— JDK 17 下
 * H2 / MySQL / MSSQL / PostgreSQL / ClickHouse / MaxCompute 全部注册不上（DuckDB 排第一才幸免）。
 *
 * [Class.forName] 让每个连接器只依赖自己的驱动：扫描顺序、其他驱动是否可加载都不再影响它；
 * 驱动本身加载不了时报错也直指那个驱动类，而不是一句含糊的 "No suitable driver found"。
 *
 * [user] / [password] / [extra] 留空则不带 `Properties` 建连（等价于原
 * `DriverManager.getConnection(url)`），保持各源既有行为不变。
 */
internal fun jdbcConnection(
    driverClass: String,
    url: String,
    user: String? = null,
    password: String? = null,
    extra: Properties? = null,
): Connection {
    Class.forName(driverClass)
    val props = Properties()
    if (!user.isNullOrEmpty()) props.setProperty("user", user)
    if (!password.isNullOrEmpty()) props.setProperty("password", password)
    extra?.forEach { (key, value) -> props.setProperty(key.toString(), value.toString()) }
    return if (props.isEmpty) DriverManager.getConnection(url) else DriverManager.getConnection(url, props)
}
