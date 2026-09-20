package com.kxxnzstdsw.sync_diff.checks

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [WilsonActivityApplyDetailCheck] 的端到端测试：跑 [WilsonActivityCheckEndToEndTestBase]
 * 提供的「相同 / 行数差 / 列值漂移」三档断言。
 *
 * 本类只声明 apply_detail 这张表特有的 schema 与「故意错开两列」的值；
 * parquet 写入、connector 注入、summary 断言全部走父类。
 */
class WilsonActivityApplyDetailCheckEndToEndTest : WilsonActivityCheckEndToEndTestBase() {

    override val check: WilsonActivityCheckBase = WilsonActivityApplyDetailCheck

    /**
     * 测试 schema 与生产 schema 对齐：`WilsonActivityApplyDetailCheck.columns` 是
     * 私有字段，测试时从已知的列清单复制一份（避免反射破坏封装）。改 apply_detail 列清单时
     * 必须同步改这里。
     */
    override val columns: List<String> = listOf(
        "source_id", "create_time", "create_user", "create_user_type", "division",
        "last_update_time", "last_update_user", "last_update_user_type",
        "operation_note", "removed", "tenant",
        "code", "activity_code", "member_code", "enroll_mobile", "enroll_time",
        "is_checkin", "checkin_time", "is_valid", "status_name",
        "point_price", "refund_price",
        "approve_time", "note", "member_level", "cancell_time",
        "member_mobile", "member_card", "team_code",
        "is_need_form", "application_form",
        "participant_id_type", "participant_id_card", "participant_name",
        "group_code", "group_name", "is_candidate",
        "enrolled_date_txt", "enrolled_time_frame_code", "enrolled_time_frame",
        "enrolled_date", "enrolled_start_time", "is_reminded_for_starting",
        "id", "event_id", "apply_id", "event_tag",
        "bi_create_time", "bi_update_time",
    )

    /**
     * 与上游 schema 对齐的列类型：TIMESTAMP 走 TIMESTAMP、BIGINT 走 BIGINT、其它一律 VARCHAR
     * （简化测试覆盖）。
     */
    override fun typeFor(col: String): String = when (col) {
        "source_id", "create_user", "division", "last_update_user", "tenant",
        "point_price", "refund_price" -> "BIGINT"
        "create_time", "last_update_time", "enroll_time", "checkin_time",
        "approve_time", "cancell_time", "enrolled_date", "enrolled_start_time",
        "bi_create_time", "bi_update_time" -> "TIMESTAMP"
        else -> "VARCHAR"
    }

    /**
     * 给定列名与行号 [i]，生成一个稳定且类型兼容的默认值：
     * - BIGINT 列：`i.toLong()`
     * - TIMESTAMP 列：`TIMESTAMP '2026-09-20 00:00:00' + i INTERVAL SECOND`
     * - VARCHAR 列：`'<col>-<i>'`，PK 列 `source_id` 用 `i`
     */
    override fun defaultFor(col: String, i: Int): String = when (col) {
        "source_id", "create_user", "last_update_user", "tenant" -> i.toString()
        "division" -> i.toString()
        "point_price", "refund_price" -> "100"
        "create_time", "last_update_time", "enroll_time", "checkin_time",
        "approve_time", "cancell_time", "enrolled_date", "enrolled_start_time",
        "bi_create_time", "bi_update_time" ->
            "TIMESTAMP '2026-09-20 00:00:00' + INTERVAL ($i) SECOND"
        else -> "'$col-$i'"
    }

    @Test
    fun `WilsonActivityApplyDetailCheck is registered with name wilson_apply_detail_sync`() {
        assertEquals("wilson_apply_detail_sync", WilsonActivityApplyDetailCheck.name)
    }
}
