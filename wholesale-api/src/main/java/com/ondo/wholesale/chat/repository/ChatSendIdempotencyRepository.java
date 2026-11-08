package com.ondo.wholesale.chat.repository;

import com.ondo.wholesale.chat.domain.ChatSender;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 보내기 멱등 기록 (MUL-158). 키 범위는 (방, 보낸 사업체) — 소매 보내기(MUL-157)는 sender RETAILER 로 같은 테이블을 쓴다.
 * 응답 본문을 저장해 두고 replay 때 그대로 내린다.
 */
@Repository
@RequiredArgsConstructor
public class ChatSendIdempotencyRepository {

    private final JdbcClient jdbc;

    public record Recorded(String requestHash, String responseBody) {
    }

    public Optional<Recorded> find(long roomId, ChatSender sender, UUID key) {
        return jdbc.sql("""
                        select request_hash, response_body from wholesale.chat_send_idempotency
                         where room_id = :roomId and sender = :sender and idempotency_key = :key
                        """)
                .param("roomId", roomId)
                .param("sender", sender.name())
                .param("key", key)
                .query((rs, i) -> new Recorded(rs.getString("request_hash"), rs.getString("response_body")))
                .optional();
    }

    public void save(long roomId, ChatSender sender, UUID key, String requestHash, long messageId,
                     String responseBody) {
        jdbc.sql("""
                        insert into wholesale.chat_send_idempotency
                            (room_id, sender, idempotency_key, request_hash, message_id, response_body)
                        values (:roomId, :sender, :key, :hash, :messageId, :body)
                        """)
                .param("roomId", roomId)
                .param("sender", sender.name())
                .param("key", key)
                .param("hash", requestHash)
                .param("messageId", messageId)
                .param("body", responseBody)
                .update();
    }
}
