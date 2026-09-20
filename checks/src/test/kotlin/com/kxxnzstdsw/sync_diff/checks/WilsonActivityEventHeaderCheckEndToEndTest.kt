package com.kxxnzstdsw.sync_diff.checks

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [WilsonActivityEventHeaderCheck] 的端到端测试：跑 [WilsonActivityCheckEndToEndTestBase]
 * 提供的「相同 / 行数差 / 列值漂移」三档断言。
 *
 * 本类只声明 event_header 这张表特有的 schema 与「故意错开两列」的值；
 * parquet 写入、connector 注入、summary 断言全部走父类。
 */
class WilsonActivityEventHeaderCheckEndToEndTest : WilsonActivityCheckEndToEndTestBase() {

    override val check: WilsonActivityCheckBase = WilsonActivityEventHeaderCheck

    /**
     * 测试 schema 与生产 schema 对齐：`WilsonActivityEventHeaderCheck.columns` 是
     * 私有字段，测试时从已知的列清单复制一份（避免反射破坏封装）。改 event_header 列清单时
     * 必须同步改这里。
     */
    override val columns: List<String> = listOf(
        "source_id", "create_time", "create_user", "create_user_type", "division",
        "last_update_time", "last_update_user", "last_update_user_type",
        "operation_note", "removed", "tenant",
        "account_code", "activity_place", "application_date", "coach_code",
        "code", "description", "end_time", "images", "level",
        "material_code", "material_description", "need_material",
        "signed_party_code", "signed_party_name", "signed_party_type",
        "start_time", "status_name", "title",
        "note", "cover_image", "stock",
        "province", "city", "latitude", "longitude",
        "qrcode_id", "cancel_reason", "is_visible", "navigation_address",
        "source",
        "activity_type", "activity_category",
        "enroll_start_time", "enroll_end_time", "store_code",
        "is_stock_limit", "is_manual_approve", "member_level",
        "redeem_mode", "point_price",
        "is_auto_share", "share_title", "share_cover_image",
        "preview_mini_code", "promotion", "is_remind",
        "detail_image", "activity_nature", "store_city",
        "checkin_code", "is_use_cover_image",
        "is_need_form", "application_form", "activity_process",
        "is_team_stock_limit", "team_stock", "team_member_min",
        "is_repeat_enroll", "is_need_agreement",
        "agreement_title", "supplemental_agreement",
        "is_group_enrollment", "group_info", "partner_img",
        "name_list_confirm_time", "enable_alternate", "alternate_quota",
        "top_sort", "set_top_time", "top_operation_user_id",
        "need_product_recommendation", "product_recommendation_info",
        "calendar_based", "grouping_type", "check_in_type",
        "is_multiple_session_allowed", "ongoing_session_limit",
        "check_in_stores", "is_cancellation_allowed",
        "open_days_for_enrollment",
        "event_id", "event_tag",
        "bi_create_time", "bi_update_time",
    )

    /**
     * 与上游 schema 对齐的列类型：TIMESTAMP 走 TIMESTAMP、BIGINT 走 BIGINT、其它一律 VARCHAR
     * （简化测试覆盖）。
     */
    override fun typeFor(col: String): String = when (col) {
        "source_id", "create_user", "division", "last_update_user", "tenant",
        "stock", "point_price", "team_stock", "team_member_min",
        "alternate_quota", "top_sort", "top_operation_user_id",
        "ongoing_session_limit", "open_days_for_enrollment" -> "BIGINT"
        "create_time", "last_update_time", "application_date", "end_time",
        "start_time", "enroll_start_time", "enroll_end_time",
        "name_list_confirm_time", "set_top_time",
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
        "source_id", "create_user", "last_update_user", "tenant",
        "division", "stock", "point_price", "team_stock", "team_member_min",
        "alternate_quota", "top_sort", "top_operation_user_id",
        "ongoing_session_limit", "open_days_for_enrollment" -> i.toString()
        "create_time", "last_update_time", "application_date", "end_time",
        "start_time", "enroll_start_time", "enroll_end_time",
        "name_list_confirm_time", "set_top_time",
        "bi_create_time", "bi_update_time" ->
            "TIMESTAMP '2026-09-20 00:00:00' + INTERVAL ($i) SECOND"
        else -> "'$col-$i'"
    }

    /**
     * 在 src / tgt 之间故意错开两列：`title` 与 `description`。
     *
     * 父类「列值漂移」断言要求：drift 值不能与 [defaultFor] 在 row 1 的输出撞车
     * （否则 sample 会判 Equal）。这里 drift 值 `TGT-TITLE` / `TGT-DESC` 与
     * `defaultFor("title", 1) = "title-1"` 显然不同。
     */
    override fun driftValueFor(col: String): String? = when (col) {
        "title" -> "'TGT-TITLE'"
        "description" -> "'TGT-DESC'"
        "activity_place" -> "'TGT-PLACE'"
        else -> super.driftValueFor(col)
    }

    @Test
    fun `WilsonActivityEventHeaderCheck is registered with name wilson_event_header_sync`() {
        assertEquals("wilson_event_header_sync", WilsonActivityEventHeaderCheck.name)
    }
}
