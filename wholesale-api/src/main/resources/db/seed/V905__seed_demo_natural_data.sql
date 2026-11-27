-- ═══════════════════════════════════════════════════════════════
--  시연용 — 상호, 대표자, 계좌, 시장 상품, 사진을 자연스럽게 다듬는다
--
--  최종 발표(11/28) 시연 화면에서 시드 티가 났다. 대표자명이 상호에서 나왔고(김무드, 이라온),
--  계좌번호가 줄지어 있었고(101-0101-0001), 상품 690건은 틀로 찍은 이름이었고(크롭 크롭 가디건),
--  사진은 해외 쇼핑몰 데이터셋을 여러 상품이 돌려 썼다.
--
--  지우고 다시 넣지 않고 덮어쓴다. 아이디, 로그인 이메일, 주문, 미송, 정산 기록과
--  시연 옵션 상태(빈티지 플라워 셔츠 레드 M 등)를 그대로 두기 위해서다.
--
--  1) 도매처 19곳 상호와 20곳 대표자, 예금주, 계좌번호 (무드온 상호는 발표 슬라이드에 있어 유지)
--  2) 이름 사본: 거래처 · 채팅방의 소매 상호, 지난 알림 제목
--  3) 무드온-봄봄상회 방(시연 방)의 점검 메시지를 지운다
--  4) 시장에는 60개만 남긴다(무드온 22 + 나머지 19곳 2개씩). 나머지 시드 게시글은
--     SEASON_ENDED 로 내린다 — 지우지 않아서 다시 ON_SALE 로 올릴 수 있다
--  5) 남긴 60개의 사진을 상품마다 다른 실사 사진으로 바꾼다. Unsplash 무료 사진(Unsplash License)을
--     images.unsplash.com 주소로 건다. AI 로 만든 사진과 실제 도매처 사진은 쓰지 않았다
--
--  ⚠️ 개발 환경 전용(db/seed). 운영에는 안 들어간다.
-- ═══════════════════════════════════════════════════════════════

-- 1) 도매처 상호와 대표자
UPDATE wholesale.wholesaler w
   SET biz_name = v.name, biz_owner_name = v.owner, updated_at = now()
  FROM (VALUES
    (101, '무드온', '김지현'),
    (102, '미뉴', '이승희'),
    (103, '제이앤케이', '박성훈'),
    (104, '벨라로사', '최은정'),
    (105, '하루', '정민아'),
    (106, '블랙빈', '한상우'),
    (107, '데일리맨', '오준혁'),
    (108, '모아', '유혜진'),
    (109, '엘린', '강수진'),
    (110, '써니데이', '윤지영'),
    (111, '루시앤', '김소연'),
    (112, '보니', '이다은'),
    (113, '스칼렛', '박서영'),
    (114, '밀크티', '정유나'),
    (115, '디엠', '최현주'),
    (116, '그레이스', '한예린'),
    (117, '에이투', '오미경'),
    (118, '투엔티', '유태민'),
    (119, '맨즈데이', '강동현'),
    (120, '노바', '윤성민')
  ) AS v(id, name, owner)
 WHERE w.id = v.id;

-- 계좌는 시드가 넣은 줄(예금주가 옛 대표자인 줄)만 바꾼다. 사람이 화면에서 등록한 계좌는 건드리지 않는다
UPDATE wholesale.bank_account b
   SET account_holder = v.owner, account_no = v.account_no, memo = '주거래'
  FROM (VALUES
    (101, '김무드', '김지현', '452101-04-238817'),
    (102, '이라온', '이승희', '110-482-519203'),
    (103, '박코튼', '박성훈', '1002-851-447210'),
    (104, '최블랑', '최은정', '391-910284-17207'),
    (105, '정데이', '정민아', '527801-01-394052'),
    (106, '한모노', '한상우', '110-371-806624'),
    (107, '오어반', '오준혁', '1002-364-917503'),
    (108, '유린넨', '유혜진', '027-118392-01-014'),
    (109, '강니트', '강수진', '302-1847-5521-61'),
    (110, '윤소소', '윤지영', '3333-08-4471925'),
    (111, '김루미', '김소연', '610401-01-772318'),
    (112, '이하얀', '이다은', '110-529-340871'),
    (113, '박블룸', '박서영', '248-910571-33807'),
    (114, '정멜로', '정유나', '1002-547-382016'),
    (115, '최데이', '최현주', '461-052817-01-019'),
    (116, '한서울', '한예린', '352-0918-2264-13'),
    (117, '오온돌', '오미경', '3333-14-9028416'),
    (118, '유젠틀', '유태민', '937202-00-145586'),
    (119, '강베이', '강동현', '110-205-773914'),
    (120, '윤스트', '윤성민', '620-910133-48105')
  ) AS v(wholesaler_id, old_holder, owner, account_no)
 WHERE b.wholesaler_id = v.wholesaler_id AND b.account_holder = v.old_holder;

-- 2) 소매 상호 사본 (소매 DB 의 상호는 소매 V902 가 바꾼다)
UPDATE wholesale.partner p
   SET retailer_name = v.new_name
  FROM (VALUES
    ('별빛의류', '체리샵'),
    ('하나패션', '더데일리'),
    ('미소상회', '모모'),
    ('진주의류', '유어룸'),
    ('온유패션', '로라앤코'),
    ('대기상회', '수수'),
    ('반려상회', '하이룸')
  ) AS v(old_name, new_name)
 WHERE p.retailer_name = v.old_name;

UPDATE wholesale.chat_room c
   SET retailer_name = v.new_name
  FROM (VALUES
    ('별빛의류', '체리샵'),
    ('하나패션', '더데일리'),
    ('미소상회', '모모'),
    ('진주의류', '유어룸'),
    ('온유패션', '로라앤코'),
    ('대기상회', '수수'),
    ('반려상회', '하이룸')
  ) AS v(old_name, new_name)
 WHERE c.retailer_name = v.old_name;

-- 지난 알림 제목은 이름으로 시작한다(「봄봄상회 새 주문」, 「무드온 미송 입고일 변경」). 앞자리만 바꾼다
UPDATE wholesale.notification n
   SET title = v.new_name || substr(n.title, char_length(v.old_name) + 1)
  FROM (VALUES
    ('별빛의류', '체리샵'),
    ('하나패션', '더데일리'),
    ('미소상회', '모모'),
    ('진주의류', '유어룸'),
    ('온유패션', '로라앤코'),
    ('대기상회', '수수'),
    ('반려상회', '하이룸')
  ) AS v(old_name, new_name)
 WHERE n.title LIKE v.old_name || ' %';

UPDATE wholesale.retail_notification n
   SET title = v.new_name || substr(n.title, char_length(v.old_name) + 1)
  FROM (VALUES
    ('라온', '미뉴'),
    ('코튼클럽', '제이앤케이'),
    ('블랑', '벨라로사'),
    ('데이지', '하루'),
    ('모노톤', '블랙빈'),
    ('어반핏', '데일리맨'),
    ('린넨하우스', '모아'),
    ('니트공방', '엘린'),
    ('소소한', '써니데이'),
    ('루미', '루시앤'),
    ('하얀', '보니'),
    ('블룸', '스칼렛'),
    ('멜로우', '밀크티'),
    ('데이오프', '디엠'),
    ('서울걸', '그레이스'),
    ('온돌', '에이투'),
    ('젠틀맨즈', '투엔티'),
    ('베이스캠프', '맨즈데이'),
    ('스트릿랩', '노바')
  ) AS v(old_name, new_name)
 WHERE n.title LIKE v.old_name || ' %';

-- 3) 시연 방(무드온 101 - 봄봄상회 소매 id 1)의 지난 메시지를 지운다. 순서가 FK 순서다
--    방 순번(last_sequence)은 그대로 둔다. 다음 글은 그 다음 번호로 이어진다
UPDATE wholesale.chat_room
   SET last_message_id = NULL, open_handoff_sequence = NULL,
       wholesaler_last_read_sequence = last_sequence, retailer_last_read_sequence = last_sequence,
       context_listing_id = NULL, context_at = NULL, updated_at = now()
 WHERE wholesaler_id = 101 AND retailer_id = 1;

DELETE FROM wholesale.chat_send_idempotency
 WHERE room_id IN (SELECT id FROM wholesale.chat_room WHERE wholesaler_id = 101 AND retailer_id = 1);

DELETE FROM wholesale.chat_message
 WHERE room_id IN (SELECT id FROM wholesale.chat_room WHERE wholesaler_id = 101 AND retailer_id = 1);

-- 4) 시장에 남길 60개 밖의 시드 게시글을 내린다. 시드 상품 id 범위(1001~2189) 안만 본다
UPDATE wholesale.listing
   SET status = 'SEASON_ENDED', season_ended_at = now(), updated_at = now()
 WHERE status = 'ON_SALE' AND deleted_at IS NULL
   AND product_id BETWEEN 1001 AND 2189
   AND product_id NOT IN (1001, 1002, 1003, 1101, 1102, 1103, 1104, 1105, 1106, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115, 1116, 1117, 1118, 1119, 1120, 1124, 1142, 1145, 1164, 1165, 1198, 1202, 1233, 1236, 1267, 1268, 1305, 1306, 1337, 1338, 1371, 1374, 1501, 1506, 1569, 1570, 1638, 1644, 1707, 1708, 1777, 1778, 1847, 1850, 1916, 1917, 1984, 1986, 2052, 2054, 2122, 2124);

-- 남긴 상품 중 틀로 만든 이름만 고친다. 상품명과 게시글 이름을 같이 바꾼다
UPDATE wholesale.product p
   SET name = v.name
  FROM (VALUES
    (1164, '꽈배기 니트 베스트'),
    (1165, '셔링 소매 블라우스'),
    (1198, '세미와이드 슬랙스'),
    (1202, '스퀘어넥 셔링 원피스'),
    (1236, '크롭 일자 슬랙스'),
    (1268, '스트링 후드티'),
    (1305, '꽈배기 롱 가디건'),
    (1306, '린넨 루즈 셔츠'),
    (1337, 'A라인 미니 스커트'),
    (1338, '린넨 슬림 셔츠'),
    (1371, '생지 와이드 데님'),
    (1374, '셔링 블라우스'),
    (1501, '플리츠 롱 스커트'),
    (1506, '라운드 오버핏 가디건'),
    (1569, '플리츠 미디 스커트'),
    (1570, '셔링 넥 블라우스'),
    (1638, '옥스포드 셔츠'),
    (1644, '새틴 캐미솔'),
    (1707, '라운드 반팔 티'),
    (1708, '랩 롱 원피스'),
    (1777, '기모 맨투맨'),
    (1778, '브이넥 가디건'),
    (1847, '기모 라운드 맨투맨'),
    (1850, '핀턱 와이드 슬랙스'),
    (1916, '트임 롱 스커트'),
    (1917, '골지 슬리브리스'),
    (1984, '케이블 라운드 니트'),
    (1986, 'MA-1 항공 점퍼'),
    (2052, '봄버 재킷'),
    (2054, '스트레이트 데님 팬츠'),
    (2122, '헤비코튼 반팔 티'),
    (2124, '옥스포드 버튼다운 셔츠')
  ) AS v(id, name)
 WHERE p.id = v.id;

UPDATE wholesale.listing l
   SET title = v.name, updated_at = now()
  FROM (VALUES
    (1164, '꽈배기 니트 베스트'),
    (1165, '셔링 소매 블라우스'),
    (1198, '세미와이드 슬랙스'),
    (1202, '스퀘어넥 셔링 원피스'),
    (1236, '크롭 일자 슬랙스'),
    (1268, '스트링 후드티'),
    (1305, '꽈배기 롱 가디건'),
    (1306, '린넨 루즈 셔츠'),
    (1337, 'A라인 미니 스커트'),
    (1338, '린넨 슬림 셔츠'),
    (1371, '생지 와이드 데님'),
    (1374, '셔링 블라우스'),
    (1501, '플리츠 롱 스커트'),
    (1506, '라운드 오버핏 가디건'),
    (1569, '플리츠 미디 스커트'),
    (1570, '셔링 넥 블라우스'),
    (1638, '옥스포드 셔츠'),
    (1644, '새틴 캐미솔'),
    (1707, '라운드 반팔 티'),
    (1708, '랩 롱 원피스'),
    (1777, '기모 맨투맨'),
    (1778, '브이넥 가디건'),
    (1847, '기모 라운드 맨투맨'),
    (1850, '핀턱 와이드 슬랙스'),
    (1916, '트임 롱 스커트'),
    (1917, '골지 슬리브리스'),
    (1984, '케이블 라운드 니트'),
    (1986, 'MA-1 항공 점퍼'),
    (2052, '봄버 재킷'),
    (2054, '스트레이트 데님 팬츠'),
    (2122, '헤비코튼 반팔 티'),
    (2124, '옥스포드 버튼다운 셔츠')
  ) AS v(product_id, name)
 WHERE l.product_id = v.product_id;

-- 5) 남긴 60개의 사진. 0 번이 대표 사진이다
DELETE FROM wholesale.listing_image
 WHERE listing_id IN (SELECT id FROM wholesale.listing WHERE product_id IN (1001, 1002, 1003, 1101, 1102, 1103, 1104, 1105, 1106, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115, 1116, 1117, 1118, 1119, 1120, 1124, 1142, 1145, 1164, 1165, 1198, 1202, 1233, 1236, 1267, 1268, 1305, 1306, 1337, 1338, 1371, 1374, 1501, 1506, 1569, 1570, 1638, 1644, 1707, 1708, 1777, 1778, 1847, 1850, 1916, 1917, 1984, 1986, 2052, 2054, 2122, 2124));

INSERT INTO wholesale.listing_image (listing_id, url, sort_order) VALUES
    (2001, 'https://images.unsplash.com/photo-1764337593519-c51a77b4fc3d?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2001, 'https://images.unsplash.com/photo-1622780432053-767528938f34?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 1),
    (2002, 'https://images.unsplash.com/photo-1683315565563-f72590773805?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2003, 'https://images.unsplash.com/photo-1683126257977-5142e52acc7e?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2101, 'https://images.unsplash.com/photo-1791391280267-a83f5649e762?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2102, 'https://images.unsplash.com/photo-1713881676551-b16f22ce4719?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2103, 'https://images.unsplash.com/photo-1519763246425-1d41fcb282d6?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2104, 'https://images.unsplash.com/photo-1609505848912-b7c3b8b4beda?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2105, 'https://images.unsplash.com/photo-1599662875272-64de8289f6d8?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2106, 'https://images.unsplash.com/photo-1779153617269-443833a48436?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2107, 'https://images.unsplash.com/photo-1617019114583-affb34d1b3cd?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2108, 'https://images.unsplash.com/photo-1519763457460-88d432c0e076?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2109, 'https://images.unsplash.com/photo-1594201658541-52380e1ca6df?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2110, 'https://images.unsplash.com/photo-1642373202727-6fb657ae9507?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2111, 'https://images.unsplash.com/photo-1630355734650-55fe91e1e5c7?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2112, 'https://images.unsplash.com/photo-1665065952009-a5dc00f423d4?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2113, 'https://images.unsplash.com/photo-1579310962131-aa21f240d986?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2114, 'https://images.unsplash.com/photo-1759229874914-c1ffdb3ebd0c?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2115, 'https://images.unsplash.com/photo-1594739033447-80ab21e24b4c?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2116, 'https://images.unsplash.com/photo-1679555916677-21fc473e4099?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2117, 'https://images.unsplash.com/photo-1764168840124-99f6990b55fb?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2118, 'https://images.unsplash.com/photo-1780765513484-0d78280442bb?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2119, 'https://images.unsplash.com/photo-1779153616974-6278478f9eb2?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2120, 'https://images.unsplash.com/photo-1745218492001-c42adc04c4fe?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2124, 'https://images.unsplash.com/photo-1663573688938-2b3e7ea2ab33?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2142, 'https://images.unsplash.com/photo-1633821879282-0c4e91f96232?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2145, 'https://images.unsplash.com/photo-1543076447-215ad9ba6923?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2164, 'https://images.unsplash.com/photo-1681483476977-322d81693e41?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2165, 'https://images.unsplash.com/photo-1778826971124-c5ee892e266e?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2198, 'https://images.unsplash.com/photo-1687825515654-23620796760c?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2202, 'https://images.unsplash.com/photo-1676218598257-7fda21a7baac?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2233, 'https://images.unsplash.com/photo-1603045720683-5c6840a8eeca?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2236, 'https://images.unsplash.com/photo-1718252540511-e958742e4165?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2267, 'https://images.unsplash.com/photo-1473966968600-fa801b869a1a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2268, 'https://images.unsplash.com/photo-1484663548870-2aa675ba38fb?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2305, 'https://images.unsplash.com/photo-1607477790346-b3ecfa18fe77?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2306, 'https://images.unsplash.com/photo-1752486268221-901251de97f5?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2337, 'https://images.unsplash.com/photo-1739945533087-1f80850e7d86?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2338, 'https://images.unsplash.com/photo-1691053318576-4bf08315e877?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2371, 'https://images.unsplash.com/photo-1725387072845-7431bbc453bc?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2374, 'https://images.unsplash.com/photo-1727742152836-40e0e2186101?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2501, 'https://images.unsplash.com/photo-1789110853398-539d78dd63bc?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2506, 'https://images.unsplash.com/photo-1786198045676-83d37e3fb370?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2569, 'https://images.unsplash.com/photo-1762343038913-2d0b726c291e?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2570, 'https://images.unsplash.com/photo-1767998569883-9251d64d1d53?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2638, 'https://images.unsplash.com/photo-1791391279724-372763ee14b3?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2644, 'https://images.unsplash.com/photo-1594631770635-f2915410b410?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2707, 'https://images.unsplash.com/photo-1666358059751-3accf39c2d95?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2708, 'https://images.unsplash.com/photo-1704775990154-b87562565d4c?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2777, 'https://images.unsplash.com/photo-1676790225864-76c836e7b5d6?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2778, 'https://images.unsplash.com/photo-1663550910347-0ae0b3fb636e?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2847, 'https://images.unsplash.com/photo-1607160199580-1b0c9b736b66?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2850, 'https://images.unsplash.com/photo-1517445312882-bc9910d016b7?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2916, 'https://images.unsplash.com/photo-1591948083708-6edc852d7275?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2917, 'https://images.unsplash.com/photo-1789110855604-b693bd403e43?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2984, 'https://images.unsplash.com/photo-1612629779374-a8432d338af5?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2986, 'https://images.unsplash.com/photo-1624548140129-74786c5f1279?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (3052, 'https://images.unsplash.com/photo-1594587639708-095eb3778067?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (3054, 'https://images.unsplash.com/photo-1754555009601-498e9873197e?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (3122, 'https://images.unsplash.com/photo-1781106476595-ac88cc3695f2?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (3124, 'https://images.unsplash.com/photo-1624835567150-0c530a20d8cc?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0);
