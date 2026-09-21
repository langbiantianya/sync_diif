# sync_diff

> 分阶段适配上下游数据源的数据对账工具。上游 / 下游都由 Check 自己声明，框架不做兜底。

> **使用方式见 [docs/USAGE.md](docs/USAGE.md)**（命令行、写自己的 Check、字段规则、
> 新增数据源、调度集成、故障排查）。本文是设计与分阶段路线。

## 1. 核心思路

- **上下游同一套 Connector**：上游接 Parquet 用 `ParquetConnector`，下游接 Impala 用
  `ImpalaConnector`；Check 在 `Ctx.run()` 里直接 new 对应 Connector，不经过任何工厂封装。
  上游 / 下游接什么、用什么配置全在 Check 自己手里——`WilsonActivity*Check` 声明走
  Parquet + Impala，`OrderSyncCheck` 声明双 Parquet，再写一个 Check 想换源就直接
  `IcebergRESTConnector(...)` / `PostgresConnector(...)`。
- **上游按需**：遇到一个源接一个。阶段 1 先接 Parquet（用 DuckDB 读），后续 JDBC 源逐个补齐。
- **新增源零侵入**：加一个实现 `Connector` 的类即可，Check 里直接用它，不动任何已有代码。
- **代码即配置**：`Check` 对象直接写 SQL 与规则，不引入 Capabilities、PushdownPlanner 之类的元数据层。

Parquet 作为起点的原因：自带完整 schema、row group 流式扫描、天然支持对象存储，跳过 JDBC 流式参数调优，先把 diff 核心跑通。自测 / CI 用 Parquet 当上下游替身也是同一理由——不必起 Impala。

## 2. 架构

```mermaid
flowchart TD
    Check["Check (Kotlin 对象)<br/>自己持有上下游 Connector 与配置"]
    Parquet["ParquetConnector<br/>(DuckDB)"]
    Impala["ImpalaConnector<br/>(Hive JDBC)"]
    Future["PG / MySQL / MSSQL / MC / …<br/>(阶段 2+ 按需)"]
    Engine["DiffEngine"]
    Report["Reporter<br/>(Markdown + Webhook)"]

    Check --> Parquet
    Check --> Impala
    Check --> Future
    Parquet --> Engine
    Impala --> Engine
    Future --> Engine
    Engine --> Report
```

## 3. 接口

只有两个核心抽象。

### 3.1 Connector

两端统一接口，小结果集走 `query()`，大表走 `stream()`，单行走 `one()`。

```kotlin
interface Connector : Closeable {
    fun query(sql: String): List<Row>      // 小结果集
    fun stream(sql: String): Sequence<Row> // 大结果集，服务端游标
    fun one(sql: String): Row?             // 单行
}
```

### 3.2 DiffEngine

```kotlin
class DiffEngine {
    fun aggregate(
        src: List<Row>,
        tgt: List<Row>,
        keys: List<String>,
        rules: FieldRules.() -> Unit = {}   // 与 compare 共用同一套规则，见下
    ): List<DiffRow>
    fun keySet(src: Sequence<Row>, tgt: Sequence<Row>, key: String): Sequence<String>
    fun compare(s: Row, t: Row, rules: FieldRules.() -> Unit): List<FieldDiff>
    fun compareRows(
        src: Sequence<Row>,
        tgt: Sequence<Row>,
        key: (Row) -> Any,
        check: (Row, Row) -> List<FieldDiff>
    )
    fun summary(): DiffSummary
}
```

`aggregate` 的 `rules` 是必需的：L1 的 `SUM(amount)` 这类度量列，如果按 `==` 精确比对，
行级允许的数值漂移会在聚合层被误报成差异。传同一套 `FieldRules` 才能让两档口径一致：

```kotlin
engine.aggregate(srcAgg, tgtAgg, keys = listOf("dt")) {
    field("s") { tolerance(abs = 0.01) }   // SUM(amount)
    // 不声明的列（count / checksum）默认 ==
}
```

等价的中缀写法：`field("s") by tolerance(abs = 0.01)`。

## 4. Kotlin 特性约定（写"Kotlin"，不写"带缩进的 Java"）

> 下面所有约定都是**默认使用**——能扩展就不用工具类，能 inline 就不要样板，能 `Sequence` 就不要 `List`，能解构 + 命名参数就不要位置参数。

### 4.1 类型与数据建模

- `Row`、`DiffRow`、`FieldDiff`、`DiffSummary` 一律 `data class`：自动 `equals/hashCode/copy/toString`。
- 校验结果用 `sealed interface FieldDiff`，子类 `Equal` / `Mismatch` / `Missing`：配合 `when` 强制穷尽。
- `Check` 用 `sealed interface` / `abstract class`，每个具体校验是 `object`（天然单例、延迟初始化）。
- 字段规则集合是 `value class` 或 `data class`，避免原始字符串散落。

```kotlin
sealed interface FieldDiff {
    data class Mismatch(val field: String, val expected: Any?, val actual: Any?) : FieldDiff
    data object Equal : FieldDiff
}

data class Row(val values: Map<String, Any?>) {
    operator fun get(name: String): Any? = values[name]
    inline fun <reified T : Any> getAs(name: String): T? = values[name] as? T
}
```

### 4.2 扩展属性 / 扩展函数：业务语义挂到原生类型上

不要写 `RowUtil.getString(row, "amount")`。直接给 `Row` 加扩展属性/操作符索引。

```kotlin
val Row.str: Map<String, Any?> get() = values
val Row.columns: Set<String> get() = values.keys

val Row.intVal:    (String) -> Int?       get() = { name -> getAs<Int>(name) }
val Row.longVal:   (String) -> Long?      get() = { name -> getAs<Long>(name) }
val Row.decimal:   (String) -> BigDecimal? get() = { name -> getAs<BigDecimal>(name) }
val Row.instant:   (String) -> Instant?   get() = { name -> getAs<Instant>(name) }
val Row.string:    (String) -> String?    get() = { name -> getAs<String>(name) }

// 操作符索引 + 安全默认值，替换所有 getOrDefault(... ?: ...)
operator fun Row.invoke(name: String, default: Any? = null): Any? =
    values[name] ?: default

// ResultSet → Row 也是扩展函数（每个 Connector 自带）
fun ResultSet.toRow(): Row = Row((1..metaData.columnCount).associate { i ->
    metaData.getColumnLabel(i) to getObject(i)
})
fun ResultSet.toRows(): List<Row> = sequence { while (next()) yield(toRow()) }.toList()
```

JDBC 客户端用 `T.runCatching` 包成 `Result<T, SQLException>` 风格的领域类型，**别**用 Java 风格的 try/catch 嵌套。

```kotlin
inline fun <T> Connection.useConnection(block: (Connection) -> T): T =
    use(block)  // Closeable.use 已是 inline

inline fun <T> PreparedStatement.useQuery(
    fetchSize: Int = 10_000,
    block: (ResultSet) -> T
): T = use { st ->
    st.fetchSize = fetchSize
    st.executeQuery().use(block)
}
```

### 4.3 lambda + 接收者（DSL）

Check、FieldRules、Reporter 全部用接收者 lambda，省掉 builder 样板。

```kotlin
@DslMarker
annotation class CheckDsl

@CheckDsl
class FieldRules(private val rules: MutableMap<String, (Any?, Any?) -> Boolean>) {
    operator fun String.invoke(rule: (Any?, Any?) -> Boolean) { rules[this] = rule }

    // DSL 助手方法全部带接收者 lambda
    fun tolerance(abs: Double = 0.0, rel: Double = 0.0): (Any?, Any?) -> Boolean = { s, t ->
        val a = (s as? Number)?.toDouble(); val b = (t as? Number)?.toDouble()
        a != null && b != null && kotlin.math.abs(a - b) <= abs + kotlin.math.abs(b) * rel
    }
    fun ignore(): (Any?, Any?) -> Boolean = { _, _ -> true }
    val toUtc: (Any?, Any?) -> Boolean = { s, t -> s == t }  // 类型归一已在 Connector 内完成
}

// 自定义扩展就是普通函数 + 接收者；不是工具类静态方法
fun FieldRules.phoneNumber(): (Any?, Any?) -> Boolean = { s, t ->
    (s as? String)?.filter(Char::isDigit) == (t as? String)?.filter(Char::isDigit)
}
fun FieldRules.amountWithTax(rate: BigDecimal): (Any?, Any?) -> Boolean = { s, t ->
    (s as? BigDecimal)?.let { it * rate } == t as? BigDecimal
}
```

`infix` 让规则声明读起来像 DSL：

```kotlin
infix fun FieldRules.field(name: String): FieldSlot = FieldSlot(this, name)
class FieldSlot internal constructor(private val rules: FieldRules, private val name: String) {
    infix fun by(check: (Any?, Any?) -> Boolean) { rules.bind(name, check) }
}

// 在 compare 的接收者作用域里，`field` 的作用对象是 FieldRules，因此字段名写在后面：
diff.compare(s, t) {
    field("amount")       by tolerance(abs = 0.01)
    field("updated_at")   by toUtc
    field("status")       by ignore()
    field("phone")        by phoneNumber()
    field("amount_taxed") by amountWithTax(BigDecimal("1.06"))
}
```

> `infix` 调用是 `a f b` ≡ `a.f(b)`；省略接收者时 `field "amount"` 等价于 `field("amount")`，
> 所以也可以写成 `field "amount" by tolerance(abs = 0.01)`。字段名不能写在 `field` 左边。

builder 写法（§11.2）与上面的中缀写法完全等价，任选一种：

```kotlin
diff.compare(s, t) {
    field("amount")     { tolerance(abs = 0.01) }
    field("updated_at") { toUtc }          // toUtc 是属性，不要加括号
}
```

### 4.4 中缀 / 操作符 / 解构

凡是有"自然语言感"的接口都用 `infix`/`operator`。

```kotlin
// "src 与 tgt" / "dt 取 2026-09-01" 这类语义
infix fun String.eq_(other: String): Boolean = this == other  // 仅示例

// Sequence 上的差集、并集用 operator 函数（自定义语义风格）改造 L2
operator fun <T> Sequence<T>.minus(other: Sequence<T>): Sequence<T> where T : Any =
    other.toHashSet().let { ex -> filter { it !in ex } }

// Row 解构：按主键直接拿
operator fun Row.component1(): Any? = values.values.firstOrNull()  // 仅示例，按主键自行实现

// 默认值场景用命名参数，避免传 7 个 null
src.query(
    sql = "SELECT count(*) c FROM read_parquet(?)",
    args = listOf(path),
    fetchSize = 10_000
)
```

### 4.5 Sequence 惰性 + Scope Function 链

`Connector.stream` 已经是 `Sequence`；后续比较器必须保持惰性，避免把上亿条抽进 `List`。

```kotlin
// L2：两端主键集合做差，O(1) 额外内存
val onlyInSrc: Sequence<String> = (srcKeys - tgtKeys.toHashSet()).asSequence()
val onlyInTgt: Sequence<String> = (tgtKeys - srcKeys.toHashSet()).asSequence()

// L3 行级：take/sampled/distinctBy 都是惰性
val sample: Sequence<Row> = srcSeq
    .filter { it.instant("updated_at")!!.isAfter(cutoff) }
    .distinctBy { it.string("order_id") }
    .sampled(ratio = 0.01)            // 自定义扩展
    .take(1000)

fun <T> Sequence<T>.sampled(ratio: Double, seed: Long = 42L): Sequence<T> = sequence {
    val rng = java.util.SplittableRandom(seed)
    forEach { if (rng.nextDouble() < ratio) yield(it) }
}
```

Scope function 用法要克制：`apply` 配构造、`also` 配副作用、`with` 配临时作用域、`let` 配 null 安全链、`use` 配资源。

```kotlin
val diffSummary = diff.summary().also { report.markdown(path, it) }
    .let { if (it.hasDiff) report.webhook(env("ALERT_URL"), it); it }
```

### 4.6 协程与结构化并发（流式场景）

`Connector.stream` 同步；如果上游源天然支持异步或想并发跑两端的 L2，用 `Flow` + `coroutineScope`：

```kotlin
import kotlinx.coroutines.flow.*

suspend fun DiffEngine.keySetAsync(
    src: Flow<Row>, tgt: Flow<Row>, key: String
): Set<String> = coroutineScope {
    val s = src.map { it.string(key)!! }.toSetIn(this)
    val t = tgt.map { it.string(key)!! }.toSetIn(this)
    s + t                              // 或 s intersect t，看语义
}

private suspend fun <T> Flow<T>.toSetIn(scope: CoroutineScope): Set<T> = scope.async {
    toList().toSet()
}.await()
```

`Closeable` 资源放进 `CloseableCoroutineScope` 或 `use { }` 模板，不要裸起 `GlobalScope`。

### 4.7 构造：命名参数 + 默认值

Connector 直接 new，不为"统一入口"再包一层工厂——多一层类就要多写一份转发代码，
而且那层类往往只持有配置、只转发构造：

```kotlin
// 上游：Parquet（DuckDB）
val src = ParquetConnector("/data/orders/dt=2026-09-01/part-0.parquet", memoryLimit = "4GB")

// 下游：Impala（连接信息由 Check 持有，env 兜底）
val tgtImpalaCfg = ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT)   // IMPALA_JDBC_URL / USER / PASSWORD
val tgt = ImpalaConnector(tgtImpalaCfg)

// 下游换 Parquet 自测：同一套 Connector，一行切换
val tgtLocal = ParquetConnector("/tmp/tgt.parquet")
```

每个 Check 自己持有自己的连接配置（典型：`@Volatile var myCfg = ImpalaConfig.fromEnv(...)`）。
框架没有"全局默认下游"——Check 之间互不影响，换连接类时也不会牵动别的 Check。

"默认值"和"必填参数"用 Kotlin 的参数默认值表达，不要为组合参数再造 builder：

```kotlin
class ImpalaConnector(
    jdbcUrl: String,
    user: String? = null,
    password: String? = null,
    private val fetchSize: Int = DEFAULT_FETCH_SIZE,
) : Connector
```

一次性注册（reflection 一次，启动期完成）：

```kotlin
inline fun <reified T : Check> CheckRegistry.register() {
    register(T::class.simpleName!!, T)
}
```

### 4.8 错误处理：`Result` + 领域异常

不要抛裸 `SQLException` 一路冒泡。用 `runCatching` + 领域错误。

```kotlin
sealed interface ConnectorError : Throwable {
    val sql: String
    data class QueryFailed(override val sql: String, override val cause: Throwable) : ConnectorError
    data class TypeCoercion(val column: String, val from: String, val to: String) : ConnectorError {
        override val sql get() = "<type-coercion>"
    }
}

inline fun <T> Connector.guard(sql: String, block: () -> T): T =
    runCatching(block).getOrElse { e -> throw ConnectorError.QueryFailed(sql, e) }
```

### 4.9 Check 主体：全是 Kotlin 习惯

```kotlin
object OrderSyncCheck : Check("order_sync") {
    override suspend fun Ctx.run() {
        val dt = args.dt
        val src = ParquetConnector("/data/orders/dt=$dt/part-0.parquet")
        val tgt = ImpalaConnector(ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT))

        // L1：行数 + 合计 + checksum
        val aggDiffs = diff.aggregate(
            src  query "SELECT '$dt' ccount, count(*) c, sum(amount) s, sum(hash(...)) h FROM read_parquet('/data/orders/dt=$dt/*.parquet')",
            tgt  query "SELECT dt, count(*) c, sum(amount) s, sum(fnv_hash(...)) h FROM ods.orders WHERE dt='$dt' GROUP BY dt",
            keys = listOf("dt")
        )

        // L2：哈希桶采样，主键集合做差（惰性）
        val keySet = with(aggDiffs.first()) {
            diff.keySet(
                src  stream "SELECT order_id FROM read_parquet('/data/orders/dt=$dt/*.parquet') WHERE hash(order_id)%100 < ${ratio(1)}" mapBy "order_id",
                tgt  stream "SELECT order_id FROM ods.orders WHERE dt='$dt' AND fnv_hash(order_id)%100 < ${ratio(1)}" mapBy "order_id"
            ).toList()
        }

        // L3：行级 diff + 顶层 N 抽样（协程并发拉两侧）
        keySet.take(1000).forEachParallel { key ->
            val s = src.one(...) ?: return@forEachParallel
            val t = tgt.one(...) ?: return@forEachParallel
            diff.compare(s, t) {
                "amount"       field by tolerance(abs = 0.01)
                "updated_at"   field by toUtc
                "status"       field by ignore()
                "phone"        field by phoneNumber()
            }
        }

        report.markdown("reports/order_sync.md", diff.summary())
        diff.summary().takeIf { it.hasDiff }?.let { report.webhook(env("ALERT_URL"), it) }
    }
}

// 顶层内联中缀：把 SQL 串成一句
infix fun Connector.query(sql: String): List<Row> = query(sql)
infix fun Connector.stream(sql: String): Sequence<Row> = stream(sql)
inline infix fun <T> Sequence<T>.mapBy(getter: String): Sequence<String> = mapNotNull { it.string(getter) }
inline infix fun <T> Iterable<T>.forEachParallel(crossinline block: suspend (T) -> Unit) =
    runBlocking { map { async { block(it) } }.awaitAll() }
```

### 4.10 风格红线

| 反例（Java 风） | 正例（Kotlin 风） |
|:---|:---|
| `RowUtil.getString(row, "x")` | `row.string("x")` 扩展属性 |
| `new Builder().setA(a).setB(b).build()` | `buildList { add(a); add(b) }` / DSL |
| `for (int i = 0; i < n; i++) ...` | `(0 until n).forEach { ... }` |
| `if (x != null) { ... x ... }` | `x?.let { ... }` |
| `if (x == null) throw new IAE()` | `requireNotNull(x)` |
| `if (x is Foo) { val y = x; ... }` | `if (x is Foo) { ... }` 智能转型 |
| `try { ... } catch (Exception e) { log }` | `runCatching { ... }.onFailure { log }` |
| `instanceof Foo ? ((Foo)x).foo() : null` | `(x as? Foo)?.foo()` |
| `List<String> -> Stream<String> -> List<String>` | `Sequence` 全程保持惰性 |
| `public static final` 常量 | `const val` / `object` 单例 |
| `equals/hashCode/toString` 手写 | `data class` |
| `switch (x) { case A: ...; default: }` | `when (x) { is A -> ...; else -> ... }` |

## 5. 阶段 1：Parquet + Impala

### 5.1 ParquetConnector

DuckDB 读 Parquet，row group 流式扫描，不依赖 JDBC 游标参数。

```kotlin
class ParquetConnector(
    private val path: String,
    private val memoryLimit: String = "4GB",
    private val tempDir: String = "/tmp/duckdb_spill"
) : Connector {

    private val conn: Connection = DriverManager.getConnection("jdbc:duckdb:").apply {
        autoCommit = false
        createStatement().use { st ->
            st.execute("SET memory_limit = '$memoryLimit'")
            st.execute("SET temp_directory = '$tempDir'")
            if (path.startsWith("s3://") || path.startsWith("oss://")) {
                st.execute("INSTALL httpfs; LOAD httpfs;")
            }
        }
    }

    override fun query(sql: String): List<Row> =
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs -> rs.toRows() }
        }

    override fun stream(sql: String): Sequence<Row> = sequence {
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) yield(rs.toRow())
            }
        }
    }

    override fun one(sql: String): Row? = query("$sql LIMIT 1").firstOrNull()

    override fun close() = conn.close()
}
```

> 注：生产里 `sql` 参数须用参数化预编译或经白名单校验；演示 SQL 采用字符串拼接只为简洁。

### 5.2 使用

```kotlin
val src = ParquetConnector("/data/orders/dt=2026-09-01/part-0.parquet")
val tgt = ImpalaConnector(ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT))  // 生产：连 Impala
val tgtLocal = ParquetConnector("/tmp/tgt.parquet")                    // 自测：直接读本地 parquet

// 对象存储
val srcS3 = ParquetConnector("s3://bucket/orders/dt=2026-09-01/part-0.parquet")
```

### 5.3 交付物与周期

1 周。包含：

- `ParquetConnector`（DuckDB）、`ImpalaConnector`（Hive JDBC）——上下游共用同一套接口
- `DiffEngine`（L1 聚合 + L2 主键集合 + L3 行级）
- `FieldRules` DSL（内置规则 + 自定义扩展函数）
- `Check` 抽象 + `CheckRegistry`（连接配置由 Check 自己持有，框架不做兜底）
- `Reporter`（Markdown + Webhook）
- CLI（Clikt）

### 5.4 验证点

- DuckDB 读大 Parquet 不 OOM（row group 流式 + spill）
- Impala JDBC 连接稳定
- L1/L2/L3 diff 结果正确
- 自定义规则 lambda 可扩展
- 对象存储（S3/OSS）可读

## 6. 阶段 2+：JDBC 源

每个源只新增一个 `Connector` 类。关键差异在游标参数与类型归一。

| 阶段 | 源 | 关键参数 | 周期 | 陷阱 |
|:---:|:---|:---|:---:|:---|
| 2 | PostgreSQL | `autoCommit=false` + `fetchSize=10000` | 2-3 天 | 不关 autoCommit 会全量拉回 |
| 3 | MySQL | `useCursorFetch=true` + `setFetchSize(Integer.MIN_VALUE)` | 2-3 天 | 流式期间连接不能复用 |
| 4 | MSSQL | `responseBuffering=adaptive` | 2-3 天 | 默认全缓冲 |
| 5 | MaxCompute | ODPS JDBC + Tunnel 分批 | 3-5 天 | Tunnel 单次 1 万行限制需放开，类型映射非标准 |

PostgreSQL 示例：

```kotlin
class PostgresConnector(dsn: String, private val fetchSize: Int = 10_000) : Connector {
    private val conn = DriverManager.getConnection(dsn).apply { autoCommit = false }

    override fun stream(sql: String): Sequence<Row> = sequence {
        conn.prepareStatement(sql).use { st ->
            st.fetchSize = fetchSize
            st.executeQuery().use { rs -> while (rs.next()) yield(rs.toRow()) }
        }
    }

    override fun query(sql: String): List<Row> = stream(sql).toList()
    override fun one(sql: String): Row? = stream("$sql LIMIT 1").firstOrNull()
    override fun close() = conn.close()
}
```

## 7. Check 示例（阶段 1）

```kotlin
object OrderSyncCheck : Check("order_sync") {
    override fun Ctx.run() {
        val dt = args.dt
        val src = ParquetConnector("/data/orders/dt=$dt/part-0.parquet")
        val tgt = ImpalaConnector(ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT))

        // L1 聚合：行数、合计、checksum
        val srcAgg = src.query("""
            SELECT '$dt' AS dt, COUNT(*) AS c, SUM(amount) AS s,
                   SUM(hash(concat_ws('|', order_id, amount, status))) AS h
            FROM read_parquet('/data/orders/dt=$dt/*.parquet')
        """)
        val tgtAgg = tgt.query("""
            SELECT dt, COUNT(*) AS c, SUM(amount) AS s,
                   SUM(fnv_hash(concat_ws('|', order_id, amount, status))) AS h
            FROM ods.orders WHERE dt = '$dt' GROUP BY dt
        """)
        val aggDiffs = diff.aggregate(srcAgg, tgtAgg, keys = listOf("dt"))

        // L2 主键集合（按哈希桶采样）
        aggDiffs.map { it.key("dt") }.forEach { d ->
            val srcKeys = src.stream("""
                SELECT order_id FROM read_parquet('/data/orders/dt=$d/*.parquet')
                WHERE hash(order_id) % 100 < ${ratio(1)}
            """)
            val tgtKeys = tgt.stream("""
                SELECT order_id FROM ods.orders
                WHERE dt = '$d' AND fnv_hash(order_id) % 100 < ${ratio(1)}
            """)
            diff.keySet(srcKeys, tgtKeys, key = "order_id")
        }

        // L3 行级 diff（仅前 1000 条）
        diff.diffKeys().take(1000).forEach { key ->
            val s = src.one("""
                SELECT * FROM read_parquet('/data/orders/dt=$dt/*.parquet')
                WHERE order_id = '$key'
            """) ?: return@forEach
            val t = tgt.one("SELECT * FROM ods.orders WHERE order_id = '$key'") ?: return@forEach
            diff.compare(s, t) {
                field("amount")     { tolerance(abs = 0.01) }
                field("updated_at") { toUtc }      // toUtc 是属性，不加括号
                field("status")     { ignore() }
                field("phone")      { phoneNumber() } // 自定义扩展
            }
        }

        report.markdown("reports/order_sync.md", diff.summary())
        if (diff.summary().hasDiff) report.webhook(env("ALERT_URL"), diff.summary())
    }
}
```

## 8. 没有工厂层

`Connector` 就是唯一抽象，Check 里直接 new 具体实现：

```kotlin
// 阶段 1：上下游连接信息由 Check 自己持有（典型 `@Volatile var cfg = ImpalaConfig.fromEnv(...)`）
val src = ParquetConnector("/data/orders/dt=2026-09-01/part-0.parquet")   // DuckDB
val tgt = ImpalaConnector(myImpalaConfig)                                // Hive JDBC

// 阶段 2+
val srcPg = PostgresConnector("jdbc:postgresql://host:5432/orders")
```

不设 `ConnectorFactory` 之类的"统一入口"：那种类最终只会是一堆
`fun x(...) = XConnector(...)` 的转发方法，调用方多绕一层，收益为零。
新增源 = 写一个实现 `Connector` 的类 + 在 Check 里 new 它，同样零侵入。

需要按配置在运行时选源时，在 Check 里写 `when` 即可——选择逻辑属于业务，不属于连接器。

## 9. 类型归一

每个 Connector 在 `rs.toRow()` 内做归一，外部一律 `Instant` / `BigDecimal` / `String(UTF-8)`。

| 源类型 | 归一到 | 说明 |
|:---|:---|:---|
| Parquet `TIMESTAMP` | `Instant(UTC)` | Parquet 自带 schema，归一最简 |
| Parquet `DECIMAL` | `BigDecimal` | 保精度 |
| PG `TIMESTAMPTZ` | `Instant(UTC)` | 直接转 |
| MySQL `DATETIME` | `Instant(UTC)` | 按源默认时区转 |
| MSSQL `DATETIMEOFFSET` | `Instant(UTC)` | 带偏移归一 |
| MaxCompute `DATETIME` | `Instant(UTC)` | 默认 UTC+8 转 UTC |
| 字符串 | `String(UTF-8)` | 统一编码 |
| NULL | `null` | 统一 |

## 10. 内存控制

- **Parquet 侧**：DuckDB `read_parquet` 按 row group 流式扫描；`memory_limit` + `temp_directory` 兜底 spill。
- **JDBC 侧**：所有 `stream()` 走服务端游标、逐行 `Sequence yield`；`query()` 只用于小结果集。
- **DiffEngine**：
  - L1 聚合：O(分区数)，全量进内存。
  - L2 主键集合：流式归并，O(1) 额外内存。
  - L3 行级：逐行比对，O(1)。
  - 差异样本：top-N 有界队列。

## 11. 自定义校验

### 11.1 字段规则（扩展函数）

```kotlin
fun FieldRules.phoneNumber() = add { s, t ->
    val a = s as? String ?: return@add false
    val b = t as? String ?: return@add false
    a.replace(Regex("\\D"), "") == b.replace(Regex("\\D"), "")
}

fun FieldRules.amountWithTax(rate: BigDecimal) = add { s, t ->
    val a = s as? BigDecimal ?: return@add false
    val b = t as? BigDecimal ?: return@add false
    a.multiply(rate) == b
}
```

### 11.2 使用

```kotlin
diff.compare(s, t) {
    field("amount")       { tolerance(abs = 0.01) }
    field("updated_at")   { toUtc }        // toUtc 是属性（val），不加括号
    field("status")       { ignore() }
    field("phone")        { phoneNumber() }
    field("amount_taxed") { amountWithTax(BigDecimal("1.06")) }
    field("order_id")     { custom { a, b -> a?.toString()?.lowercase() == b?.toString()?.lowercase() } }
}
```

### 11.3 整行校验

```kotlin
diff.compareRows(srcStream, tgtStream, key = { it["order_id"] }) { s, t ->
    buildList {
        if (s.decimal("amount") * BigDecimal("1.06") != t.decimal("amount_taxed"))
            add(FieldDiff("amount_taxed", s["amount"], t["amount_taxed"]))
    }
}
```

## 12. 数据流

```mermaid
sequenceDiagram
    participant CLI
    participant Check
    participant Src as Connector(上游)
    participant Tgt as Connector(下游，Check 自己选)
    participant Engine as DiffEngine
    participant Reporter

    CLI->>Check: 执行 + 参数
    Check->>Src: L1 query / L2 stream / L3 one
    Check->>Tgt: L1 query / L2 stream / L3 one
    Src-->>Engine: Row 流
    Tgt-->>Engine: Row 流
    Engine->>Reporter: DiffSummary
    Reporter-->>CLI: Markdown 报告
    Reporter-->>CLI: Webhook 告警(可选)
```

## 13. 技术栈

| 层 | 选型 | 版本 |
|:---|:---|:---|
| 语言 | Kotlin JVM | 2.4.20 |
| JDK | 编译 / 运行 / 构建 JVM | 17（Gradle 9 本身要求 ≥17，构建前 `JAVA_HOME` 必须指向 JDK 17） |
| 构建 | Gradle 多模块 + Shadow Fat JAR | Gradle 9.7.1 + Shadow 9.6.1 |
| CLI | Clikt | 5.1.0 |
| 并发 | Kotlin 协程 + Flow | kotlinx-coroutines 1.11.0 |
| 上游阶段 1 | DuckDB JDBC 读 Parquet | duckdb_jdbc 1.5.5.1 |
| 下游 | Impala Hive JDBC | ImpalaJDBC41 2.6.4 |
| 上游阶段 2+ | 各源 JDBC 驱动 | — |
| 报告 | Markdown / Webhook | JDK `HttpURLConnection`，无额外依赖 |
| 调度 | Airflow / DolphinScheduler 触发 CLI | — |

> Fat JAR 开了 `failOnDuplicateEntries = true` 严格模式：依赖树里出现重复条目直接构建失败。
> 当前唯一需要合并的是 jna / ImpalaJDBC41 各带一份的 `META-INF/LICENSE|NOTICE|DEPENDENCIES`，
> 由 `append(...)` 追加合并而非丢弃。

## 14. 实施计划

| 阶段 | 内容 | 新增代码 | 周期 |
|:---:|:---|:---|:---:|
| 1 | Parquet + Impala + DiffEngine + FieldRules + CLI | `ParquetConnector`、`ImpalaConnector` | 1 周 |
| 2 | PostgreSQL | `PostgresConnector` | 2-3 天 |
| 3 | MySQL | `MySQLConnector` | 2-3 天 |
| 4 | MSSQL | `MSSQLConnector` | 2-3 天 |
| 5 | MaxCompute | `MaxComputeConnector` | 3-5 天 |
| 6+ | 按需 | 每源一文件 | 按需 |

每个阶段只新增 `Connector`，不改已有代码。

## 15. 风险

| 风险 | 等级 | 缓解 |
|:---|:---:|:---|
| 采样一致性（两端哈希不同） | 高 | 在程序侧统一哈希，或两端使用可对齐表达式 |
| JDBC 源类型归一 bug | 高 | Parquet 起步降低首版风险；上线前用真实数据集回归 |
| JDBC 流式行为差异 | 中 | 每个源按 §6 参数单独验证，不能套用其他源 |
| 调度与连接生命周期 | 中 | Connector 实现 `Closeable`；由 Check 统一管理 |
| SQL 注入（字符串拼接示例） | 中 | 生产中所有动态值必须参数化或经白名单校验 |

**难度分布**：Check / Connector / DiffEngine ≈★★☆；FieldRules DSL ≈★★☆；ParquetConnector ≈★☆☆；类型归一（JDBC）≈★★★★；采样一致性 ≈★★★★。

## 16. 目录

Gradle 多模块，依赖方向单向：`app → checks → core`。

| 模块 | 职责 | 主要依赖 |
|:---|:---|:---|
| `core` | 对账内核：Row / DiffEngine / Connector / FieldRules / Reporter | Kotlin、协程、DuckDB JDBC、Impala JDBC |
| `checks` | Check 抽象与注册表 + 具体对账（`OrderSyncCheck`、`WilsonActivity*Check`） | `core`、kotlin-reflect |
| `app` | CLI 入口 + Shadow Fat JAR（可执行产物） | `checks`、`core`、Clikt |

```
sync_diff/
├── settings.gradle.kts                  # include("core") / "checks" / "app"
├── gradle.properties
├── docs/USAGE.md                        # 使用手册
├── core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/kxxnzstdsw/sync_diff/
│       │   ├── core/         # Row / FieldDiff / DiffRow / DiffSummary / Connector
│       │   │                 # ConnectorError / FieldRules(DSL) / SequenceExt
│       │   ├── engine/       # DiffEngine（L1 聚合 / L2 主键集合 / L3 行级）
│       │   ├── connectors/   # ParquetConnector(DuckDB) / ImpalaConnector(Hive JDBC) / ResultSetExt
│       │   └── reporter/     # Reporter（Markdown + Webhook）
│       └── test/kotlin/com/kxxnzstdsw/sync_diff/    # 与 main 同构
├── checks/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/kxxnzstdsw/sync_diff/
│       │   ├── check/        # Check / Ctx / CheckBase / Alertable / CheckRegistry / BuiltinCheck
│       │   └── checks/       # 具体对账：OrderSyncCheck（@BuiltinCheck，启动期注解扫描注册）
│       └── test/java/com/kxxnzstdsw/sync_diff/      # 与 main 同构
└── app/
    ├── build.gradle.kts      # shadowJar + Main-Class 在这里
    └── src/main/java/com/kxxnzstdsw/sync_diff/Main.kt   # SyncDiffCli（Clikt 入口）
```

> `checks` / `app` 的 Kotlin 源码放在 `src/main/java` 下（Kotlin 插件同样编译该目录），
> `core` 用的是 `src/main/kotlin`。新增文件时跟随所在模块现有的目录约定即可。
>
> 可执行产物在 `app/build/libs/sync_diff-all.jar`（不是根目录的 `build/libs`）。

阶段 1 的实现已经跑通：

```bash
# 构建 JVM 必须是 JDK 17（Gradle 9 要求 ≥17）
export JAVA_HOME=/path/to/jdk-17

./gradlew build shadowJar
java -jar app/build/libs/sync_diff-all.jar --check order_sync --dt 2026-09-20

# 只跑某个模块的测试
./gradlew :core:test :checks:test
```

上游路径由 `ORDERS_PARQUET_PATH` / `ORDERS_TGT_PARQUET_PATH` 控制（`OrderSyncCheck` 阶段 1
两端都走 Parquet 以便零依赖自测；生产要把下游换成 Impala，去 `OrderSyncCheck.defaultConnectors()`
里替换 Connector、或在 `OrderSyncCheck.injected` 上注入一对目标 Connector）。Wilson 域的两个
Check 直接以 `IMPALA_URL` / `IMPALA_JDBC_URL` / `IMPALA_USER` / `IMPALA_PASSWORD` 兜底。