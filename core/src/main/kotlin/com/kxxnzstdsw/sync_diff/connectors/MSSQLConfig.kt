package com.kxxnzstdsw.sync_diff.connectors

private const val MSSQL_DRIVER: String = "com.microsoft.sqlserver.jdbc.SQLServerDriver"


/**
 * MSSQL 连接配置。
 *
 * JDBC URL 形态：`jdbc:sqlserver://host:1433;databaseName=ods`。
 * ```kotlin
 * MSSQLConfig("jdbc:sqlserver://mssql-prod:1433;databaseName=ods")
 * MSSQLConfig("jdbc:sqlserver://mssql-prod:1433;databaseName=ods", "etl", "secret")
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口（`MSSQL_JDBC_URL` 必填，缺失即抛）。
 */
data class MSSQLConfig(
    val jdbcUrl: String,
    val user: String? = null,
    val password: String? = null,
) {
    companion object {
        fun fromEnv(): MSSQLConfig = MSSQLConfig(
            jdbcUrl = System.getenv("MSSQL_JDBC_URL")
                ?: error("MSSQL_JDBC_URL is not set"),
            user = System.getenv("MSSQL_USER"),
            password = System.getenv("MSSQL_PASSWORD"),
        )
    }
}

/**
 * MSSQL 连接器。
 *
 * 建连 / 取数 / 失败包装的公共实现见 [JdbcConnector]；[one] 例外——T-SQL 没有 `LIMIT`，
 * 本类覆写 [JdbcConnector.oneSql] 为 [mssqlTopOne]（在 `SELECT` 后插 `TOP 1`）。
 *
 * ```kotlin
 * MSSQLConnector(MSSQLConfig("jdbc:sqlserver://mssql-prod:1433;databaseName=ods")).use { conn ->
 *     conn.query("SELECT * FROM orders WHERE dt = '2026-09-01'")
 * }
 * ```
 */
class MSSQLConnector(
    jdbcUrl: String,
    user: String? = null,
    password: String? = null,
    fetchSize: Int = DEFAULT_FETCH_SIZE,
) : JdbcConnector(
    jdbcConnection(MSSQL_DRIVER, jdbcUrl, user, password).also { it.autoCommit = false },
    fetchSize,
) {
    /** 构造自 [MSSQLConfig]：Check 里最常用的入口。 */
    constructor(cfg: MSSQLConfig, fetchSize: Int = DEFAULT_FETCH_SIZE) : this(
        jdbcUrl = cfg.jdbcUrl,
        user = cfg.user,
        password = cfg.password,
        fetchSize = fetchSize,
    )

    override fun oneSql(sql: String): String = mssqlTopOne(sql)
}

/** 语句开头：`SELECT`，可选 `ALL` / `DISTINCT`（T-SQL 里 `TOP` 必须写在它们之后）。 */
private val SELECT_PREFIX: Regex = Regex("""(?is)^(\s*select\s+)(?:(all|distinct)\s+)?""")

/**
 * 把「尾部追加 LIMIT 1」的通用取一行写法，改写成 T-SQL 的 `SELECT TOP 1`。
 *
 * [JdbcConnector.oneSql] 的 SQL Server 分支：`SELECT a FROM t WHERE pk = 1` →
 * `SELECT TOP 1 a FROM t WHERE pk = 1`；`SELECT DISTINCT a ...` →
 * `SELECT DISTINCT TOP 1 a ...`（`TOP` 在 `DISTINCT` 之后才合法）。
 *
 * 两条边界是刻意的（不做猜测性改写，宁可早失败）：
 * - 语句里**已经写了 `TOP`**：原样返回，不再插第二个（同时也不再追加 `LIMIT 1`）；
 * - 不是以 `SELECT`（可带 `ALL` / `DISTINCT`）开头——CTE（`WITH …`）、`UNION` 之类无法在不解析
 *   语法的前提下安全插入，抛 [IllegalArgumentException]；这类查询请直接用
 *   [JdbcConnector.query] / [JdbcConnector.stream]。
 */
internal fun mssqlTopOne(sql: String): String {
    val match = SELECT_PREFIX.find(sql)
        ?: throw IllegalArgumentException(
            "MSSQLConnector.one() rewrites a leading SELECT into 'SELECT TOP 1 ...', got: " +
                sql.trim().take(80) + " — use query() / stream() for other statements",
        )
    val keywordEnd = match.range.last + 1
    val current = sql.substring(keywordEnd).trimStart()
    if (current.startsWith("TOP", ignoreCase = true)) return sql
    return sql.substring(0, keywordEnd) + "TOP 1 " + sql.substring(keywordEnd)
}
