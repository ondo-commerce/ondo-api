package com.ondo.retail.order.dto;

/**
 * 도매처 한 곳의 접수 결과 (MUL-98).
 *
 * <p>성공과 실패를 <b>한 타입으로</b> 표현한다. 부분 성공이 정상 결과라서다 —
 * 넷 중 셋만 받아진 것은 실패가 아니라 그냥 그런 결과다.
 *
 * @param accepted         받아졌는지
 * @param wholesaleOrderId 도매쪽 주문 id. 거절이면 null
 * @param orderNumber      도매처별 연번. 거절이면 null
 * @param amount           이 도매처 금액. 거절이면 null
 * @param reason           거절 코드. 도매가 준 것을 그대로 옮긴다. 성공이면 null
 * @param message          화면에 그대로 쓸 문구. 성공이면 null
 * @param retryable        다시 보내면 될 여지가 있는지 (MUL-139).
 *                         <p>판단을 여기 실어 보내는 이유 — 도매가 안 떠서 못 보낸 것과
 *                         재고가 없어서 거절당한 것은 완전히 다르다. 앞은 다시 하면 되고
 *                         뒤는 백 번 해도 실패한다. 그런데 그걸 아는 건 도매를 직접 부른
 *                         어댑터뿐이다. 코드 문자열을 밖에서 대조하게 두면 새 코드가
 *                         생길 때마다 대조표를 같이 고쳐야 하고, 빠뜨리면 조용히
 *                         영원히 재시도한다
 */
public record WholesaleOrderReceipt(
        boolean accepted,
        Long wholesaleOrderId,
        Integer orderNumber,
        Integer amount,
        String reason,
        String message,
        boolean retryable) {

    public static WholesaleOrderReceipt accepted(Long orderId, Integer orderNumber, Integer amount) {
        return new WholesaleOrderReceipt(true, orderId, orderNumber, amount, null, null, false);
    }

    /** 도매가 규약대로 거절했다. 다시 해도 같은 답이 온다. */
    public static WholesaleOrderReceipt rejected(String reason, String message) {
        return new WholesaleOrderReceipt(false, null, null, null, reason, message, false);
    }

    /** 도매가 안 떴거나 대답을 안 했다. 도매 사정이라 다시 해볼 만하다. */
    public static WholesaleOrderReceipt unreachable(String reason, String message) {
        return new WholesaleOrderReceipt(false, null, null, null, reason, message, true);
    }
}
