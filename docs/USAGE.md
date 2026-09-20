# sync_diff 使用手册

面向「要拿它做对账」的人。设计动机与分阶段路线见 [README](../README.md)；本文只讲怎么用、
怎么扩展、以及会踩到什么坑。

- [1. 快速开始](#1-快速开始)
- [2. 命令行](#2-命令行)
- [3. 写自己的 Check](#3-写自己的-check)
- [4. 三档 diff](#4-三档-diff)
- [5. 字段规则 FieldRules](#5-字段规则-fieldrules)
- [6. 新增一个上游数据源](#6-新增一个上游数据源)
- [7. 报告与告警](#7-报告与告警)
- [8. 配置与环境变量](#8-配置与环境变量)
- [9. 调度集成](#9-调度集成)
- [10. 本地自测](#10-本地自测)
- [11. 故障排查](#11-故障排查)
- [12. 已知边界](#12-已知边界)

---

## 1. 快速开始

### 项目结构

Gradle 多模块，依赖方向单向 `app → checks → core`：

| 模块 | 职责 | 源码目录 |
|:---|:---|:---|
| `core` | 对账内核：`Row` / `DiffEngine` / `Connector` / `FieldRules` / `Reporter` / 配置工厂 | `core/src/main/kotlin` |
| `checks` | `Check` 抽象与注册表 + 具体对账（`OrderSyncCheck`） | `checks/src/main/kotlin` |
| `app` | CLI 入口 + Shadow Fat JAR | `app/src/main/kotlin` |

改代码前先对号入座：新增**数据源**动 `core`，新增**一条对账**动 `checks`，
改**命令行**动 `app`。细节见 [README §16](../README.md#16-目录)。

> `checks` / `app` 的 Kotlin 源码放在 `src/main/kotlin` 下（Kotlin 插件同样编译该目录），
> `core` 用的是 `src/main/kotlin`。跟随所在模块现有约定即可。

### 环境要求

| 项 | 要求 |
|:---|:---|
| JDK | **17**，且必须是构建 JVM：Gradle 9 自身要求 ≥17，用 JDK 8 跑 `./gradlew` 会直接报 `Gradle requires JVM 17 or later to run` |
| 构建 | 自带 Gradle Wrapper，无需本机装 Gradle（Gradle 9.7.1） |
| 语言 | Kotlin 2.4.20、Shadow 9.6.1 |

```bash
# 构建前确保 JAVA_HOME 指向 JDK 17
export JAVA_HOME=/path/to/jdk-17
java -version     # 应输出 17.x
```

若不想每次 export，可在 `gradle.properties` 里固定：

```properties
org.gradle.java.home=/absolute/path/to/jdk-17
```

### 构建

```bash
./gradlew build shadowJar
# → app/build/libs/sync_diff-all.jar   （fat jar，含 DuckDB 与 Impala JDBC）
```

产物在 **`app/build/libs/`**，不是根目录的 `build/libs`（那里没有可执行 jar）。

`shadowJar` 开了 `failOnDuplicateEntries = true`：依赖树里一旦出现重复条目直接构建失败。
这是有意的——重复条目通常意味着同一份类被两个依赖各自打包（曾经 `reload4j` 与 Impala
驱动内嵌的 log4j 就这样撞过）。目前唯一需要合并的是几个包里各带一份的法务元数据，由
`append(...)` 追加合并。

> `Main-Class` 在 `app/build.gradle.kts` 的 `shadowJar` 里配置（当前
> `com.kxxnzstdsw.sync_diff.MainKt`）。改动 `Main.kt` 的包名时必须同步改这里，否则
> `java -jar` 会报 `找不到或无法加载主类`。

### 跑内置 Check

内置的 `order_sync` 阶段 1 两端都走 Parquet，所以没有 Impala 也能跑通：

```bash
# 1) 造两个 parquet（见 §10，或直接用你自己的数据）
# 2) 指向上游 / 目标端文件
export ORDERS_PARQUET_PATH=/data/orders/dt=2026-09-20/part-0.parquet
export ORDERS_TGT_PARQUET_PATH=/data/orders_tgt/dt=2026-09-20/part-0.parquet

java -jar app/build/libs/sync_diff-all.jar --check order_sync --dt 2026-09-20
```

成功退出码 `0`，并在 `reports/order_sync_2026-09-20.md` 落一份报告。
下面是按 §10 那份 fixture（源端 3 行；目标端缺 1 行 + 1 行金额漂移）跑出来的真实输出：

```markdown
# Diff Summary

| metric | value |
|:---|---:|
| level | WARN |
| src rows | 3 |
| tgt rows | 2 |
| diff keys | 3 |

> hasDiff = true

Top diff keys are reported in the linked L3 detail file.
```

3 条差异分别是：L1 分区（count 3 ≠ 2）、L3 缺行（order_id=3）、L3 金额超容（order_id=2）。

---

## 2. 命令行

### 选项

| 选项 | 必填 | 默认值 | 环境变量 | 说明 |
|:---|:---:|:---|:---|:---|
| `--check` | ✅ | — | — | 要跑的 Check 名（区分大小写），与 `Check.name` 对齐 |
| `--dt` | | `1970-01-01` | — | 上游分区日，装进 `Check.Args.dt` |
| `--impala-url` | | `jdbc:impala://localhost:21050` | `IMPALA_URL` | 下游 Impala JDBC URL |
| `--alert-url` | | 空（不发告警） | `ALERT_URL` | 告警 webhook；只有实现 `Alertable` 的 Check 会收到 |
| `--registry` | | `checks.txt` | — | Check 注册清单文件路径 |
| `-h`, `--help` | | — | — | 打印用法 |

优先级：**命令行 > 同名环境变量 > 默认值**。

### 示例

```bash
# 列出用法
java -jar app/build/libs/sync_diff-all.jar --help

# 用显式清单文件启动（清单里写 FQCN，见 §3.4）
java -jar app/build/libs/sync_diff-all.jar --check order_sync --registry conf/checks.txt

# 带告警地址
java -jar app/build/libs/sync_diff-all.jar --check order_sync --alert-url "$ALERT_URL"

# 只靠环境变量（命令行不传 --impala-url / --alert-url）
IMPALA_URL='jdbc:impala://impala-prod:21050/default' ALERT_URL="$ALERT_URL" \
  java -jar app/build/libs/sync_diff-all.jar --check order_sync --dt 2026-09-20
```

### 退出码与失败行为

| 情形 | 行为 | 退出码 |
|:---|:---|:---:|
| 正常跑完（有无差异都算正常） | 落报告，返回 | `0` |
| 选项缺失 / 非法 | Clikt 打印 `Error: missing option --check` + 用法 | `1` |
| `--check` 名字不存在 | `Error: Unknown check: xxx. Available: order_sync, wilson_apply_detail_sync, wilson_event_header_sync` | `1` |
| 清单文件里某行反射不出来 | `Error: Registry entry '...' cannot be loaded: ...` | `1` |
| 清单文件不存在 / 无有效行 | **不是错误**：fallback 到内置 Check | 照常跑 |
| 对账执行期失败（文件不存在 / SQL 错 / 连不上库） | 抛 `ConnectorError`，未捕获，打印堆栈 | `1` |

**启动期**错误（选项、Check 名、清单文件）统一由 Clikt 渲染成一行 `Error: ...`，不带 JVM
堆栈——调度日志里一眼看懂：

```text
$ java -jar app/build/libs/sync_diff-all.jar --check nope
Unknown check: nope. Available: order_sync, wilson_apply_detail_sync, wilson_event_header_sync   # 实际带 Error: 前缀
```

**执行期**失败则保留完整堆栈（排查需要），但异常信息本身已经带上了原始 SQL 与底层 cause：

```text
QueryFailed(sql=SELECT '2026-09-20' AS dt, COUNT(*) AS c, ... FROM read_parquet('/tmp/orders.parquet'),
            cause=java.sql.SQLException: IO Error: No files found that match the pattern "/tmp/orders.parquet"
```

所有 Connector 的失败都会被 `guard` 包成 `ConnectorError.QueryFailed`，因此日志里不会出现
裸的 `SQLException` 而看不到是哪个查询挂的。

> 注意：`hasDiff = true` **不会**让退出码变成非零。设计上「对账发现了差异」是任务跑成功，
> 该不该告警由 `--alert-url` / 报告消费方决定。要在调度里把差异当失败，得自己解析退出码
> 之外的产物（报告或 webhook）。

---

## 3. 写自己的 Check

### 3.1 最小骨架

一个 Check = 一个 `object` + 一个 `Ctx.run()`：

```kotlin
package com.example.checks

import com.kxxnzstdsw.sync_diff.check.CheckBase
import com.kxxnzstdsw.sync_diff.check.Ctx

object UserSyncCheck : CheckBase("user_sync") {
    override suspend fun Ctx.run() {
        val dt = args.dt
        val src = source parquet "/data/users/dt=$dt/part-0.parquet"   // 上游工厂
        val tgt = target impala "ods.users"                            // 下游工厂

        val sql = "SELECT COUNT(*) AS c FROM read_parquet('/data/users/dt=$dt/part-0.parquet')"
        val srcAgg = src query sql
        val tgtAgg = tgt query "SELECT COUNT(*) AS c FROM ods.users WHERE dt = '$dt'"

        diff.aggregate(srcAgg, tgtAgg, keys = listOf("dt"))
        report.markdown("reports/user_sync_$dt.md", diff.summary())
    }
}
```

`Ctx` 里能直接用的东西：

| 名字 | 来源 | 说明 |
|:---|:---|:---|
| `args` | `Check.Args`，由 `runWith` 注入 | CLI 从 `--dt` 造出来；`args.copy(dt = ...)` 派生新参数 |
| `source` / `target` | `Check` 的工厂属性，读 `GlobalConfig.current` | 造 Connector；谁造谁 `use { }` 关 |
| `diff` | 本次执行的 `DiffEngine` | **每次执行新建**，所以并发跑同一 Check 不会共享计数器 |
| `report` | 本次执行的 `Reporter` | Markdown + Webhook |

`Ctx` 是**每次执行一个实例**（不是 `object`）。`DiffEngine` 带可变计数器、`Reporter` 不是
线程安全的，做成单例会让并发执行互相污染，所以 `Check.runWith` 每次都 new 一个。

### 3.2 参数的取用

```kotlin
data class Args(val dt: String = "1970-01-01", val params: Map<String, String> = emptyMap())

val dt = args.dt
val table = args.params["tgt_table"] ?: "ods.orders"     // 取不到就是没配
val yesterday = args.copy(dt = "2026-09-19")             // 派生，不改原值
```

`params` 是自由字典，适合放表名 / 库名 / 白名单这类不该散在代码里的东西。

### 3.3 接 Impala（阶段 2+ 的上游也一样）

```kotlin
override suspend fun Ctx.run() {
    val src = source parquet "/data/orders/dt=${args.dt}/part-0.parquet"
    val tgt = target impala "ods.orders"

    // 两侧 SQL 各写自己的方言，Connector 负责把结果归一成 Row
}
```

`Connector.query/stream/one` 收的是**完整 SQL**，表名必须自己写全限定——
`target impala "ods.orders"` 的参数目前只用于对齐工厂签名（见 §12）。

### 3.4 让 CLI 找到它

两种方式：

**方式一：注册清单文件**（推荐生产用，不改代码即可增删 Check）

```text
# conf/checks.txt
# 每行一个 FQCN；空行与 # 之后的内容会被忽略

com.kxxnzstdsw.sync_diff.checks.OrderSyncCheck   # 内置示例
com.example.checks.UserSyncCheck
```

```bash
java -jar app/build/libs/sync_diff-all.jar --check user_sync --registry conf/checks.txt
```

清单里某行写错会**启动即失败**（而不是静默少跑一个对账），这是刻意的：
少跑一个对账比启动失败危险得多。

**方式二：加进内置注册**（改代码，注解扫描）

```kotlin
// checks/src/main/kotlin/com/kxxnzstdsw/sync_diff/checks/UserSyncCheck.kt
@BuiltinCheck                    // 就这一行；不用在任何清单里登记
object UserSyncCheck : CheckBase("user_sync") {
    override suspend fun Ctx.run() { /* ... */ }
}
```

清单文件缺失时走这条内置路径：`DiscoverBuiltin.registerAll` 扫描 `checks` 模块的
`com.kxxnzstdsw.sync_diff.checks` 包，把带 `@BuiltinCheck` 的 `object` 全部注册。
执行顺序 = `@BuiltinCheck(order = ...)` 升序（默认 `0`）、同 order 按类名字典序，
可用 `@BuiltinCheck(order = -10)` 把某个 Check 提到前面。

你自己的 Check 放在 `checks` 模块里（`checks/src/main/kotlin/com/kxxnzstdsw/sync_diff/checks/`，
**包必须一致**，否则扫不到），并在 `checks/build.gradle.kts` 里补上它需要的依赖；
只要 `app` 依赖 `checks`，无需改 `app`。注解形式必须是 Kotlin `object`——`class` 声明没有
单例实例，扫描到会在启动期直接报错，这种情况请用方式一显式注册。

---

## 4. 三档 diff

`DiffEngine` 提供三档粒度，按代价从低到高排列，推荐「L1 不平就不必下钻 L3」：

| 档 | 方法 | 输入 | 额外内存 | 用途 |
|:--:|:---|:---|:---|:---|
| L1 | `aggregate` | `List<Row>` | O(分区数) | count / sum / checksum 粗筛，一行一分区 |
| L2 | `keySet` | `Sequence<Row>` | O(两端去重键数) | 找出只在单侧存在的主键 |
| L3 | `compareRows` | `Sequence<Row>` | O(\|src\|) | 逐主键逐列细比 |

### L1 聚合

```kotlin
val srcAgg = src query """
    SELECT '2026-09-20' AS dt, COUNT(*) AS c, SUM(amount) AS s,
           SUM(hash(order_id)) AS h
    FROM read_parquet('/data/orders/dt=2026-09-20/part-0.parquet')
"""
val tgtAgg = tgt query """
    SELECT dt, COUNT(*) AS c, SUM(amount) AS s, SUM(fnv_hash(order_id)) AS h
    FROM ods.orders WHERE dt = '2026-09-20' GROUP BY dt
"""

val aggDiffs = diff.aggregate(srcAgg, tgtAgg, keys = listOf("dt")) {
    field("s") { tolerance(abs = 0.01) }   // SUM(amount) 必须挂容忍度
    // c / h 不声明 → 默认精确相等
}
```

**`rules` 不是可选的装饰**：L1 的 `SUM(amount)` 若按 `==` 精确比，L3 允许的数值漂移会在
聚合层被误报成差异，于是每个分区都「不平」，L1 这层粗筛就失去意义。

两侧都在时比较 `s.columns ∪ t.columns` 的全部列；只有一侧有时，按 `keys` 列 emit
`FieldDiff.Missing`（整行缺失时只有主键是可比的）。

### L2 主键集合

```kotlin
val onlyOneSide = diff.keySet(
    src = src.stream("SELECT order_id FROM read_parquet('...')"),
    tgt = tgt.stream("SELECT order_id FROM ods.orders WHERE dt = '2026-09-20'"),
    key = "order_id",
)
val keys = onlyOneSide.toList()     // ⚠ 必须消费，见下
```

特性与坑：

- **两侧各只扫一遍**，键收进 `LinkedHashSet` 后做双向成员判定。
  别改成 `srcKeys - tgtKeys` 加 `tgtKeys - srcKeys`——`Sequence.minus` 会物化「右边」那一侧，
  两个方向合起来正好把两侧各读两遍（对数仓就是 4 次全表扫描）。
- **返回前两侧就被拉完**：`keySet(...)` 一调用，两个键集就建好了，即使你从不消费返回值。
- **但 `keyDiffCount` 只在消费时累加**：拿到序列不 `toList()` / 不 `forEach`，
  计数永远是 0，`summary()` 会漏掉这档差异。这是最容易踩的一个坑。
- 输出**去重、不排序**：先 src 独有、再 tgt 独有，各自保持首次出现顺序。

### L3 行级

```kotlin
diff.compareRows(
    src = src.stream("SELECT order_id, amount, status FROM read_parquet('...') LIMIT 1000"),
    tgt = tgt.stream("SELECT order_id, amount, status FROM ods.orders LIMIT 1000"),
    key = { row -> row["order_id"] ?: error("missing order_id") },
    check = { s, t -> diff.compare(s, t, rules) },
).toList()
```

- 先把 `src` 装成 `HashMap`（O(|src|) 内存），再流 `tgt`；`tgt` 可以任意大，
  `src` 必须装得下。两侧都可能很大时，先在 SQL 里采样（哈希桶 / `LIMIT` / 时间窗）。
- 整行缺失时 emit `FieldDiff.Missing("<row>", Side.SRC/TGT)`，`"<row>"` 是占位字段名。
- 重复主键保留**首次出现**。
- `srcRowCount` / `tgtRowCount` / `rowDiffCount` 在流被消费时累加。

单行比对直接用 `compare`：

```kotlin
val s = src.one("SELECT * FROM read_parquet('...') WHERE order_id = 'A1'") ?: return
val t = tgt.one("SELECT * FROM ods.orders WHERE order_id = 'A1'") ?: return

diff.compare(s, t) {
    field("amount")     { tolerance(abs = 0.01) }
    field("updated_at") { toUtc }
    field("status")     { ignore() }
}
```

### 读结果

`summary()` 把运行期计数器折叠成 `DiffSummary`：

```kotlin
val summary = diff.summary()
summary.srcCount    // 只有 compareRows 会计数；只跑 L1 时是 0
summary.diffCount   // aggDiffCount + keyDiffCount + rowDiffCount（不去重）
summary.hasDiff     // diffCount > 0
summary.level       // INFO / WARN
```

`summary()` 目前**最高只到 WARN**，且无法区分 Mismatch 与 Missing。需要 ERROR 级判定时，
从逐行的 `DiffRow` 自己折算：

```kotlin
val strict = DiffSummary.accumulate(
    srcCount = summary.srcCount,
    tgtCount = summary.tgtCount,
    rows = diffRows.asSequence(),      // DiffRow 序列，Missing → ERROR、Mismatch → WARN
)
```

---

## 5. 字段规则 FieldRules

### 两种等价写法

```kotlin
diff.compare(s, t) {
    // builder 形式
    field("amount")     { tolerance(abs = 0.01) }
    field("updated_at") { toUtc }

    // 中缀形式（等价）
    field("status") by ignore()
    field("phone")  by phoneNumber()
}
```

字段名写在 `field(...)` 的括号里。`infix` 调用是 `a f b` ≡ `a.f(b)`，省略接收者时
`field "amount"` 等价于 `field("amount")`，所以 `field "amount" by tolerance(...)` 也合法，
但字段名不能写到 `field` 左边。

> 注意 `toUtc` 是**属性**不是函数，写 `{ toUtc }` 或 `by toUtc`，不要写 `toUtc()`。

### 内置规则

| 规则 | 语义 | 用在什么字段 |
|:---|:---|:---|
| `tolerance(abs = 0.0, rel = 0.0)` | `\|s-t\| <= abs + \|t\|*rel`；任一侧非 Number 判 false | 浮点/金额类度量 |
| `ignore()` | 恒 true，完全跳过该列 | 不参与对账的列（状态、审计字段） |
| `toUtc` | 归一后的 `==`（时区归一已在 Connector 内做完） | 时间戳 |
| `custom { a, b -> ... }` | 用你自己的 lambda | 特殊语义 |
| `phoneNumber()` | 去掉非数字字符后比较 | 电话、证件号等格式可能不一致的标识 |
| `amountWithTax(rate)` | 源端不含税、目标端含税，`s * rate` 与 `t` 数值相等 | 含税/不含税对账 |

未声明规则的列默认走 `==`；一侧缺列时 emit `Missing`（`ignore()` 掩不掉 `Missing`）。

### 自定义规则

规则就是一个扩展函数，返回 `(Any?, Any?) -> Boolean`：

```kotlin
/** 大小写不敏感的字符串比较。 */
fun FieldRules.caseInsensitive(): (Any?, Any?) -> Boolean = { s, t ->
    s?.toString()?.lowercase() == t?.toString()?.lowercase()
}

/** 允许目标端在源端基础上打 1.06 的税。 */
fun FieldRules.amountWithTax(rate: BigDecimal): (Any?, Any?) -> Boolean = { s, t ->
    val a = s as? BigDecimal
    val b = t as? BigDecimal
    a != null && b != null && a.multiply(rate).compareTo(b) == 0
}
```

用 `BigDecimal` 比较时用 `compareTo` 而不是 `==`：`BigDecimal("100").multiply(BigDecimal("1.06"))`
得到 `106.00`（scale 2），与 `106.0`（scale 1）数值相等但 `equals == false`。

### 加到 L1 上

`aggregate` 收同一套规则，把浮点聚合列挂上容忍度：

```kotlin
diff.aggregate(srcAgg, tgtAgg, keys = listOf("dt")) {
    field("s") { tolerance(abs = 0.01) }   // SUM(amount)
}
```

---

## 6. 新增一个上游数据源

设计目标是**零侵入**：加一个 `Connector` 类 + 工厂一行方法，不动已有代码。

### 步骤

**1) 写 Connector**

```kotlin
package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector
import com.kxxnzstdsw.sync_diff.core.Row
import com.kxxnzstdsw.sync_diff.core.guard
import java.sql.Connection
import java.sql.DriverManager

class PostgresConnector(dsn: String, private val fetchSize: Int = 10_000) : Connector {

    private val conn: Connection = DriverManager.getConnection(dsn).apply {
        autoCommit = false            // 不关 autoCommit 会全量拉回
    }

    override fun query(sql: String): List<Row> = guard(sql) {
        conn.prepareStatement(sql).use { st ->
            st.fetchSize = fetchSize
            st.executeQuery().use { rs -> rs.toRows() }
        }
    }

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

    override fun close() = conn.close()
}
```

四条契约（`Connector.kt` 里有完整说明）：

1. 构造期建连、`close()` 释放，调用方用 `use { }` 管生命周期。
2. 失败必须抛 `ConnectorError`（用 `guard` 包），别让裸 `SQLException` 冒泡。
3. `stream` 必须是服务端游标逐行 `yield`，禁止 `query(sql).asSequence()` 这种「先全量物化
   再假装惰性」。
4. `guard` 要写在 `sequence { }` **里面**：写在外面只能包住序列对象的创建，真正的
   prepare/execute 发生在迭代时，异常会绕过它。

以上文件放在 `core` 模块：
`core/src/main/kotlin/com/kxxnzstdsw/sync_diff/connectors/PostgresConnector.kt`。

**2) 加工厂方法**

```kotlin
// core/src/main/kotlin/com/kxxnzstdsw/sync_diff/config/Config.kt
class Sources(private val cfg: AppConfig) {
    fun parquet(path: String, memoryLimit: String = "4GB") = ParquetConnector(path, memoryLimit)
    fun impala(table: String) = ImpalaConnector(cfg.impala.jdbcUrl, cfg.impala.user, cfg.impala.password)
    fun postgres(dsn: String) = PostgresConnector(dsn)      // ← 新增一行
}
```

**3) 加驱动依赖**（`core/build.gradle.kts`，不是根目录——根目录没有 build 文件）

```kotlin
implementation("org.postgresql:postgresql:42.7.4")
```

驱动装在 `core` 里，`checks` / `app` 通过 `implementation(project(":core"))` 自动带上。

**4) 处理类型归一** —— 如果驱动返回了 `ResultSetExt.coerceType` 还没覆盖的类型，
去那里加分支，而不是在 Check 里散落转换（见下）。

各源的流式参数差异大，上线前必须按 README §6 的表逐源验证，不能互相套用：

| 源 | 关键参数 | 陷阱 |
|:---|:---|:---|
| PostgreSQL | `autoCommit=false` + `fetchSize=10000` | 不关 autoCommit 会全量拉回 |
| MySQL | `useCursorFetch=true` + `setFetchSize(Integer.MIN_VALUE)` | 流式期间连接不能复用 |
| MSSQL | `responseBuffering=adaptive` | 默认全缓冲 |
| MaxCompute | ODPS JDBC + Tunnel 分批 | Tunnel 单次 1 万行限制需放开，类型映射非标准 |

### 类型归一集中在哪

`core/src/main/kotlin/com/kxxnzstdsw/sync_diff/connectors/ResultSetExt.kt` 的 `coerceType`
是**唯一**做归一的地方；对外只暴露 `Instant` / `BigDecimal` / `String` / `Number` / `null`，
这样跨源才能放心 `==`：

| 源类型 | 归一到 |
|:---|:---|
| `TIMESTAMP`（naive） | `Instant`（按 **UTC** 解释，故意不走 `Timestamp.toInstant()`，后者会吃 JVM 默认时区） |
| `TIMESTAMP_WITH_TIMEZONE` / `DATETIME` / `DATETIMEOFFSET` | `Instant` |
| `DECIMAL` / `NUMERIC` | `BigDecimal`（经字符串构造保精度） |
| 字符串 | `String` |
| `NULL` | `null` |
| 其它 | 透传 `getObject` |

新增源若引入新类型，就在这里加分支；加完补一个像 `ParquetConnectorTest` 那样的
「写入 → 读回 → 断言归一结果」的用例。

---

## 7. 报告与告警

### Markdown 报告

`report.markdown(path, summary)` 覆盖写文件、自动创建父目录、UTF-8。
输出结构见 [§1](#1-快速开始)。

汇总块只有四行（level / src rows / tgt rows / diff keys），**没有 row-level 明细**。
差异明细、字段对照、巡检结论这类自定义内容由 Check 自己追加——`markdown` 有三个重载：

```kotlin
// ① 只有汇总块（等价于原来的写法）
report.markdown("reports/order_sync_$dt.md", summary)

// ② 汇总块 + 自定义内容：块追加在汇总块之后
report.markdown("reports/order_sync_$dt.md", summary) {
    heading("差异明细（抽样）")                 // level 默认 2，即 `##`
    table(
        headers = listOf("列", "主键", "src", "tgt"),
        rows = diffs.map { listOf(it.column, it.key, it.src, it.tgt) },
        aligns = listOf(Align.LEFT, Align.LEFT, Align.RIGHT, Align.RIGHT),
    )
    paragraph("> 只列抽样命中的差异，未命中不代表一致。")
}

// ③ 完全自定义，不写汇总块（报告结构全由 Check 决定）
report.markdown("reports/inspect_$dt.md") {
    heading("巡检结果", level = 1)
    bullets(listOf("分区齐全", "part 文件数与上游一致"))
}
```

`MarkdownBuilder` 的块方法：`heading(text, level = 2)` / `paragraph(text)` /
`bullets(items)` / `table(headers, rows, aligns)` / `code(text, language = "")` /
`raw(markdown)`；块之间自动隔一个空行，`toString()` 末尾恰好一个 `\n`。
`paragraph` / `raw` 的内容**原样落笔**（可以写引用、链接、HTML），只有表格单元格会转义。

表格渲染由 `markdownTable(headers, rows, aligns)` 承担（`MarkdownBuilder.table` 就是它的
落笔形式），规则：

- 单元格 `null` 写成 `null`（不折叠成空串，好和「值是空串」区分）；其它值走 `toString()`。
- `|` 转义为 `\|`，换行折叠成 `<br>`——不处理会把表格切碎。
- `aligns` 缺省是全 `DEFAULT`；给了就必须与表头等长，行内列数不一致直接抛
  `IllegalArgumentException`（宁可当场失败，也不产出错位表格）。

需要「先拿字符串再决定写哪儿」（同一份内容既落盘又发 webhook）时，自己拼：

```kotlin
val md = MarkdownBuilder().apply {
    heading("差异明细")
    table(listOf("列", "src", "tgt"), rows)
}.toString()
```

### 内置 Wilson Check 的明细表

`wilson_apply_detail_sync` / `wilson_event_header_sync` 的报告 = 标准汇总块 + 两张抽样明细表。
数据来自 `WilsonActivityCheckBase.Result`（`columnSamples` / `pkSamples`），**与汇总同源**：
判定用的就是这一批样本，不是为写报告再查一遍库，所以表格行数与 `summary.diffCount` 必定对得上。

| 小节 | 一行是什么 | 列 |
|:---|:---|:---|
| `## 单字段抽样（N 列，差异 M 列）` | `columns` 每一列（跳过主键列）取一条**上游非空样本**，再按主键反查下游同一行 | 列 / 主键 / src / tgt / 结论 |
| `## 主键随机抽样（N 条，下游缺失 M 条）` | 本次抽到的每条上游主键（`ORDER BY hash(pk) LIMIT sampleSize`，默认 100） | 主键 / src / tgt / 结论 |

结论只有四种：`一致` / `值不同` / `下游缺该行` / `上游该列全空`（上游整列没有非空值时，主键一栏写 `-`）。
`sampleSize`（默认 100，实例字段）控制主键抽样条数：上游不足 100 行就抽多少写多少；上游空表时
该小节整段不出现。单元格里的 `null` 是真实空值，和空串不是一回事。

真实报告节选（`wilson_apply_detail_sync`，10 行 fixture、`code` 列故意漂移）：

```markdown
## 单字段抽样（48 列，差异 1 列）

| 列 | 主键 | src | tgt | 结论 |
|---|---|---|---|---|
| code | 1 | code-1 | TGT-CODE | 值不同 |
| status_name | 1 | status_name-1 | status_name-1 | 一致 |

## 主键随机抽样（10 条，下游缺失 0 条）

| 主键 | src | tgt | 结论 |
|:---|:---:|:---:|:---|
| 1 | 有 | 有 | 一致 |
| 2 | 有 | 有 | 一致 |
```

细节：整张表按上游样本的主键取值排序无关紧要（抽样本身是 `hash` 伪随机），要 `ORDER BY` 的话
在 `WilsonActivityCheckBase` 的 SQL 模板里改；报告文件名是 `reports/<reportFilename>_<dt>.md`，
`reportFilename` 现在对子类公开，外部也能算出落盘位置。

### Webhook

`report.webhook(url, summary)` 以 `POST` + `Content-Type: application/json` 发出：

```json
{"level":"WARN","srcCount":5,"tgtCount":4,"diffCount":3,"hasDiff":true}
```

行为要点：

- `url` 为 `null` / 空白：no-op。典型用法是接到 `ALERT_URL` 上，没配就不发。
- 连接超时 5s、读超时 10s。
- **任何 IO 异常都被吞掉，方法不抛**。设计意图是对账结果已经落报告，告警失败不该带崩
  对账。代价是投递失败不会报错——如果告警可靠性重要，在消费端（接收方）做去重与补发，
  或在你的 Check 里自行调用 `Reporter` 之外的告警客户端。
- 非 2xx 响应内部会抛 `IOException`，随后同样被吞掉。

```kotlin
report.markdown("reports/order_sync_$dt.md", summary)
if (alertUrl.isNotEmpty()) report.webhook(alertUrl, summary)
```

接收方按普通 JSON 解析即可。飞书 / 钉钉 / Slack 的入站 webhook 各自要求特定字段结构，
本 payload 不是它们开的格式——需要对接时请用对应的 bot 接口，或加一层转发服务，
不要指望直接填对方的 webhook 地址。

---

## 8. 配置与环境变量

### 变量表

| 变量 | 作用 | 用在 |
|:---|:---|:---|
| `IMPALA_URL` | 下游 Impala JDBC URL（CLI `--impala-url` 的同名 env） | CLI |
| `IMPALA_JDBC_URL` | 同上，但优先级低于 `--impala-url` | `GlobalConfig.loadFromEnv()` |
| `IMPALA_USER` | Impala 用户名（LDAP） | `GlobalConfig.loadFromEnv()` |
| `IMPALA_PASSWORD` | Impala 口令 | `GlobalConfig.loadFromEnv()` |
| `ALERT_URL` | 告警 webhook（CLI `--alert-url` 的同名 env） | CLI |
| `ORDERS_PARQUET_PATH` | 内置 `order_sync` 的源端 parquet 路径 | `OrderSyncCheck` |
| `ORDERS_TGT_PARQUET_PATH` | 内置 `order_sync` 的目标端 parquet 路径 | `OrderSyncCheck` |
| `WILSON_APPLY_DETAIL_PARQUET_PATH` | 内置 `wilson_apply_detail_sync` 的源端 parquet 路径 | `WilsonActivityApplyDetailCheck` |
| `WILSON_APPLY_DETAIL_TGT_TABLE` | 内置 `wilson_apply_detail_sync` 的下游 Impala 表名；未设则 `dwd.fact_channel_wilson_activity_apply_detail` | `WilsonActivityApplyDetailCheck` |
| `WILSON_EVENT_HEADER_PARQUET_PATH` | 内置 `wilson_event_header_sync` 的源端 parquet 路径 | `WilsonActivityEventHeaderCheck` |
| `WILSON_EVENT_HEADER_TGT_TABLE` | 内置 `wilson_event_header_sync` 的下游 Impala 表名；未设则 `dwd.fact_channel_wilson_activity_event_header` | `WilsonActivityEventHeaderCheck` |

优先级：显式参数（命令行）> 同名环境变量 > 代码里的当前值。

> CLI 路径上 `--impala-url` 有非空默认值，所以 `IMPALA_JDBC_URL` 在 CLI 里不会是兜底；
> 换地址请用 `--impala-url` 或 `IMPALA_URL`。

### 代码里改配置

```kotlin
GlobalConfig.loadFromEnv()                                  // 只读 env
GlobalConfig.loadFromEnv(impalaUrl = "jdbc:impala://x:21050")  // 显式覆盖
GlobalConfig.configure(AppConfig(ImpalaConfig("jdbc:impala://y:21050", "u", "p")))  // 测试直接替换
```

`GlobalConfig.current` 的 setter 是 `private`，运行期改配置走 `configure` / `loadFromEnv`。

Impala JDBC URL 形态与三种认证：

| 模式 | URL 关键参数 | 凭据 |
|:---|:---|:---|
| 无认证 | `AuthMech=0` | 不需要 |
| LDAP | `AuthMech=3` | 可写进 URL，或走 `user` / `password` |
| Kerberos | `AuthMech=1`, `KrbRealm`, `KrbHostFQDN`, `KrbServiceName` | 票据由 JVM 从 ticket cache 取，`user` / `password` 留空 |

---

## 9. 调度集成

CLI 是幂等的一次性进程，退出码即成败信号（§2）。

### Airflow

`/opt/sync_diff/sync_diff-all.jar` 是部署路径，来源是本地产出的
`app/build/libs/sync_diff-all.jar`（§1）。

```python
run_diff = BashOperator(
    task_id="order_sync_diff",
    bash_command=(
        "java -jar /opt/sync_diff/sync_diff-all.jar "
        "--check order_sync "
        "--dt {{ ds }} "
        "--registry /opt/sync_diff/conf/checks.txt"
    ),
    env={
        "IMPALA_URL": "jdbc:impala://impala-prod:21050/default;AuthMech=0",
        "ORDERS_PARQUET_PATH": "/data/orders/dt={{ ds }}/part-0.parquet",
        "ORDERS_TGT_PARQUET_PATH": "/data/orders_tgt/dt={{ ds }}/part-0.parquet",
        "ALERT_URL": "{{ var.value.diff_alert_url }}",
    },
    retries=1,
)
```

### DolphinScheduler

用 Shell 任务，脚本同上；把 JDBC/路径放工作流的自定义参数里，避免写死在脚本内。

> 因为 `hasDiff = true` 不影响退出码，想让「有差异」触发下游告警节点的话，用报告的产物
> （文件 / webhook）做分支，而不是靠任务失败。

---

## 10. 本地自测

### 造 parquet 测试数据

fat jar 里已经打包了 DuckDB，直接用 JDK 的单文件源码模式跑即可，不需要额外装工具：

```java
// /tmp/mkparquet.java
import java.sql.*;

public class mkparquet {
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t(order_id BIGINT, amount DECIMAL(18,3), status VARCHAR, updated_at TIMESTAMP);");
            s.execute("INSERT INTO t VALUES "
                + "(1,100.000,'PAID',TIMESTAMP '2026-09-20 01:00:00'),"
                + "(2,100.000,'PAID',TIMESTAMP '2026-09-20 01:01:00'),"
                + "(3,100.000,'PAID',TIMESTAMP '2026-09-20 01:02:00');");
            s.execute("COPY t TO '/tmp/e2e_src.parquet' (FORMAT PARQUET);");

            s.execute("DELETE FROM t WHERE order_id = 3;");        // 目标端缺一行
            s.execute("UPDATE t SET amount = 105.000 WHERE order_id = 2;");  // 目标端金额飘了
            s.execute("COPY t TO '/tmp/e2e_tgt.parquet' (FORMAT PARQUET);");
        }
    }
}
```

```bash
java -cp app/build/libs/sync_diff-all.jar /tmp/mkparquet.java

ORDERS_PARQUET_PATH=/tmp/e2e_src.parquet \
ORDERS_TGT_PARQUET_PATH=/tmp/e2e_tgt.parquet \
  java -jar app/build/libs/sync_diff-all.jar --check order_sync --dt 2026-09-20
```

### 跑测试

```bash
./gradlew test                 # 全部模块
./gradlew :core:test           # 只跑内核
./gradlew :checks:test         # 只跑 Check 层
```

用例分布（共 78 个）：

| 模块 | 用例文件 | 覆盖 |
|:---|:---|:---|
| `core`（58） | `core/src/test/kotlin/.../core/CoreTypesTest.kt` | Row 扩展、FieldRules 两种写法、DiffSummary |
| | `.../engine/DiffEngineTest.kt` | 三档 diff、计数器、`keySet` 去重与扫描次数 |
| | `.../connectors/ParquetConnectorTest.kt` | DuckDB 读写、类型归一、`guard` 契约 |
| | `.../connectors/ImpalaConnectorTest.kt` | 接口形状、连不上时 fail fast |
| | `.../reporter/ReporterTest.kt` | Markdown / JSON / webhook |
| `checks`（20） | `checks/src/test/java/.../check/CheckTest.kt` | Args 注入、工厂 |
| | `.../check/CheckRegistryTest.kt` | 注册、查重、`discover` |
| | `.../checks/OrderSyncCheckEndToEndTest.kt` | 端到端（DuckDB 造数 → 跑 Check → 断言 Summary） |
| `app`（0） | — | CLI 目前靠手工冒烟（见 §2） |

注意两个模块的测试目录约定不同：`core` 用 `src/test/kotlin`，`checks` 用 `src/test/java`。

---

## 11. 故障排查

| 现象 | 原因 | 处理 |
|:---|:---|:---|
| `ConnectorError$QueryFailed: ... No files found that match the pattern` | SQL 里的 `read_parquet('...')` 路径不存在 | 检查 `ORDERS_PARQUET_PATH` 等路径与分区日期 |
| `Unknown check: xxx. Available: order_sync, wilson_apply_detail_sync, wilson_event_header_sync` | `--check` 名字不对，或清单文件没加载到 | 名字区分大小写；确认 `--registry` 指向的文件存在且有有效行 |
| `Registry entry '...' cannot be loaded` | 清单里的 FQCN 不存在 / 不是 `object` / 没实现 `Check` | 用全限定名，且声明为 `object` |
| 报告写出来了，但 `summary.diffCount` 永远是 0 | L2 序列拿到了没消费 | `keySet(...).toList()`，见 §4 |
| L1 每个分区都报差异，但 L3 查不出问题 | `aggregate` 没给浮点聚合列挂容忍度 | 传 `rules`，把 `SUM(...)` 列绑上 `tolerance` |
| 时间列明明同值却判不等 | 归一没生效，两侧类型不同 | 确认走 `ResultSetExt` 的归一；naive `TIMESTAMP` 按 UTC 解释 |
| `BigDecimal` 数值相等却判不等 | 用了 `==` 比 `BigDecimal`（连 scale 一起比） | 自定义规则里用 `compareTo(...) == 0` |
| Impala 查询把内存吃爆 | `autoCommit` 没关或没设 `fetchSize` | 两个都要有，见 §6 |
| 构建报 `Duplicate entries found in the shadowed JAR` | 新依赖与已有依赖打包了同一份类 | 排除重复来源；必要时用 `append(...)` 合并法务元数据 |
| `Unresolved reference` 满屏，来自某一个文件 | 该文件注释里有斜杠紧跟星号（Kotlin 块注释会嵌套，把 `*/` 吃掉了） | 不要在注释里写 glob 路径原文，改写为具体文件名 |
| `Gradle requires JVM 17 or later to run` | 构建 JVM 是 JDK 8 | `export JAVA_HOME=/path/to/jdk-17`，或在 `gradle.properties` 设 `org.gradle.java.home` |
| `找不到或无法加载主类 com.kxxnzstdsw.sync_diff.MainKt` | `Main.kt` 的 `package` 与 `app/build.gradle.kts` 里 `Main-Class` 不一致（Kotlin 按 package 生成类名，不看目录） | 两处包名对齐；当前都是 `com.kxxnzstdsw.sync_diff` |
| `java -jar build/libs/...` 报文件不存在 | fat jar 在 `app` 模块 | 用 `app/build/libs/sync_diff-all.jar` |
| 自己写的 Check 编译不过 / CLI 里找不到 | Check 放错模块或缺依赖 | Check 放 `checks` 模块，依赖加在 `checks/build.gradle.kts`；再按 §3.4 注册 |
| 改了 `core` 但 `checks` 没生效 | 依赖方向是 `app → checks → core` | 确认 `checks`/`app` 有 `implementation(project(":core"))`；改完跑 `./gradlew build` |

---

## 12. 已知边界

踩之前先知道：

1. **`sources.impala(table)` / `targets.impala(table)` 的 `table` 参数当前未被使用。**
   `Connector.query/stream` 收完整 SQL，表名必须由调用方写全限定。该参数只为对齐
   README §8 的工厂签名而保留，改动它会破坏既有 Check。
2. **`summary()` 最高只到 WARN**，无法区分 Mismatch 与 Missing；需要 ERROR 档请自行用
   `DiffSummary.accumulate` 折算。
3. **`ImpalaConnector` 没有对真实 Impala 的集成测试**（无可用实例）。连接参数与游标设置
   按 README §6 实现，上线前请用真实 Impala 回归一次。
4. **`OrderSyncCheck` 的 L1 checksum 用 DuckDB 的 `hash()`**，是方言函数；目标端换成
   Impala 时要改成 `fnv_hash()` 之类的等价函数，否则 checksum 列必然不平。
5. **`OrderSyncCheck` 阶段 1 默认两端都走 Parquet**，便于零依赖自测；生产要把下游换成
   Impala，见 `defaultConnectors()` 或注入 `injected`。
6. **SQL 是字符串拼接的**（示例级别）。生产的动态值必须参数化或经白名单校验，否则有注入
   风险——README §15 已列为中风险项。
7. **`DiffEngine` 不是线程安全的**：一个 Check 一次执行一个实例。要在同一个 Check 里并发
   跑多档 diff，用 `Ctx.diff` 之外自己 new 的实例。
