package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.inquiry.InquiryInput.Packing;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 미송 0일 때 출고 준비 중 주문 답 (MUL-157). 웹 목 {@code packingOrders.ts} 를 옮겼다.
 * 본문에는 주문 번호만 나간다 — 출고 예정일·상품명·수량을 쓰지 않는다.
 */
final class PackingOrders {

    private PackingOrders() {
    }

    /** 본문에 번호를 싣는 주문 수 상한. 넘치면 "외 n건". */
    static final int SHOWN = 3;

    /**
     * 남기는 조합은 둘뿐이다: READY + 봉투 없음, PACKED + 봉투 출고 전. 이상값을 "준비 중" 으로 읽으면
     * 이미 떠난 물건을 기다리라고 말하게 된다.
     */
    private static boolean readyToShip(Packing p) {
        if ("READY".equals(p.packingStatus())) {
            return p.outboundShipped() == null;
        }
        return Boolean.FALSE.equals(p.outboundShipped());
    }

    /** 이 방 도매처 + 이 소매처의 유효한 출고 준비 중 포장을 주문으로 묶은 번호(오름차순). */
    static List<Long> orderIdsOf(List<Packing> rows, long wholesalerId, long retailerId) {
        return rows.stream()
                .filter(p -> Objects.equals(p.wholesalerId(), wholesalerId) && p.retailerId() == retailerId)
                .filter(p -> !p.orderCancelled() && p.qty() > 0)
                .filter(PackingOrders::readyToShip)
                .map(Packing::orderId)
                .distinct()
                .sorted()
                .toList();
    }

    /** 번호 뒤에 조사를 붙이지 않는다(끝 숫자마다 은/는이 바뀐다). */
    static String replyBody(List<Long> orderIds) {
        String shown = orderIds.stream().limit(SHOWN).map(id -> "#" + id).collect(Collectors.joining(", "));
        if (orderIds.size() == 1) {
            return "출고 준비 중인 주문이 있어요(" + shown + ")";
        }
        int more = orderIds.size() - SHOWN;
        String tail = more > 0 ? " 외 " + more + "건" : "";
        return "출고 준비 중인 주문 " + orderIds.size() + "건이 있어요(" + shown + tail + ")";
    }
}
