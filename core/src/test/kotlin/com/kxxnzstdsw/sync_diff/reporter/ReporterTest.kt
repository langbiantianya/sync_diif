package com.kxxnzstdsw.sync_diff.reporter

import com.kxxnzstdsw.sync_diff.core.DiffLevel
import com.kxxnzstdsw.sync_diff.core.DiffSummary
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReporterTest {

    private fun tmp(name: String): Path = Files.createTempFile("reporter-test-", "-$name.md")

    @Test
    fun `markdown renders summary with level counts and badge`() {
        val out = tmp("render")
        val summary = DiffSummary(srcCount = 100, tgtCount = 99, diffCount = 7, level = DiffLevel.WARN)
        Reporter().markdown(out, summary)

        val body = Files.readString(out)
        assertTrue(body.contains("# Diff Summary"), "must have title")
        assertTrue(body.contains("| src rows | 100 |"))
        assertTrue(body.contains("| tgt rows | 99 |"))
        assertTrue(body.contains("| diff keys | 7 |"))
        assertTrue(body.contains("| level | WARN |"))
        assertTrue(body.contains("hasDiff = true"))
    }

    @Test
    fun `markdown overwrites existing file`() {
        val out = tmp("overwrite")
        Reporter().markdown(out, DiffSummary(1, 1, 0, DiffLevel.INFO))
        Reporter().markdown(out, DiffSummary(10, 11, 12, DiffLevel.ERROR))

        val body = Files.readString(out)
        assertTrue(body.contains("| src rows | 10 |"))
        assertTrue(body.contains("| diff keys | 12 |"))
        assertTrue(body.contains("| level | ERROR |"))
    }

    @Test
    fun `webhook no-op on blank url`() {
        val summary = DiffSummary(1, 1, 0, DiffLevel.INFO)
        // should not throw, regardless of network.
        Reporter().webhook("", summary)
        Reporter().webhook(null, summary)
        Reporter().webhook("   ", summary)
    }

    @Test
    fun `webhook posts JSON to a running HTTP server`() {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/webhook") { ex ->
            ex.sendResponseHeaders(200, -1)
            ex.responseBody.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/webhook"
            val captured = java.util.concurrent.atomic.AtomicReference<String?>(null)

            val capturing = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            capturing.createContext("/capture") { ex ->
                captured.set(ex.requestBody.bufferedReader().readText())
                ex.sendResponseHeaders(200, -1)
                ex.responseBody.close()
            }
            capturing.start()
            try {
                val capUrl = "http://127.0.0.1:${capturing.address.port}/capture"
                val s = DiffSummary(srcCount = 1, tgtCount = 2, diffCount = 3, level = DiffLevel.ERROR)
                Reporter().webhook(capUrl, s)
                val body = captured.get()
                assertNotNull(body)
                assertTrue(body!!.contains("\"level\":\"ERROR\""))
                assertTrue(body.contains("\"srcCount\":1"))
                assertTrue(body.contains("\"tgtCount\":2"))
                assertTrue(body.contains("\"diffCount\":3"))
                assertTrue(body.contains("\"hasDiff\":true"))
            } finally {
                capturing.stop(0)
            }

            // Verify the original 'server' URL is still 200-OK to webhook without exception.
            Reporter().webhook(url, DiffSummary.EMPTY)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `webhook swallows IO failure`() {
        // closed port — should not throw.
        Reporter().webhook("http://127.0.0.1:1/webhook", DiffSummary.EMPTY)
        assertFalse(false)
    }

    @Test
    fun `markdown renders the summary document as a whole`() {
        val out = tmp("whole")
        Reporter().markdown(out, DiffSummary(100, 99, 7, DiffLevel.WARN))
        assertEquals(
            """
            # Diff Summary

            | metric | value |
            |:---|---:|
            | level | WARN |
            | src rows | 100 |
            | tgt rows | 99 |
            | diff keys | 7 |

            > hasDiff = true

            Top diff keys are reported in the linked L3 detail file.
            """.trimIndent() + "\n",
            Files.readString(out),
        )
    }

    @Test
    fun `markdown appends custom content after the summary block`() {
        val out = tmp("custom")
        Reporter().markdown(out, DiffSummary(3, 2, 1, DiffLevel.WARN)) {
            heading("差异明细")
            table(
                headers = listOf("列", "src", "tgt"),
                rows = listOf(listOf("amount", 1, 2)),
                aligns = listOf(Align.LEFT, Align.RIGHT, Align.RIGHT),
            )
        }
        val body = Files.readString(out)
        assertTrue(body.startsWith("# Diff Summary"), "summary block must come first")
        assertTrue(
            body.indexOf("Top diff keys") < body.indexOf("## 差异明细"),
            "custom content must follow the summary footer",
        )
        assertTrue(body.contains("|:---|---:|---:|"), "aligns must reach the separator row")
        assertTrue(body.contains("| amount | 1 | 2 |"))
    }

    @Test
    fun `markdown without summary writes only the custom document`() {
        val out = tmp("custom-only")
        Reporter().markdown(out) {
            heading("巡检结果", level = 1)
            bullets(listOf("分区齐全", "part 数一致"))
        }
        assertEquals("# 巡检结果\n\n- 分区齐全\n- part 数一致\n", Files.readString(out))
    }

    @Test
    fun `markdownTable escapes cells and renders alignment`() {
        assertEquals(
            "| pk | note |\n|:---|---:|\n| 1 | a\\|b<br>c |\n| null | x |",
            markdownTable(
                headers = listOf("pk", "note"),
                rows = listOf(listOf(1, "a|b\nc"), listOf(null, "x")),
                aligns = listOf(Align.LEFT, Align.RIGHT),
            ),
        )
    }

    @Test
    fun `markdown helpers reject malformed input`() {
        assertFailsWith<IllegalArgumentException>("empty header") {
            markdownTable(headers = emptyList(), rows = emptyList())
        }
        assertFailsWith<IllegalArgumentException>("aligns/columns mismatch") {
            markdownTable(listOf("a"), emptyList(), aligns = listOf(Align.LEFT, Align.RIGHT))
        }
        assertFailsWith<IllegalArgumentException>("row shorter than header") {
            markdownTable(listOf("a", "b"), listOf(listOf(1)))
        }
        assertFailsWith<IllegalArgumentException>("heading level out of range") {
            MarkdownBuilder().heading("x", level = 7)
        }
    }

    private fun assertNotNull(s: String?) = kotlin.test.assertNotNull(s)
}