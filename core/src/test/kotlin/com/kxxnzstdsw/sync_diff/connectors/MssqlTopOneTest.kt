package com.kxxnzstdsw.sync_diff.connectors

import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [mssqlTopOne]：`Connector.one` 的 T-SQL 改写。
 *
 * H2 也认 `SELECT TOP n`，所以除了断言改写结果，再让 H2 **真的执行**改写后的语句——证明这不是
 * 一条"看起来对"的字符串，而是语法合法的 SQL。
 */
class MssqlTopOneTest {

    private val h2 = H2Connector(H2Config(URL)).apply {
        Class.forName("org.h2.Driver")
        DriverManager.getConnection(URL, "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS orders")
                st.execute("CREATE TABLE orders (order_id BIGINT, status VARCHAR)")
                st.execute("INSERT INTO orders VALUES (1, 'PAID'), (2, 'REFUND')")
            }
        }
    }

    @AfterTest
    fun tearDown() {
        h2.close()
    }

    private companion object {
        const val URL = "jdbc:h2:mem:mssql_top_one;DB_CLOSE_DELAY=-1"
    }

    @Test
    fun `rewrites a plain select into TOP 1`() {
        assertEquals("SELECT TOP 1 * FROM orders", mssqlTopOne("SELECT * FROM orders"))
    }

    @Test
    fun `keeps TOP after DISTINCT and tolerates leading whitespace and lowercase`() {
        // 只插入 `TOP 1 `，不改写原有大小写 / 空白（少一处"顺手格式化"就能少一处猜错）
        assertEquals(
            "  select distinct TOP 1 order_id from orders",
            mssqlTopOne("  select distinct order_id from orders"),
        )
        assertEquals(
            "SELECT ALL TOP 1 order_id FROM orders",
            mssqlTopOne("SELECT ALL order_id FROM orders"),
        )
    }

    @Test
    fun `leaves statements that already limit rows untouched`() {
        assertEquals("SELECT TOP 5 * FROM orders", mssqlTopOne("SELECT TOP 5 * FROM orders"))
    }

    @Test
    fun `refuses statements it cannot rewrite without parsing the grammar`() {
        assertFailsWith<IllegalArgumentException> {
            mssqlTopOne("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent")
        }
    }

    @Test
    fun `the rewritten statement is valid SQL for a TOP-capable engine`() {
        // 用 query 而不是 one：one 会再加一个 LIMIT 1（那是 H2Connector 的方言），
        // 这里要验证的正是 MSSQLConnector 交给 query 的那条语句本身。
        val rewritten = mssqlTopOne("""SELECT order_id AS "order_id", status AS "status" FROM orders ORDER BY order_id""")
        val row = h2.query(rewritten).single()

        assertEquals(1L, row?.get("order_id"))
    }
}
