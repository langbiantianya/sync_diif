package com.kxxnzstdsw.sync_diff.connectors

import java.util.Properties

private const val MAXCOMPUTE_DRIVER: String = "com.aliyun.odps.jdbc.OdpsDriver"


/**
 * MaxCompute 连接配置。
 *
 * JDBC URL 形态：`jdbc:odps:http://service.cn-shanghai.maxcompute.aliyun.com/api`。
 *
 * ```kotlin
 * MaxComputeConfig(
 *     jdbcUrl = "jdbc:odps:http://service.cn-shanghai.maxcompute.aliyun.com/api",
 *     user = "your_access_key_id",
 *     password = "your_access_key_secret",
 *     project = "your_project",
 * )
 * ```
 *
 * 环境变量注入：[fromEnv] 是唯一的入口（`MAXCOMPUTE_JDBC_URL` / `MAXCOMPUTE_USER` /
 * `MAXCOMPUTE_PASSWORD` / `MAXCOMPUTE_PROJECT` 全部必填，缺失即抛）。
 */
data class MaxComputeConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val project: String,
) {
    companion object {
        fun fromEnv(): MaxComputeConfig = MaxComputeConfig(
            jdbcUrl = System.getenv("MAXCOMPUTE_JDBC_URL")
                ?: error("MAXCOMPUTE_JDBC_URL is not set"),
            user = System.getenv("MAXCOMPUTE_USER")
                ?: error("MAXCOMPUTE_USER is not set"),
            password = System.getenv("MAXCOMPUTE_PASSWORD")
                ?: error("MAXCOMPUTE_PASSWORD is not set"),
            project = System.getenv("MAXCOMPUTE_PROJECT")
                ?: error("MAXCOMPUTE_PROJECT is not set"),
        )
    }
}

/**
 * MaxCompute 连接器。
 *
 * 建连 / 取数 / 失败包装的公共实现见 [JdbcConnector]。两处 MaxCompute 特有的差异：
 *
 * - **不设 `autoCommit=false`**：ODPS JDBC 不支持显式事务，用默认自动提交（README §6）；
 * - **project 走连接属性**：odps-jdbc 读的属性名是 `project_name`（URL 上的 `project` 等价），
 *   由 [MaxComputeConnector] 的 [project] 参数下发——不配 project 时驱动会连到默认 project，
 *   查询可能落到错误的库，所以**默认推荐用 [MaxComputeConfig] 构造**。
 *
 * ```kotlin
 * MaxComputeConnector(MaxComputeConfig.fromEnv()).use { conn ->
 *     conn.query("SELECT * FROM orders WHERE dt = '2026-09-01'")
 * }
 * ```
 */
class MaxComputeConnector(
    jdbcUrl: String,
    user: String,
    password: String,
    fetchSize: Int = DEFAULT_FETCH_SIZE,
    project: String? = null,
) : JdbcConnector(
    jdbcConnection(MAXCOMPUTE_DRIVER, jdbcUrl, user, password, maxComputeProperties(project)),
    fetchSize,
) {
    /** 构造自 [MaxComputeConfig]：把 `project` 一并下发（推荐入口）。 */
    constructor(cfg: MaxComputeConfig, fetchSize: Int = DEFAULT_FETCH_SIZE) : this(
        jdbcUrl = cfg.jdbcUrl,
        user = cfg.user,
        password = cfg.password,
        fetchSize = fetchSize,
        project = cfg.project,
    )
}

/**
 * odps-jdbc 的 project 连接属性：属性名是 `project_name`（驱动 `ConnectionResource.PROJECT_PROP_KEY`，
 * URL 上的写法是 `project=`）。[project] 为空时不下发，让驱动用自己的默认 project。
 *
 * 凭据不用操心的部分：[jdbcConnection] 下发的 `user` / `password` 正好是 access id / access key
 * 的别名（`ACCESS_ID_PROP_KEY_ALT` / `ACCESS_KEY_PROP_KEY_ALT`），驱动会按别名识别。
 */
private fun maxComputeProperties(project: String?): Properties =
    Properties().apply {
        if (!project.isNullOrBlank()) setProperty("project_name", project)
    }
