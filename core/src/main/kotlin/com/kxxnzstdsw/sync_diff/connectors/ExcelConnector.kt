package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector

/**
 * Excel 连接器：DuckDB `excel` 扩展读 `.xlsx`。
 *
 * 建会话 / 取数的公共实现见 [DuckDbSessionConnector]，本类额外在会话期
 * `INSTALL` + `LOAD excel`（首次需要联网下载扩展，离线环境要有扩展缓存）；
 * [path] 只用于判断是否 `s3://` / `oss://`（自动加载 httpfs），**读哪些文件由调用方写的
 * SQL 决定**：
 *
 * ```kotlin
 * ExcelConnector("/data/orders/2026-09-01.xlsx").use { src ->
 *     src.stream("SELECT * FROM read_xlsx('$path')").take(1000).toList()
 * }
 * ```
 *
 * 工作表 / 表头行这类参数写在 **SQL 里**（`read_xlsx('…', sheet = 'Sheet2', header = true)`；
 * `read_xlsx` 是 DuckDB `excel` 扩展提供的函数名，不是 `st_read`——后者属于 spatial 扩展），
 * 本类不代拼接 SQL，所以没有 "sheet 构造参数"。
 *
 * @param path 本地路径、glob 或 `s3://` / `oss://` 路径（仅用于决定是否加载 httpfs）
 * @param memoryLimit DuckDB 引擎侧内存上限
 * @param tempDir 超出 [memoryLimit] 时的 spill 目录
 */
class ExcelConnector(
    private val path: String,
    memoryLimit: String = "4GB",
    tempDir: String = "/tmp/duckdb_spill",
) : DuckDbSessionConnector(memoryLimit, tempDir, listOf("excel") + httpfsFor(path))
