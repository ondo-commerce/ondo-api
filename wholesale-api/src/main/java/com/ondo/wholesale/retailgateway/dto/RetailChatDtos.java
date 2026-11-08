package com.ondo.wholesale.retailgateway.dto;

import com.ondo.wholesale.chat.domain.ChatGeneration;
import com.ondo.wholesale.chat.dto.ChatMessagePage.ChatMessagePageMeta;
import com.ondo.wholesale.common.response.ResponseEnvelope;

import java.util.List;

/**
 * 소매 채팅 게이트웨이의 작은 요청·응답 모양 (MUL-157). 소매 백엔드만 부른다 — 프론트 계약은 소매가 만든다.
 */
public final class RetailChatDtos {

    private RetailChatDtos() {
    }

    /**
     * 방 열기. retailerId·retailerName 은 소매 세션·소매 DB 에서 온 값이다. retailerName 은 방에 스냅샷으로 남는다
     * (거래 전 소매처가 방을 열 수도 있어 도매 partner 에 이름이 없을 수 있다).
     */
    public record OpenRequest(Long retailerId, String retailerName, Long wholesalerId, Long listingId) {
    }

    /** 방 열기 결과. created 면 소매가 201, 아니면 200. */
    public record OpenResponse(Boolean created, RetailChatRoomResponse room) {
    }

    /** 보내기 결과(소매 계약 {@code ChatSendResult}). HTTP 201 새로 만듦 / 200 같은 키 replay. */
    public record SendResponse(RetailChatMessageResponse message, List<RetailChatMessageResponse> generatedMessages,
                               ChatGeneration generation) {
    }

    /** 메시지 한 장 — {@code {data, meta}}. meta 는 도매 응답과 같은 칸(상대 = 도매의 읽음 위치). */
    public record MessagePage(List<RetailChatMessageResponse> data, ChatMessagePageMeta meta)
            implements ResponseEnvelope {
    }

    /** 소매 헤더 배지의 채팅 몫. 그 소매처 방들의 unreadCount 합. */
    public record UnreadResponse(Integer chats) {
    }
}
