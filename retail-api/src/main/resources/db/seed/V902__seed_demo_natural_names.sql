-- ═══════════════════════════════════════════════════════════════
--  시연용 — 소매처 상호를 자연스럽게 다듬고 시연 계정 장바구니를 비운다
--
--  상호가 「별빛의류 · 최별빛」처럼 시드 티가 났다. 봄봄상회는 발표 슬라이드에 있어 그대로 둔다.
--  도매 DB 의 상호 사본(거래처, 채팅방)은 도매 V905 가 같은 표로 바꾼다.
--  장바구니는 시연 첫 장면이 「담고 주문하기」라 비워 둬야 한다.
--
--  ⚠️ 개발 환경 전용(db/seed). 운영에는 안 들어간다.
-- ═══════════════════════════════════════════════════════════════

UPDATE retail.retailer r
   SET shop_name = v.new_name
  FROM (VALUES
    ('byeolbit@ondo.test', '별빛의류', '체리샵'),
    ('hana@ondo.test', '하나패션', '더데일리'),
    ('miso@ondo.test', '미소상회', '모모'),
    ('jinju@ondo.test', '진주의류', '유어룸'),
    ('onyu@ondo.test', '온유패션', '로라앤코'),
    ('pending@ondo.test', '대기상회', '수수'),
    ('rejected@ondo.test', '반려상회', '하이룸')
  ) AS v(email, old_name, new_name)
 WHERE r.email = v.email AND r.shop_name = v.old_name;

DELETE FROM retail.cart_item
 WHERE retailer_id = (SELECT id FROM retail.retailer WHERE email = 'bombom@ondo.test');
