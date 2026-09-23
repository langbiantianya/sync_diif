package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.ConnectorError
import com.kxxnzstdsw.sync_diff.core.longVal
import com.kxxnzstdsw.sync_diff.core.string
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * [JdbcConnector] 的契约测试，用内置的 H2 当 JDBC 方言替身。
 *
 * 覆盖三件事：
 * 1. 三个取数方法都真的能用（建会话 → 查询 → 归一后的 `Row`）；
 * 2. **失败一律抛 [ConnectorError.QueryFailed] 并带上原始 SQL**，`stream` 也不例外——它的
 *    `prepare/execute` 发生在迭代时，所以失败也在迭代时抛出，且同样必须是领域错误；
 * 3. [JdbcConnector.one] 的「只取一行」语义。
 *
 * 第 2 条是所有 JDBC 源共享的实现，历史上漏在 [JdbcConnector.stream] 之外过——这里锁住。
 */
class JdbcConnectorContractTest {

    private lateinit var connector: H2Connector

    @BeforeTest
    fun setUp() {
        // 建表用裸 JDBC：Connector.query 走 executeQuery()，只能跑查询，跑不了 DDL。
        // 凭据要与 Connector 一致：H2 2.x 用首个连接的用户建库，之后用别的用户连会 28000。
        // DB_CLOSE_DELAY=-1 让内存库在整个 JVM 存活，所以要按用例重建这张表。
        rawConnection().use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS orders")
                st.execute("CREATE TABLE orders (order_id BIGINT, amount DECIMAL(18,3), status VARCHAR)")
                st.execute("INSERT INTO orders VALUES (1, 100.000, 'PAID'), (2, 100.000, 'PAID'), (3, 105.000, 'REFUND')")
            }
        }
        connector = H2Connector(H2Config(URL))
    }

    private fun rawConnection(): Connection {
        Class.forName("org.h2.Driver") // 测试里也不假设 JDBC 4 自动注册一定生效
        return DriverManager.getConnection(URL, "sa", "")
    }

    @AfterTest
    fun tearDown() {
        connector.close()
    }

    private companion object {
        const val URL = "jdbc:h2:mem:jdbc_contract;DB_CLOSE_DELAY=-1"
    }

    @Test
    fun `query returns every matching row with normalized values`() {
        // H2 把未加引号的标识符折成大写，列标签要显式加引号才能得到 snake_case 的 Row key
        val rows = connector.query(
            """SELECT order_id AS "order_id", amount AS "amount", status AS "status" FROM orders ORDER BY order_id""",
        )

        assertEquals(3, rows.size)
        assertEquals(1L, rows.first().longVal("order_id"), "COUNT/BIGINT 归一成 Long")
        assertEquals("REFUND", rows.last().string("status"))
    }

    @Test
    fun `stream yields rows lazily and one returns only the first row`() {
        val streamed = connector.stream("""SELECT order_id AS "order_id" FROM orders ORDER BY order_id""")
            .mapNotNull { it.longVal("order_id") }
            .take(2)
            .toList()
        assertEquals(listOf(1L, 2L), streamed)

        val first = connector.one("""SELECT amount AS "amount", status AS "status" FROM orders ORDER BY order_id""")
        assertNotNull(first)
        assertEquals("PAID", first.string("status"))
    }

    @Test
    fun `query failure surfaces as ConnectorError_QueryFailed carrying the sql`() {
        val sql = "SELECT * FROM does_not_exist"

        val failure = assertFailsWith<ConnectorError.QueryFailed> { connector.query(sql) }

        assertEquals(sql, failure.sql, "领域错误必须带上原始 SQL")
        assertNotNull(failure.cause, "必须保留底层异常供排查")
    }

    @Test
    fun `stream failure surfaces as ConnectorError when the sequence is consumed`() {
        val sql = "SELECT * FROM does_not_exist"

        val rows = connector.stream(sql) // 此处不抛：还没开始迭代
        val failure = assertFailsWith<ConnectorError.QueryFailed> { rows.toList() }

        assertEquals(sql, failure.sql)
    }

    @Test
    fun `one failure surfaces as ConnectorError_QueryFailed`() {
        val failure = assertFailsWith<ConnectorError.QueryFailed> {
            connector.one("SELECT * FROM does_not_exist")
        }

        assertEquals("SELECT * FROM does_not_exist LIMIT 1", failure.sql)
    }
}
