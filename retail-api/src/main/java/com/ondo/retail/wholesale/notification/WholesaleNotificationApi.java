package com.ondo.retail.wholesale.notification;

import com.ondo.retail.wholesale.dto.WholesaleEnvelope;
import com.ondo.retail.wholesale.notification.dto.WholesaleNotification;
import com.ondo.retail.wholesale.notification.dto.WholesaleNotificationRead;
import com.ondo.retail.wholesale.notification.dto.WholesaleNotificationReadRequest;
import com.ondo.retail.wholesale.notification.dto.WholesaleNotificationUnread;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 도매 소매접점 알림 API 의 계약 (MUL-155). 구현은 스프링이 끼운다({@code WholesaleHttpConfig}).
 *
 * <p>keyset 시각을 epoch 마이크로초 숫자로 보낸다. ISO 문자열의 {@code +} 가 쿼리에서 공백으로
 * 바뀌는 사고를 피하려는 것이다.
 */
@HttpExchange("/api/retail-gateway/notifications")
public interface WholesaleNotificationApi {

    @GetExchange
    WholesaleEnvelope<List<WholesaleNotification>> notifications(
            @RequestParam Long retailerId,
            @RequestParam int limit,
            @RequestParam(required = false) Long beforeMicros,
            @RequestParam(required = false) Long beforeId);

    @GetExchange("/unread-count")
    WholesaleEnvelope<WholesaleNotificationUnread> unreadCount(@RequestParam Long retailerId);

    @PostExchange("/read")
    WholesaleEnvelope<WholesaleNotificationRead> read(@RequestBody WholesaleNotificationReadRequest request);
}
