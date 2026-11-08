package com.ondo.retail.wholesale.chat.dto;

import com.ondo.retail.chat.dto.ChatMessageResponses.ChatInquiry;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomDetail;

/** 도매 채팅 게이트웨이의 요청·응답 중 소매 계약에 없는 모양 (MUL-157). */
public final class WholesaleChatDtos {

    private WholesaleChatDtos() {
    }

    /** 방 열기. retailerId 는 세션, retailerName 은 소매 DB 의 상호다. */
    public record OpenRequest(Long retailerId, String retailerName, Long wholesalerId, Long listingId) {
    }

    /** 방 열기 결과. created 면 소매가 201. */
    public record Opened(Boolean created, ChatRoomDetail room) {
    }

    /** 보내기 본문 — 소매가 받은 그대로(앞뒤 공백도 그대로) 넘긴다. 멱등 지문이 보낸 그대로의 본문을 본다. */
    public record SendRequest(String body, ChatInquiry inquiry) {
    }

    public record ReadRequest(Long lastReadSequence) {
    }

    /** 배지의 채팅 몫. */
    public record Unread(Integer chats) {
    }
}
