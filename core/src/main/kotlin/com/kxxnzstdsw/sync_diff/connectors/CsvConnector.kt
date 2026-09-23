package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector

/**
 * CSV 连接器：DuckDB `read_csv_auto`，自动推断列类型。
 *
 * 建会话 / 取数的公共实现见 [DuckDbSessionConnector]；[path] 只用于判断是否 `s3://` /
 * `oss://`（自动加载 httpfs），**读哪些文件由调用方写的 SQL 决定**：
 *
 * ```kotlin
 * CsvConnector("/data/orders/2026-09-01.csv").use { src ->
 *     src.stream("SELECT * FROM read_csv_auto('$path')").take(1000).toList()
 * }
 * ```
 *
 * CSV 的解析选项（`header` / `delim` / `columns` / 编码……）写在 **SQL 里**
 * （`read_csv_auto('…', header = true, delim = '\t')`）——本类不代拼接 SQL，所以没有
 * "options 构造参数"这种东西。
 *
 * @param path 本地路径、glob 或 `s3://` / `oss://` 路径（仅用于决定是否加载 httpfs）
 * @param memoryLimit DuckDB 引擎侧内存上限
 * @param tempDir 超出 [memoryLimit] 时的 spill 目录
 */
class CsvConnector(
    private val path: String,
    memoryLimit: String = "4GB",
    tempDir: String = "/tmp/duckdb_spill",
) : DuckDbSessionConnector(memoryLimit, tempDir, httpfsFor(path))
