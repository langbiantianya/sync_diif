# sync_diff 使用手册

面向「要拿它做对账」的人。设计动机与分阶段路线见 [README](../README.md)；本文只讲怎么用、
怎么扩展、以及会踩到什么坑。

- [1. 快速开始](#1-快速开始)
- [2. 命令行](#2-命令行)
- [3. 写自己的 Check](#3-写自己的-check)
- [4. 三档 diff 与自定义档](#4-三档-diff-与自定义档)
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
| `core` | 对账内核：`Row` / `DiffEngine` / `Connector` / `FieldRules` / `Reporter` | `core/src/main/kotlin` |
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

`shadowJar` 现在关掉了重复条目的严格失败（`failOnDuplicateEntries = false`）：同名 class 在多个
transitive 里各带一份（kotlin-stdlib、lz4-java…）内容一致，运行时不存在冲突；法务元数据
（`META-INF/LICENSE|NOTICE|DEPENDENCIES`）用 `append(...)` 追加合并而非丢弃。

同时 jar 里**只保留 linux/amd64 的原生库**（DuckDB 的其他平台 `libduckdb_java.so` 与各平台
zstd/snappy 加起来约 480 MB 未压缩）；要在 macOS / arm64 上跑这个 jar，去
`app/build.gradle.kts` 的 `exclude(...)` 里删掉对应那一行再构建。

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

成功退出码 `0`，并在 `reports/order_sync_2026-09-20.xlsx` 落一份 Excel 报告。
下面是按 §10 那份 fixture（源端 3 行；目标端缺 1 行 + 1 行金额漂移）跑出来的内容：
sheet 名与首行标题都来自 `heading("Diff Summary")`（同源），A 列是项、B 列是值。

| A | B | 落成 |
|:---|:---|:---|
| Diff Summary | | 加粗 13pt 标题，同时是该 sheet 的名字 |
| metric | value | 加粗 + 灰底表头 |
| level | WARN | `DiffLevel` 的 name（INFO / WARN / ERROR），文本单元格 |
| src rows | 3 | 数字单元格 |
| tgt rows | 2 | 数字单元格 |
| diff keys | 3 | 数字单元格 |
| hasDiff = true | | 一段文本；无差异时写 `hasDiff = false` |
| Top diff keys are reported in the linked L3 detail file. | | 仅 `hasDiff == true` 时追加 |

`Diff Summary` 表只有这几行，没有 row-level 明细；差异明细、字段对照这类内容由 Check 自己
追加（每个 `heading` 开一张 sheet），见 [§7](#7-报告与告警)。

3 条差异分别是：L1 分区（count 3 ≠ 2）、L3 缺行（order_id=3）、L3 金额超容（order_id=2）。

---

## 2. 命令行

### 选项

| 选项 | 必填 | 默认值 | 环境变量 | 说明 |
|:---|:---:|:---|:---|:---|
| `--check` | ✅ | — | — | 要跑的 Check 名（区分大小写），与 `Check.name` 对齐 |
| `--dt` | | 不传则用 `Check.Args.dt`（今天） | — | 上游分区日，装进 `Check.Args.dt`；显式传入才覆盖 |
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

# 只靠环境变量：连接信息走各 Check 自己读的 env（如 ImpalaConfig.fromEnv 读
# IMPALA_JDBC_URL / IMPALA_USER / IMPALA_PASSWORD），不再走 CLI 的 --impala-url；
# CLI 这一层只负责 --check / --dt / --alert-url。
IMPALA_JDBC_URL='jdbc:hive2://impala-prod:21050/default' ALERT_URL="$ALERT_URL" \
  java -jar app/build/libs/sync_diff-all.jar --check order_sync --dt 2026-09-20
```

### 退出码与失败行为

| 情形 | 行为 | 退出码 |
|:---|:---|:---:|
| 正常跑完（有无差异都算正常） | 落报告，返回 | `0` |
| 选项缺失 / 非法 | Clikt 打印 `Error: missing option --check` + 用法 | `1` |
| `--check` 名字不存在 | `Error: Unknown check: xxx. Available: order_sync`（可用名字以实际注册结果为准） | `1` |
| 清单文件里某行反射不出来 | `Error: Registry entry '...' cannot be loaded: ...` | `1` |
| 清单文件不存在 / 无有效行 | **不是错误**：fallback 到内置 Check | 照常跑 |
| 对账执行期失败（文件不存在 / SQL 错 / 连不上库） | 抛 `ConnectorError`，未捕获，打印堆栈 | `1` |

**启动期**错误（选项、Check 名、清单文件）统一由 Clikt 渲染成一行 `Error: ...`，不带 JVM
堆栈——调度日志里一眼看懂：

```text
$ java -jar app/build/libs/sync_diff-all.jar --check nope
Unknown check: nope. Available: order_sync   # 实际带 Error: 前缀
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
import com.kxxnzstdsw.sync_diff.connectors.ImpalaConnector
import com.kxxnzstdsw.sync_diff.connectors.ParquetConnector

object UserSyncCheck : CheckBase("user_sync") {
    override suspend fun Ctx.run() {
        val dt = args.dt
        val src = ParquetConnector("/data/users/dt=$dt/part-0.parquet")        // 上游 Connector
        val tgt = ImpalaConnector(ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT)) // 下游 Connector

        val srcAgg = src.query(
            "SELECT COUNT(*) AS c FROM read_parquet('/data/users/dt=$dt/part-0.parquet')"
        )
        val tgtAgg = tgt.query("SELECT COUNT(*) AS c FROM ods.users WHERE dt = '$dt'")

        diff.aggregate(srcAgg, tgtAgg, keys = listOf("dt"))
        report.excel("reports/user_sync_$dt.xlsx", diff.summary())
    }
}
```

`Ctx` 里能直接用的东西：

| 名字 | 来源 | 说明 |
|:---|:---|:---|
| `args` | `Check.Args`，由 `runWith` 注入 | CLI 从 `--dt` 造出来；`args.copy(dt = ...)` 派生新参数 |
| `diff` | 本次执行的 `DiffEngine` | **每次执行新建**，所以并发跑同一 Check 不会共享计数器 |
| `report` | 本次执行的 `Reporter` | Excel（`heading` / `paragraph` / `bullets` / `table`）+ Webhook |

**上游 / 下游连接信息由 Check 自己负责**——典型做法是给 Check 加一个
`@Volatile var myCfg = ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT)`，调度脚本改 env 切集群、
测试改字段都行。框架没有"全局默认下游"。

上下游 Connector 都直接 new，不走工厂封装——`ParquetConnector` / `ImpalaConnector` 就是
同一套 `Connector` 接口的两个实现，Check 想在两侧用哪个就 new 哪个。

`Ctx` 是**每次执行一个实例**（不是 `object`）。`DiffEngine` 带可变计数器，做成单例会让并发
执行互相污染；`Reporter` 本身无状态可复用，但同样跟着 `Ctx` 每次 new 一个，所以
`Check.runWith` 每次都 new 一份。

### 3.2 参数的取用

```kotlin
data class Args(val dt: String = LocalDate.now() /* 今天 */, val params: Map<String, String> = emptyMap())

val dt = args.dt
val table = args.params["tgt_table"] ?: "ods.orders"     // 取不到就是没配
val yesterday = args.copy(dt = "2026-09-19")             // 派生，不改原值
```

`params` 是自由字典，适合放表名 / 库名 / 白名单这类不该散在代码里的东西。

### 3.3 接 Impala（阶段 2+ 的上游也一样）

```kotlin
@Volatile var impalaCfg: ImpalaConfig = ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT)

override suspend fun Ctx.run() {
    val src = ParquetConnector("/data/orders/dt=${args.dt}/part-0.parquet")
    val tgt = ImpalaConnector(impalaCfg)             // 生产：连 Impala

    // 两侧 SQL 各写自己的方言，Connector 负责把结果归一成 Row
}
```

`Connector.query/stream/one` 收的是**完整 SQL**，表名必须自己写全限定；Connector 构造参数
（如 `ParquetConnector` 的 `path`）只用于判断是否加载 `httpfs` / 配 DuckDB 内存上限，
**真正读哪些文件由 SQL 里的 `read_parquet('...')` 决定**。

下游也能直接读 Parquet（同一套 `ParquetConnector`，与上游对称）——CI / 自测场景：

```kotlin
val tgt = ParquetConnector("/tmp/tgt.parquet")         // 自测：直接读本地 parquet
```

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

## 4. 三档 diff 与自定义档

`DiffEngine` 提供三档粒度，外加一档「自己定口径」的自定义档；按代价从低到高排列，
推荐「L1 不平就不必下钻 L3」：

| 档 | 方法 | 输入 | 额外内存 | 用途 |
|:--:|:---|:---|:---|:---|
| L1 | `aggregate` | `List<Row>` | O(分区数) | count / sum / checksum 粗筛，一行一分区 |
| L2 | `keySet` | `Sequence<Row>` | O(两端去重键数) | 找出只在单侧存在的主键 |
| L3 | `compareRows` | `Sequence<Row>` | O(\|src\|) | 逐主键逐列细比 |
| 自定义 | `reconcile` | `Sequence<Row>` | O(\|src\|) | 自己定「怎么算对得上」：多主键 / 归一键 / 自定义差异结构 |

L1 / L3 / 自定义档建在同一个对齐内核 `align`（按 key 的全外连接）上，对齐、缺行、重复键
的语义只有一份（见下面「内核约定」）；L2 是刻意例外（只需要主键、不需要行，省一个内存档次）。

### L1 聚合

```kotlin
val srcAgg = src.query("""
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

行序与重复键走内核：**先 tgt 流序（命中与 tgt 独有交错）、再 src 独有**；同一主键 src 侧
保留首次出现、tgt 侧逐条 emit（L1 的输入是 `GROUP BY` 结果，正常一个分区一行），见「内核约定」。

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
- **主键为 NULL / 缺列会直接失败**（`IllegalStateException`），不会把那一行悄悄跳过：NULL 主键
  无法对齐，静默丢弃会让「上游多了一堆主键为 NULL 的脏行」看起来像「两端一致」。要容忍它就在
  SQL 里写 `WHERE key IS NOT NULL`，或 `COALESCE(key, '<null>')` 显式归一成可比的值。

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
- 重复主键保留**首次出现**（tgt 侧不去重）、行序先 tgt 后 src 独有——这些是内核 `align` 的
  约定，见「内核约定」。
- 抽样下钻时 `LIMIT` 一定配 `ORDER BY`：只写 `LIMIT` 的话取哪几行由扫描顺序决定
  （多 part / 并行扫描下不稳定），两次运行会样到不同子集，报告结论不可回归。
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

### 自定义档（reconcile）

内置三档不够用时（多主键拼接、键要先归一、差异要写成自己的结构），不必改 `core`：
对齐内核 `align` 是公开的，自定义档就是往里传一套 lambda。

| lambda | 决定 |
|:---|:---|
| `key: (Row) -> K` | 怎么取对齐键。多列拼接、归一化（大小写 / 空格 / 前缀）都行，只要 `equals`/`hashCode` 是你要的语义 |
| `compare: (Row, Row) -> V` | 两侧都有时产出什么。`V` 由你定义：自定义差异行、`Boolean`、一段文本都行 |
| `onMissing: (K, Side) -> V` | 只有一侧时产出什么；`Side` 指向**缺的那一侧** |
| `isDiff: (V) -> Boolean` | 产出物算不算一次差异。**必须显式给**：引擎不猜「缺行算不算差异」 |

```kotlin
/** 自定义差异行：(dt, order_id) 组合键，只比金额。 */
data class AmountDiff(val key: String, val detail: String, val isDiff: Boolean)

val diffs = diff.reconcile(
    src = src.stream("SELECT dt, order_id, amount FROM read_parquet('...')"),
    tgt = tgt.stream("SELECT dt, order_id, amount FROM ods.orders WHERE dt = '2026-09-20'"),
    key = { row -> "${row["dt"]}/${row["order_id"]}" },        // 组合键，一行拼出来
    onMissing = { k, side -> AmountDiff(k, "missing on $side", true) },
    compare = { s, t ->
        val sv = s["amount"] as BigDecimal
        val tv = t["amount"] as BigDecimal
        AmountDiff("${s["dt"]}/${s["order_id"]}", "amount $sv vs $tv", sv.compareTo(tv) != 0)
    },
    isDiff = { it.isDiff },
).toList()      // ⚠ 必须消费，计数器才生效

diff.summary()  // diffs 里的差异已经并进 diffCount / hasDiff
```

要点：

- **对齐语义与内置档完全一致**（顺序、缺行、重复键、内存、惰性）：它们本来就是同一个
  `align`，见下面「内核约定」。
- **计数**：`src` / `tgt` 流过的每一行进 `srcRowCount` / `tgtRowCount`，每个 `isDiff` 为 true
  的产出进 `customDiffCount`，并进 `summary().diffCount`——与内置档一样，**在流被消费时**累加。
- **只看不计数**用 `align`：同一个内核，不碰任何计数器，也不预设「差异」这个概念，
  可以用来做任何"按 key 把两侧对齐"的事。

### 内核约定（align / compareRows / reconcile 共用）

- **产出顺序**：先按 `tgt` 的流序（命中的与 tgt 独有的交错），再按 src 的首次出现顺序输出
  src 独有的键。`aggregate` / `compareRows` / `reconcile` 的行序都是它——这个顺序让 `tgt`
  单向流过、不落堆，只有 `src` 进 `HashMap`。
- **缺行**：`Side` 指向**缺的那一侧**——tgt 独有 → `Side.SRC`，src 独有 → `Side.TGT`。
- **重复键**：`src` 侧保留**首次出现**，后面的同键行丢弃；`tgt` 侧重复键**各自产出**
  （同一个键会出现多次），要不要去重由调用方决定。
- **惰性**：不消费序列就不读数据、不累加计数器（`align(...)` 这一句本身不读任何一侧）。

### 读结果

`summary()` 把运行期计数器折叠成 `DiffSummary`：

```kotlin
val summary = diff.summary()
summary.srcCount    // 只有流式档（compareRows / reconcile）会累加；只跑 L1 时是 0
summary.diffCount   // aggDiffCount + keyDiffCount + rowDiffCount + customDiffCount（不去重）
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

设计目标是**零侵入**：加一个实现 `Connector` 的类，Check 里直接 `new` 它，不动已有代码。

### 步骤

**1) 写 Connector**

先判断属于哪一类，绝大多数新源都是前两类——只写建连，取数逻辑一行都不用抄：

**A. JDBC 源**（PG / Oracle / Trino / …）：继承 `JdbcConnector`，只提供连接与方言：

```kotlin
package com.kxxnzstdsw.sync_diff.connectors

private const val ORACLE_DRIVER = "oracle.jdbc.OracleDriver"

class OracleConnector(
    jdbcUrl: String,
    user: String? = null,
    password: String? = null,
    fetchSize: Int = JdbcConnector.DEFAULT_FETCH_SIZE,
) : JdbcConnector(
    // jdbcConnection 会先 Class.forName(驱动)，不依赖 fat jar 里被合并的 service 文件
    jdbcConnection(ORACLE_DRIVER, jdbcUrl, user, password).also { it.autoCommit = false },
    fetchSize,
)
```

`query` / `stream`（服务端游标逐行 `yield`）/ `one`（`LIMIT 1`）/ `close` / 失败包装
（`ConnectorError.QueryFailed`）全部来自基类。方言差异只覆写对应钩子，例如 SQL Server 那样
没有 `LIMIT` 的方言：
`override fun oneSql(sql: String) = mssqlTopOne(sql)`。

**B. DuckDB 能直接读的文件源**（Parquet / CSV / Excel / JSONL / 对象存储）：继承
`DuckDbSessionConnector`，在 super 调用里声明要加载的扩展
（`httpfsFor(path)`、`listOf("excel")`），取数同样一行不写。

```kotlin
class MyFileConnector(
    private val path: String,
    memoryLimit: String = "4GB",
    tempDir: String = "/tmp/duckdb_spill",
) : DuckDbSessionConnector(memoryLimit, tempDir, httpfsFor(path))
```

**C. 都不是**（REST / 消息队列 / 自研 SDK）：自己实现 `Connector` 的四个方法，遵守下面四条契约：

```kotlin
class MyApiConnector(private val baseUrl: String) : Connector {
    override fun query(sql: String): List<Row> = guard(sql) { /* 小结果集：一次拉完 → List<Row> */ }
    override fun stream(sql: String): Sequence<Row> = sequence { guard(sql) { /* 游标逐条 yield */ } }
    override fun one(sql: String): Row? = guard(sql) { /* 单条，取不到返回 null */ }
    override fun close() = Unit
}
```

1. 构造期建连、`close()` 释放，调用方用 `use { }` 管生命周期。
2. 失败必须抛 `ConnectorError`（用 `guard` 包），别让裸 `SQLException` 冒泡。
3. `stream` 必须是服务端游标逐行 `yield`，禁止 `query(sql).asSequence()` 这种「先全量物化
   再假装惰性」。
4. `guard` 要写在 `sequence { }` **里面**：写在外面只能包住序列对象的创建，真正的
   prepare/execute 发生在迭代时，异常会绕过它。

以上文件放在 `core` 模块：
`core/src/main/kotlin/com/kxxnzstdsw/sync_diff/connectors/`。

**2) 直接 new 它**（没有工厂层要改）

```kotlin
override suspend fun Ctx.run() {
    val src = PgConnector(PgConfig("jdbc:postgresql://host:5432/orders"))  // ← 新增一个 Connector
    val tgt = ImpalaConnector(ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT))
    // ...
}
```

不需要往任何工厂类里加方法：`Connector` 是唯一抽象，Check 想用哪个源就 new 哪个。
上游、下游、同一条 Check 里多个源，全都是同一套写法。

**3) 加驱动依赖**（`core/build.gradle.kts`，不是根目录——根目录没有 build 文件）

```kotlin
runtimeOnly("org.postgresql:postgresql:42.7.13")   // 与现有驱动一致写 runtimeOnly
```

驱动装在 `core` 里，`checks` / `app` 通过 `implementation(project(":core"))` 自动带上。

连接器里**显式 `Class.forName(驱动类)`**（`jdbcConnection(...)` 已经这么做了）：fat jar 里
`META-INF/services/java.sql.Driver` 被合并成一份，一旦其中某个驱动类加载不了（例如旧版本
hive-jdbc 的 Java 21 字节码跑在 JDK 17 上），扫描会在那里中断，排在它后面的驱动全部失效。

**4) 处理类型归一** —— 如果驱动返回了 `ResultSetExt.coerceType` 还没覆盖的类型，
去那里加分支，而不是在 Check 里散落转换（见下）。

各源的流式参数差异大，上线前必须按 README §6 的表逐源验证，不能互相套用：

| 源 | 关键参数 | 陷阱 |
|:---|:---|:---|
| PostgreSQL | `autoCommit=false` + `fetchSize=10000` | 不关 autoCommit 会全量拉回 |
| MySQL | `autoCommit=false` + `fetchSize=10000` + URL 里 `useCursorFetch=true` | 少 `useCursorFetch=true` 时 `fetchSize` 不生效，驱动全量拉回 |
| MSSQL | `autoCommit=false` + `fetchSize=10000`；`one()` 走 `SELECT TOP 1` | 默认全缓冲 |
| MaxCompute | ODPS JDBC 直连（不支持显式事务，故不设 `autoCommit=false`） | 类型映射非标准 |

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

### Excel 报告

`report.excel(path, summary)` 覆盖写文件、自动创建父目录；文件是 `.xlsx`（OOXML），
IO 失败抛异常。工作簿内容用 `ExcelBuilder` 的 DSL 拼装，标准汇总块固定落在名为
`Diff Summary` 的 sheet 上（sheet 名与首行标题同源），A 列是项、B 列是值：

| A | B | 落成 |
|:---|:---|:---|
| Diff Summary | | 加粗 13pt 标题，同时是该 sheet 的名字 |
| metric | value | 加粗 + 灰底表头 |
| level | INFO / WARN / ERROR | `DiffLevel` 的 name，文本单元格 |
| src rows | 数字 | 数字单元格 |
| tgt rows | 数字 | 数字单元格 |
| diff keys | 数字 | 数字单元格 |
| hasDiff = true | | 一段文本；无差异时写 `hasDiff = false` |
| Top diff keys are reported in the linked L3 detail file. | | 仅 `hasDiff == true` 时追加 |

`Diff Summary` 表本身**没有 row-level 明细**。差异明细、字段对照、巡检结论这类自定义内容由
Check 自己追加——`excel` 有三个重载：

```kotlin
// ① 只有标准汇总块
report.excel("reports/order_sync_$dt.xlsx", summary)

// ② 汇总块 + Check 追加的自定义内容（每个 heading 一张 sheet）
report.excel("reports/order_sync_$dt.xlsx", summary) {
    heading("差异明细（抽样）")
    table(
        headers = listOf("列", "主键", "src", "tgt"),
        rows = diffs.map { listOf(it.column, it.key, it.src, it.tgt) },
    )
    paragraph("只列抽样命中的差异，未命中不代表一致。")
}

// ③ 完全自定义，不写汇总块（报告长什么样全由 Check 决定）
report.excel("reports/inspect_$dt.xlsx") {
    heading("巡检结果")
    bullets(listOf("分区齐全", "part 文件数与上游一致"))
}
```

`ExcelBuilder` 只有四个落笔入口，都返回 `this` 可链式调用：

- `heading(text)`：**开一张新 sheet**，`text` 同时当 sheet 名与该 sheet 首行的加粗标题。
  下一个 `heading` 再开一张；标题之前的内容落在默认 sheet 上，第一个 `heading` 会给这张
  还没写过内容的默认 sheet 改名——所以「完全自定义」的报告不会留下空的 `Report` sheet。
- `paragraph(text)`：一行说明文字。
- `bullets(items: Iterable<String>)`：每条一行，行首加 `• `；空集合不产生任何行。
- `table(headers: List<String>, rows: Iterable<Iterable<Any?>>)`：一行加粗表头 + 每个数据行一行。

`heading` 的 sheet 名会做清洗：`\ / * ? : [ ]` 换成空格，去掉首尾空格与单引号，清洗后为空
则用 `Report`，与 Excel 保留名 `History` 同名（忽略大小写）时补下划线 `History_`，按码点
截到 31 个字符（不切断代理对），重名（忽略大小写）自动加后缀 ` (2)`、` (3)`……

`table` 的参数校验走 fail fast（`IllegalArgumentException`），不静默产出错位表格：
`headers` 不能为空，每一行的列数必须等于表头列数。`excel(path){}` 传空 lambda 不报错，
会落一张名为 `Report` 的空 sheet。

单元格取值规则（`table` / `paragraph` / `bullets` 共用）：

- `null` → 文本 `null`（**不是空单元格**；对账报告里「没有值」与「值是空串」必须能区分）。
- `Number` → 数字单元格（可在 Excel 里直接求和 / 排序）；`Boolean` → 布尔单元格；
  其余（含 `String`）走 `toString()` 落成文本单元格。
- `Double` / `Float` 的 NaN、Infinity → 退回文本（Excel 表示不了这两个值）。
- `Long` / `BigDecimal` → 能精确表示时数字单元格；`Long` 超过 ±2^53、`BigDecimal` 超过
  15 位有效数字时落成**文本**。checksum / `SUM(hash(...))` 常年是 64 位整数，直接写 double
  会被静默四舍五入（`123456789012345678` → `123456789012345680`），宁可退化成文本也不改数。

落盘由 `XlsxWriter` 手写**最小 OOXML**，只用 `java.util.zip` + 拼串，零额外依赖
（不引 POI / xmlbeans / commons-* / log4j-api）。写出的部件只有 `[Content_Types].xml`、
`_rels/.rels`、`xl/workbook.xml`、`xl/_rels/workbook.xml.rels`、`xl/styles.xml`、
`xl/worksheets/sheetN.xml`；单元格一律 `t="inlineStr"`（不写 sharedStrings），没有主题 /
docProps / 列宽 / 自动过滤 / 公式 / 图表，样式只有正文（顶对齐 + 自动换行）、标题（加粗
13pt）、表头（加粗 + 灰底）三种。单元格文本超过 32767 字符（Excel 单格上限，超了整份工作簿
会被判损坏）时截断并补 `...`；XML 1.0 非法控制字符、落单的代理项（unpaired surrogate）直接
丢弃——一个畸形字符会毁掉整份工作簿，不值得为它保住那一格。写入是覆盖写、自动建父目录，
父目录不存在 / 无权限 / 磁盘满照样抛 IO 异常（与 [webhook](#webhook)「任何 IO 异常都吞掉」
相反）。

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
report.excel("reports/order_sync_$dt.xlsx", summary)
if (alertUrl.isNotEmpty()) report.webhook(alertUrl, summary)
```

接收方按普通 JSON 解析即可。飞书 / 钉钉 / Slack 的入站 webhook 各自要求特定字段结构，
本 payload 不是它们开的格式——需要对接时请用对应的 bot 接口，或加一层转发服务，
不要指望直接填对方的 webhook 地址。

---

## 8. 配置与环境变量

CLI 不再做"前置装配"——`--impala-url` 已删除，连接信息走 Check 自己持有的字段
（典型：`@Volatile var cfg = ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT)`），env 兜底在
`ImpalaConfig.fromEnv` 里完成。

### 变量表

| 变量 | 作用 | 用在 |
|:---|:---|:---|
| `IMPALA_JDBC_URL` | 下游 Impala JDBC URL（Wilson 域 Check 默认读） | `ImpalaConfig.fromEnv` |
| `IMPALA_USER` | Impala 用户名（LDAP） | `ImpalaConfig.fromEnv` |
| `IMPALA_PASSWORD` | Impala 口令 | `ImpalaConfig.fromEnv` |
| `ALERT_URL` | 告警 webhook（CLI `--alert-url` 的同名 env） | CLI |
| `ORDERS_PARQUET_PATH` | 内置 `order_sync` 的源端 parquet 路径 | `OrderSyncCheck` |
| `ORDERS_TGT_PARQUET_PATH` | 内置 `order_sync` 的目标端 parquet 路径 | `OrderSyncCheck` |

优先级：env > Check 字段当前值。Check 之间互不影响——一个 Check 改自己的 `cfg` 不会
牵动别的 Check；改 env 则所有读到该 env 的 Check 一起生效（看各 Check `fromEnv` 实现）。

### 代码里改连接配置

```kotlin
// 内置 Check 的 test seam：换上游 / 下游路径（@Volatile，读写并发安全）
OrderSyncCheck.parquetPath = "/data/orders_canary/dt=2026-09-20/part-0.parquet"
OrderSyncCheck.tgtParquetPath = "/data/orders_tgt_canary/dt=2026-09-20/part-0.parquet"

// 要整体换掉两端 Connector（例如下游换成 Impala），用 injected：
OrderSyncCheck.injected = ParquetConnector(OrderSyncCheck.parquetPath) to
    ImpalaConnector(ImpalaConfig("jdbc:hive2://canary:21050", "etl", "secret"))

// 自己写的 Check 里更直接：@Volatile var cfg = ImpalaConfig.fromEnv(ImpalaConfig.DEFAULT)
```

Impala JDBC URL 形态与三种认证（驱动：`org.apache.hive:hive-jdbc`，scheme `jdbc:hive2://`）：

| 模式 | URL 关键参数 | 凭据 |
|:---|:---|:---|
| 无认证 | 无（默认） | 不需要 |
| LDAP | `auth=LDAP` | 可写进 URL，或走 `user` / `password` |
| Kerberos | `principal=hive/_HOST@REALM` | 票据由 JVM 从 ticket cache 取，`user` / `password` 留空 |

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
        # 连接信息走 Check 自己持有的字段；env 只放切换集群 / 凭据时要覆盖的值。
        "IMPALA_JDBC_URL": "jdbc:hive2://impala-prod:21050/default",
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

用例分布（共 105 个；`./gradlew test` 的 "tests completed" 计数）：

| 模块 | 用例文件 | 覆盖 |
|:---|:---|:---|
| `core`（87） | `core/src/test/kotlin/.../core/CoreTypesTest.kt`（27） | Row 扩展、FieldRules 两种写法、DiffSummary |
| | `.../engine/DiffEngineTest.kt`（17） | 三档 diff、计数器、`keySet` 去重与扫描次数 |
| | `.../engine/DiffAlignTest.kt`（10） | `align` 内核（顺序 / 缺行侧 / 重复键 / 惰性 / 不碰计数器）与 `reconcile` 自定义档（计数进 summary） |
| | `.../connectors/ParquetConnectorTest.kt`（5） | DuckDB 读写、类型归一、`guard` 契约 |
| | `.../connectors/JdbcConnectorContractTest.kt`（5） | `JdbcConnector` 的取数语义与「失败必抛 `ConnectorError`」（含 `stream`），用内置 H2 当方言替身 |
| | `.../connectors/MssqlTopOneTest.kt`（5） | `one()` 的 T-SQL 改写（`SELECT TOP 1`）与边界 |
| | `.../connectors/ImpalaConnectorTest.kt`（3） | `ImpalaConfig.fromEnv` 优先级、连不上时 fail fast |
| | `.../reporter/ReporterTest.kt`（15） | 手写 OOXML 写出的 `.xlsx` 用 POI（仅测试期依赖）读回校验 + webhook JSON |
| `checks`（18） | `checks/src/test/kotlin/.../check/CheckTest.kt`（6） | Args 注入、注册表语义 |
| | `.../check/CheckRegistryTest.kt`（8） | 注册、查重、`discover` |
| | `.../checks/OrderSyncCheckEndToEndTest.kt`（4） | 端到端（DuckDB 造数 → 跑 Check → 断言 Summary） |
| `app`（0） | — | CLI 目前靠手工冒烟（见 §2） |

---

## 11. 故障排查

| 现象 | 原因 | 处理 |
|:---|:---|:---|
| `ConnectorError$QueryFailed: ... No files found that match the pattern` | SQL 里的 `read_parquet('...')` 路径不存在 | 检查 `ORDERS_PARQUET_PATH` 等路径与分区日期 |
| `Unknown check: xxx. Available: order_sync` | `--check` 名字不对，或清单文件没加载到 | 名字区分大小写；可用名字以报错信息里列的为准；确认 `--registry` 指向的文件存在且有有效行 |
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

1. **`summary()` 最高只到 WARN**，无法区分 Mismatch 与 Missing；需要 ERROR 档请自行用
   `DiffSummary.accumulate` 折算。
2. **`ImpalaConnector` 没有对真实 Impala 的集成测试**（无可用实例）。已验证到「驱动能加载并真的
   去建 TCP 连接」这一层（JDK 17 下 `HiveDriver` 注册成功，连不上时报
   `TTransportException: ConnectException`），Kerberos / SASL / 结果集归一这些只有真实集群能验，
   上线前请回归一次。
   驱动固定在 `hive-jdbc:4.1.0:standalone`（见 `core/build.gradle.kts` 的注释）：4.2.x 的驱动是
   Java 21 字节码，JDK 17 运行时会让**所有** JDBC 驱动注册失效（详见 README §13）。
3. **`OrderSyncCheck` 的 L1 checksum 用 DuckDB 的 `hash()`**，是方言函数；目标端换成
   Impala 时要改成 `fnv_hash()` 之类的等价函数，否则 checksum 列必然不平。
4. **`OrderSyncCheck` 阶段 1 默认两端都走 Parquet**，便于零依赖自测；生产要把下游换成
   Impala，见 `defaultConnectors()` 或注入 `injected`。
5. **SQL 是字符串拼接的**（示例级别）。生产的动态值必须参数化或经白名单校验，否则有注入
   风险——README §15 已列为中风险项。
6. **`DiffEngine` 不是线程安全的**：一个 Check 一次执行一个实例。要在同一个 Check 里并发
   跑多档 diff，用 `Ctx.diff` 之外自己 new 的实例。
7. **MySQL 的流式需要 URL 里带 `useCursorFetch=true`**：Connector-J 只在这个参数打开时才按
   `fetchSize` 走服务端游标，否则 `stream()` 会把整表拉回客户端再切分（惰性形同虚设）。连接器
   不代改 URL，写 `MySQLConfig` 时自己带上。
8. **L2 的 `keySet` 对 NULL 主键直接失败**，不会静默跳过：NULL 主键没法对齐，跳过会让"上游多出来
   的脏行"看起来像"两端一致"。要容忍就在 SQL 里 `WHERE key IS NOT NULL` 或 `COALESCE` 归一。
9. **抽样下钻的 `LIMIT` 必须配 `ORDER BY`**：只写 `LIMIT` 时取哪几行由扫描顺序决定，两次运行
   样到不同子集，结论不可回归。
10. **`aggregate` / `compareRows` / `reconcile` 共用内核，行序与重复键规则一致**：先 `tgt` 流序
    （命中与 tgt 独有交错）、再 src 独有；重复主键 src 侧保留首次出现、tgt 侧逐条产出。
    `aggregate` 的输出行序因此由「先 src、再 tgt 独有」变为内核序——报告是给人看的表格，
    按主键排序请在 SQL 里 `ORDER BY`（或对结果自行排序）。
