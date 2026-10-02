package com.ondo.retail.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 주문 접수 결과.
 *
 * <p><b>일부만 접수돼도 201 이다. 에러가 아니라 결과다.</b> 도매처 넷 중 셋만 받아졌으면
 * 그건 정상적인 결과이지 실패가 아니다. 접수된 항목만 장바구니에서 빠진다.
 *
 * <p>전부 안 되면 통합 주문을 만들지 않는다 — 그때는 502 다.
 *
 * @param orderId     통합 주문서 id. 주문 상세를 부를 때 쓴다
 * @param orderNo     화면에 보여주는 주문번호. 20260902-1420-0088 모양이다
 * @param orderedAt   주문 시각
 * @param totalAmount 접수된 것만 합한 금액. 실패한 도매처는 빠진다
 * @param results     도매처별 결과. 성공과 실패가 섞여 온다
 */
public record PlaceOrderResponse(
        Long orderId,
        String orderNo,
        OffsetDateTime orderedAt,
        int totalAmount,
        List<Result> results) {


    /**
     * @param wholesalerId     도매처 id
     * @param wholesalerName   도매처 상호
     * @param isAccepted       이 도매처가 받았는지
     * @param wholesaleOrderId 도매쪽 주문 id. 실패면 null
     * @param orderNumber      도매처별 연번. 실패면 null
     * @param amount           이 도매처 금액. 실패면 null
     * @param reason           실패 코드. 성공이면 null
     * @param message          화면에 그대로 쓸 문구
     * @param isPending        서버가 대신 다시 보내는 중인지 (MUL-141).
     *                         <p>{@code isAccepted=false} 인데 이 값이 true 면 <b>끝난 실패가
     *                         아니다.</b> 도매가 잠깐 안 떠서 서버가 맡아 뒀다는 뜻이고,
     *                         그 줄은 장바구니에서 빠져 있다. 기한 안에 못 넣으면 다시
     *                         장바구니로 돌아온다.
     *                         <p>필드를 더하기만 했다 — 프론트가 무시하면 지금과 똑같이
     *                         동작한다. 화면 표시는 계약을 맞춘 뒤에 붙인다
     */
    @Schema(name = "PlaceOrderResult")
    public record Result(
            Long wholesalerId,
            String wholesalerName,
            boolean isAccepted,
            Long wholesaleOrderId,
            Integer orderNumber,
            Integer amount,
            String reason,
            String message,
            boolean isPending) {
    }
}
