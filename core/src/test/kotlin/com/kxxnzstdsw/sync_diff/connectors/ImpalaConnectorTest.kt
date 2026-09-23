package com.kxxnzstdsw.sync_diff.connectors

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ImpalaConnector：无真实 Impala 可连时能测的**行为**契约。
 *
 * 只覆盖两件事：
 * 1. [ImpalaConfig.fromEnv] 的优先级（env > current，未设的字段沿用 current）——通过注入
 *    假 env 变得可确定，不去改进程环境变量；
 * 2. 构造期建连（fail fast）：url 连不上就在**构造时**抛 `SQLException`。
 *
 * 真实集群就位前，接口形态 / 方法签名不在这里断言——那是实现细节，`Connector` 的实现关系
 * 由编译器保证。
 */
class ImpalaConnectorTest {

    @Test
    fun `fromEnv overrides only the fields present in the environment`() {
        val current = ImpalaConfig("jdbc:hive2://current:21050", "cu", "cp")
        val env = mapOf(
            "IMPALA_JDBC_URL" to "jdbc:hive2://from-env:21050",
            "IMPALA_USER" to "eu",
        )

        val config = ImpalaConfig.fromEnv(current) { env[it] }

        assertEquals("jdbc:hive2://from-env:21050", config.jdbcUrl)
        assertEquals("eu", config.user, "env 里给了 user 就覆盖 current")
        assertEquals("cp", config.password, "env 里没有 password，沿用 current")
    }

    @Test
    fun `fromEnv keeps current fields when the environment is empty`() {
        val current = ImpalaConfig("jdbc:hive2://current:21050", "cu", "cp")

        val config = ImpalaConfig.fromEnv(current) { null }

        assertEquals(current, config)
    }

    @Test
    fun `construction connects eagerly and fails fast on an unreachable host`() {
        assertFailsWith<SQLException> {
            ImpalaConnector("jdbc:hive2://no-such-host.invalid:1", "u", "p", fetchSize = 100)
                .use { /* nothing */ }
        }
    }
}
