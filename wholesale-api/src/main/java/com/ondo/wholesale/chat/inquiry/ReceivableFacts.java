package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatOverdueFacts;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatReceivableFacts;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Receivable;

import java.util.List;
import java.util.Optional;

/**
 * 미수 원본 한 줄 → 소매에게 나갈 금액 (MUL-157). 웹 목 {@code receivable.ts} 를 옮겼다.
 *
 * <p>지키는 것 하나: <b>틀린 금액을 말하느니 사람에게 넘긴다.</b> 부호와 이상값은 {@link #convert} 한 곳에서만 본다.
 */
final class ReceivableFacts {

    private ReceivableFacts() {
    }

    /** JS 안전 정수(±2^53 − 1). 목과 같은 경계로 본다 — 넘는 금액은 글로 바꿀 때 틀어진다. */
    private static final long MAX_SAFE = (1L << 53) - 1;

    /** 이 방 도매처 + 이 방 소매처 한 줄. 화면이 보낸 값이 아니라 방의 값으로 고른다. */
    static Optional<Receivable> of(List<Receivable> rows, long wholesalerId, long retailerId) {
        return rows.stream()
                .filter(r -> r.wholesalerId() == wholesalerId && r.retailerId() == retailerId)
                .findFirst();
    }

    /**
     * 아래 하나라도 맞으면 빈 값(→ AMOUNT_UNSURE 넘김):
     * <ol>
     *   <li>exact false</li>
     *   <li>안전 정수가 아닌 값 / 음수(balance 빼고 전부)</li>
     *   <li>unpaid − prepaid ≠ balance</li>
     *   <li>unpaid > 0 이고 prepaid > 0 — 배분 안 된 입금이 있다</li>
     *   <li>연체 건수와 연체 금액 중 하나만 0이 아니다</li>
     *   <li>연체 건수 > 0 인데 최대 일수 < 1</li>
     *   <li>연체 금액 > 갚을 돈</li>
     * </ol>
     */
    static Optional<ChatReceivableFacts> convert(Receivable row) {
        long[] nums = {row.balance(), row.unpaid(), row.prepaid(),
                row.overdueAmount(), row.overdueCount(), row.overdueMaxDays()};
        if (!row.exact()) {
            return Optional.empty();
        }
        for (long n : nums) {
            if (n > MAX_SAFE || n < -MAX_SAFE) {
                return Optional.empty();
            }
        }
        for (int i = 1; i < nums.length; i++) {
            if (nums[i] < 0) {
                return Optional.empty();
            }
        }
        if (row.unpaid() - row.prepaid() != row.balance()) {
            return Optional.empty();
        }
        if (row.unpaid() > 0 && row.prepaid() > 0) {
            return Optional.empty();
        }
        if ((row.overdueCount() > 0) != (row.overdueAmount() > 0)) {
            return Optional.empty();
        }
        if (row.overdueCount() > 0 && row.overdueMaxDays() < 1) {
            return Optional.empty();
        }
        if (row.overdueAmount() > row.unpaid()) {
            return Optional.empty();
        }
        if (row.overdueCount() > Integer.MAX_VALUE || row.overdueMaxDays() > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new ChatReceivableFacts(row.unpaid(), row.prepaid(),
                new ChatOverdueFacts(row.overdueAmount(), (int) row.overdueCount(), (int) row.overdueMaxDays())));
    }

    /** 원 단위 정수에 세 자리 쉼표. 반올림·줄임 없음. 로캘에 기대지 않는다. */
    static String formatWon(long n) {
        return String.valueOf(n).replaceAll("\\B(?=(\\d{3})+(?!\\d))", ",") + "원";
    }

    /** 미수 답 본문. 이 글이 소매에게 그대로 간다. */
    static String replyBody(ChatReceivableFacts f) {
        if (f.outstandingAmount() > 0) {
            return f.overdue().count() > 0
                    ? "갚을 돈은 %s이에요. 그중 기한 지난 것 %d건, %s이에요".formatted(
                            formatWon(f.outstandingAmount()), f.overdue().count(), formatWon(f.overdue().amount()))
                    : "갚을 돈은 %s이에요. 기한 지난 것은 없어요".formatted(formatWon(f.outstandingAmount()));
        }
        return f.prepaidAmount() > 0
                ? "지금 갚을 돈은 없어요. 미리 낸 돈(선수금) %s이 남아 있어요".formatted(formatWon(f.prepaidAmount()))
                : "지금 갚을 돈은 없어요";
    }
}
