package com.kxxnzstdsw.sync_diff.connectors

import com.kxxnzstdsw.sync_diff.core.Connector

/**
 * JSONL 连接器：DuckDB 读每行一个 JSON 对象的文件（`read_json_auto` / `read_ndjson_auto`）。
 *
 * 建会话 / 取数的公共实现见 [DuckDbSessionConnector]；[path] 只用于判断是否 `s3://` /
 * `oss://`（自动加载 httpfs），**读哪些文件由调用方写的 SQL 决定**：
 *
 * ```kotlin
 * JsonlConnector("/data/orders/2026-09-01.jsonl").use { src ->
 *     src.stream("SELECT * FROM read_json_auto('$path')").take(1000).toList()
 * }
 * ```
 *
 * @param path 本地路径、glob 或 `s3://` / `oss://` 路径（仅用于决定是否加载 httpfs）
 * @param memoryLimit DuckDB 引擎侧内存上限
 * @param tempDir 超出 [memoryLimit] 时的 spill 目录
 */
class JsonlConnector(
    private val path: String,
    memoryLimit: String = "4GB",
    tempDir: String = "/tmp/duckdb_spill",
) : DuckDbSessionConnector(memoryLimit, tempDir, httpfsFor(path))
