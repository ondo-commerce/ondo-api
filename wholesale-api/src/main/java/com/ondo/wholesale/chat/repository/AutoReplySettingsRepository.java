package com.ondo.wholesale.chat.repository;

import com.ondo.wholesale.chat.inquiry.AutoReplySettings;
import com.ondo.wholesale.chat.inquiry.AutoReplySettingsSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 자동 답 설정 저장소 (MUL-159, V19 {@code chat_auto_reply_setting}). 판정이 읽는 {@link AutoReplySettingsSource} 의
 * 운영 구현이다 — 소매 보내기 트랜잭션 안에서 판정 직전에 읽으므로 PUT 이 커밋된 뒤 들어오는 메시지부터 걸린다.
 *
 * <p>행이 없는 도매처는 {@link AutoReplySettings#DEFAULTS}(전부 켜짐)다. 계약: "저장한 적이 없으면 전부 true".
 */
@Repository
@RequiredArgsConstructor
public class AutoReplySettingsRepository implements AutoReplySettingsSource {

    private final JdbcClient jdbc;

    @Override
    public AutoReplySettings forWholesaler(long wholesalerId) {
        return jdbc.sql("""
                        select enabled, stock_enabled, backorder_enabled, receivable_enabled
                          from wholesale.chat_auto_reply_setting where wholesaler_id = :wholesalerId
                        """)
                .param("wholesalerId", wholesalerId)
                .query((rs, n) -> new AutoReplySettings(rs.getBoolean("enabled"), rs.getBoolean("stock_enabled"),
                        rs.getBoolean("backorder_enabled"), rs.getBoolean("receivable_enabled")))
                .optional()
                .orElse(AutoReplySettings.DEFAULTS);
    }

    /** 통째로 저장한다(있으면 덮어쓴다). 동시에 두 PUT 이 와도 행은 하나고 나중 커밋이 이긴다. */
    public void save(long wholesalerId, AutoReplySettings settings) {
        jdbc.sql("""
                        insert into wholesale.chat_auto_reply_setting
                            (wholesaler_id, enabled, stock_enabled, backorder_enabled, receivable_enabled)
                        values (:wholesalerId, :enabled, :stock, :backorder, :receivable)
                        on conflict (wholesaler_id) do update
                           set enabled = excluded.enabled, stock_enabled = excluded.stock_enabled,
                               backorder_enabled = excluded.backorder_enabled,
                               receivable_enabled = excluded.receivable_enabled, updated_at = now()
                        """)
                .param("wholesalerId", wholesalerId)
                .param("enabled", settings.enabled())
                .param("stock", settings.stock())
                .param("backorder", settings.backorder())
                .param("receivable", settings.receivable())
                .update();
    }
}
