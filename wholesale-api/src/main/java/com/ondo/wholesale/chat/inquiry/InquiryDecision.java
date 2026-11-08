package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.domain.StockBand;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatReceivableFacts;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Listing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Variant;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 판정 결과 (MUL-157). 웹 목 {@code InquiryDecision} 의 갈래 그대로다. 줄로 바꾸는 건 {@link InquiryLines} 가 한다.
 * {@link #intent()} 는 그때 읽은 의도 — 넘김이면 못 읽었을 때 null.
 */
public sealed interface InquiryDecision {

    ChatIntent intent();

    /** 사람에게 넘긴다. */
    record Handoff(ChatHandoffReason reason, ChatIntent intent) implements InquiryDecision {
    }

    /** 재고 되묻기. choices = 게시 옵션 전부(색상 등록 순 → 사이즈 순). */
    record StockClarify(Listing listing, List<Variant> choices) implements InquiryDecision {
        @Override
        public ChatIntent intent() {
            return ChatIntent.STOCK;
        }
    }

    /**
     * 재고 답.
     *
     * @param orderable  게시 중 + 게시된 옵션. 수량과 무관하다
     * @param orderLimit 한도 없음이면 null
     * @param qtyNote    문장에 "10장" 같은 수량이 있었다 → 답 끝에 "수량은 주문서에서 확인돼요"
     */
    record StockAnswer(Listing listing, Variant variant, StockBand band, boolean orderable, Integer orderLimit,
                       boolean qtyNote) implements InquiryDecision {
        @Override
        public ChatIntent intent() {
            return ChatIntent.STOCK;
        }
    }

    /**
     * 미송 되묻기. choices 는 오래된 순 최대 8개. 끝의 「다른 주문이에요」 보기는 줄을 만들 때 붙인다.
     *
     * @param truncated 후보가 8개를 넘어 잘랐다
     */
    record BackorderClarify(List<Backorder> choices, boolean truncated, LocalDate today) implements InquiryDecision {
        @Override
        public ChatIntent intent() {
            return ChatIntent.BACKORDER;
        }
    }

    /** 미송 답. */
    record BackorderAnswer(Backorder backorder, EtaState eta, LocalDate today) implements InquiryDecision {
        @Override
        public ChatIntent intent() {
            return ChatIntent.BACKORDER;
        }
    }

    /**
     * 미송 0 + 출고 준비 중 주문. 미송 하나를 가리키지 않아 subject·facts 가 전부 null 이고 NO_BACKORDER 넘김 줄이 늘 붙는다.
     *
     * @param orderIds 주문으로 묶은 번호(오름차순, 1개 이상)
     */
    record PackingAnswer(List<Long> orderIds) implements InquiryDecision {
        @Override
        public ChatIntent intent() {
            return ChatIntent.BACKORDER;
        }
    }

    /**
     * 미수 답. 이 방 도매처 몫만.
     *
     * @param checkedAt 원본의 asOf. 판정 시각이 아니다
     */
    record ReceivableAnswer(ChatReceivableFacts facts, OffsetDateTime checkedAt) implements InquiryDecision {
        @Override
        public ChatIntent intent() {
            return ChatIntent.RECEIVABLE;
        }
    }

    /** 입고일 상태. 미정 NONE, 오늘보다 앞 PAST(지연이라 단정하지 않는다). */
    enum EtaState {
        FUTURE, TODAY, NONE, PAST;

        static EtaState of(LocalDate date, LocalDate today) {
            if (date == null) {
                return NONE;
            }
            if (date.equals(today)) {
                return TODAY;
            }
            return date.isAfter(today) ? FUTURE : PAST;
        }
    }
}
