package com.ondo.wholesale.migration;

import com.ondo.wholesale.support.ChatFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V17 검증 (MUL-158) — 채팅 방·메시지·보내기 멱등 테이블.
 *
 * <p>잡고 싶은 사고: ① 한 쌍(도매처, 소매처)에 방이 둘 생긴다 ② 한 방에 같은 sequence 가 둘 ③ 넘김 줄에 이유가 없다
 * ④ 넘김 이유에 CHECK 가 걸려 판정(MUL-157)이 새 이유를 쓸 때마다 마이그레이션이 생긴다 ⑤ polling 인덱스가 빠진다.
 * API 동작은 chat 패키지의 통합 테스트가 본다.
 */
@SpringBootTest
@Transactional
class ChatSchemaTest extends PostgresTestSupport {

    @Autowired JdbcTemplate jdbc;

    private long 방;

    @BeforeEach
    void 방_하나를_심는다() {
        long 무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-schema@ondo.test", "9500000166");
        방 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회");
    }

    @Test
    void 테이블과_polling_인덱스가_있다() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'wholesale' and table_name like 'chat%' order by table_name
                """, String.class);
        assertThat(tables).containsExactly("chat_message", "chat_room", "chat_send_idempotency");

        List<String> indexes = jdbc.queryForList("""
                select indexname from pg_indexes where schemaname = 'wholesale' and tablename like 'chat%'
                """, String.class);
        assertThat(indexes).contains(
                "chat_room_wholesaler_activity_idx", "chat_room_retailer_activity_idx",
                "chat_message_sequence_uk", "chat_message_room_sender_sequence_idx", "chat_message_client_id_uk");
    }

    @Test
    void 같은_도매처_소매처_쌍에_방은_하나다() {
        long 무드온 = jdbc.queryForObject("select wholesaler_id from wholesale.chat_room where id = ?", Long.class, 방);

        assertThatThrownBy(() -> ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 한_방에_같은_sequence는_둘일_수_없다() {
        ChatFixture.소매_글을_넣는다(jdbc, 방, "첫 글");

        assertThatThrownBy(() -> jdbc.update("""
                insert into wholesale.chat_message (room_id, sequence, sender, kind, body) values (?, 1, 'RETAILER', 'TEXT', '또')
                """, 방)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // 실패한 문장은 PG 트랜잭션을 깨뜨려 다음 문장을 못 쓴다 — 위반 하나에 테스트 하나

    @Test
    void 넘김_줄은_이유가_필수다() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into wholesale.chat_message (room_id, sequence, sender, kind, body) values (?, 1, 'SYSTEM', 'HANDOFF', '전달')
                """, 방)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 넘김_줄이_아니면_이유를_가질_수_없다() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into wholesale.chat_message (room_id, sequence, sender, kind, body, handoff_reason)
                values (?, 1, 'RETAILER', 'TEXT', '글', 'OFF')
                """, 방)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 넘김_이유와_종류는_CHECK가_없어_새_값을_받는다() {
        // 계약: "값이 늘 수 있다". 새 이유·새 종류에 마이그레이션이 필요 없어야 한다 — 막는 건 Java enum 이다
        jdbc.update("""
                insert into wholesale.chat_message (room_id, sequence, sender, kind, body, handoff_reason)
                values (?, 1, 'SYSTEM', 'HANDOFF', '전달', 'SOME_FUTURE_REASON')
                """, 방);
        jdbc.update("""
                insert into wholesale.chat_message (room_id, sequence, sender, kind, body)
                values (?, 2, 'WHOLESALER', 'SOME_FUTURE_KIND', '새 줄')
                """, 방);

        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_message where room_id = ?",
                Integer.class, 방)).isEqualTo(2);
    }

    @Test
    void 보낸_쪽은_세_값으로_닫혀_있다() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into wholesale.chat_message (room_id, sequence, sender, kind, body) values (?, 1, 'BOT', 'TEXT', '글')
                """, 방)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 빈_본문은_저장할_수_없다() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into wholesale.chat_message (room_id, sequence, sender, kind, body) values (?, 1, 'RETAILER', 'TEXT', '')
                """, 방)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 도매_읽음_위치는_방의_마지막_sequence를_넘을_수_없다() {
        assertThatThrownBy(() -> ChatFixture.도매가_읽는다(jdbc, 방, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 소매_읽음_위치는_방의_마지막_sequence를_넘을_수_없다() {
        assertThatThrownBy(() -> ChatFixture.소매가_읽는다(jdbc, 방, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
