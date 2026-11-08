package com.ondo.wholesale.retailnotification;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.retailgateway.dto.RetailNotificationResponse;
import com.ondo.wholesale.retailgateway.dto.RetailNotificationUnreadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 소매 알림 읽기 · 읽음 처리 (MUL-155). 소매 백엔드가 게이트웨이로 부른다.
 *
 * <p>모든 질의가 {@code retailer_id = :retailerId} 로 시작한다. 소매처 id 는 소매가 자기 세션에서
 * 꺼내 싣는다 — 시크릿은 "소매 서버가 맞다" 까지만 증명한다(미송 게이트웨이와 같은 방식).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RetailNotificationService {

    private final JdbcClient jdbc;

    /**
     * 한 장. 정렬은 {@code created_at desc, id desc} 로 고정이다.
     *
     * <p>커서는 keyset 이다 — {@code (created_at, id) < (before, beforeId)}. 첫 장을 받은 뒤 새 알림이
     * 와도 다음 장에 중복·누락이 없다. 커서를 문자열로 만들고 푸는 건 소매 몫이다(프론트 계약이 소매 것).
     *
     * @param before   직전 장 마지막 행의 createdAt. 첫 장이면 null
     * @param beforeId 직전 장 마지막 행의 id. before 와 같이 온다
     * @param limit    가져갈 행 수. 소매가 다음 장 여부를 알려고 size + 1 로 부른다
     */
    public List<RetailNotificationResponse> page(long retailerId, OffsetDateTime before, Long beforeId, int limit) {
        String keyset = before == null ? "" : " and (n.created_at, n.id) < (:before, :beforeId)";
        var spec = jdbc.sql("""
                        select n.id, n.type, n.title, n.body, n.target_kind, n.target_order_id,
                               n.target_wholesaler_id, n.created_at, n.read_at
                        from wholesale.retail_notification n
                        where n.retailer_id = :retailerId
                        """ + keyset + """

                        order by n.created_at desc, n.id desc
                        limit :limit
                        """)
                .param("retailerId", retailerId)
                .param("limit", limit);
        if (before != null) {
            spec = spec.param("before", before).param("beforeId", beforeId);
        }
        return spec.query((rs, rowNum) -> new RetailNotificationResponse(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("body"),
                        new RetailNotificationResponse.Target(
                                rs.getString("target_kind"),
                                (Long) rs.getObject("target_order_id"),
                                (Long) rs.getObject("target_wholesaler_id")),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("read_at", OffsetDateTime.class)))
                .list();
    }

    /** 안 읽은 수와 가장 최근 알림 id. 화면은 개수가 아니라 id 가 바뀌었는지로 새 알림을 안다. */
    public RetailNotificationUnreadResponse unread(long retailerId) {
        return jdbc.sql("""
                        select (select count(*) from wholesale.retail_notification
                                where retailer_id = :retailerId and read_at is null) as unread,
                               (select id from wholesale.retail_notification
                                where retailer_id = :retailerId
                                order by created_at desc, id desc limit 1) as latest_id
                        """)
                .param("retailerId", retailerId)
                .query((rs, rowNum) -> new RetailNotificationUnreadResponse(
                        rs.getInt("unread"), (Long) rs.getObject("latest_id")))
                .single();
    }

    /**
     * 고른 것만 읽음. 멱등이다 — 이미 읽은 건 readAt 을 안 바꾼다. 없는 id · 남의 id 는 조용히 넘어간다.
     *
     * @return 처리 뒤 남은 안 읽음 수
     */
    @Transactional
    public int markRead(long retailerId, List<Long> ids) {
        jdbc.sql("""
                        update wholesale.retail_notification
                        set read_at = now()
                        where retailer_id = :retailerId and read_at is null and id in (:ids)
                        """)
                .param("retailerId", retailerId)
                .param("ids", ids)
                .update();
        return unread(retailerId).notifications();
    }

    /**
     * 안 읽음 전부를 읽음으로. 지금 안 읽은 수가 화면이 본 수와 다르면 <b>아무것도 안 바꾸고</b> 409 다 —
     * 그 사이 새 알림이 왔거나 다른 탭이 읽었다.
     *
     * <p>세기와 바꾸기를 한 문장에서 한다. 따로 하면 그 사이에 커밋된 새 알림이 세지지 않고 읽음만
     * 될 수 있다. 대상 행은 잠그고({@code for update}) 수가 맞을 때만 바꾼다 — expectedUnread 가
     * 1 이상이라 0행이면 곧 어긋났다는 뜻이고, 롤백에 기대지 않는다.
     *
     * @return 처리 뒤 남은 안 읽음 수
     */
    @Transactional
    public int markAllRead(long retailerId, int expectedUnread) {
        int changed = jdbc.sql("""
                        with target as (
                            select id from wholesale.retail_notification
                            where retailer_id = :retailerId and read_at is null
                            for update
                        ), matched as (
                            select count(*) = :expected as ok from target
                        )
                        update wholesale.retail_notification n
                        set read_at = now()
                        from target t, matched m
                        where n.id = t.id and m.ok
                        """)
                .param("retailerId", retailerId)
                .param("expected", expectedUnread)
                .update();
        if (changed == 0) {
            throw new ApiException(ErrorCode.UNREAD_CHANGED,
                    "안 읽은 알림 수가 바뀌었습니다. 기대 %d".formatted(expectedUnread));
        }
        return unread(retailerId).notifications();
    }
}
