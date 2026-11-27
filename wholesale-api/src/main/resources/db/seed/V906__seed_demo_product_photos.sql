-- ═══════════════════════════════════════════════════════════════
--  시연용 — 사진을 도매에서 실제로 올릴 법한 상품 사진으로 바꾼다
--
--  V905 의 사진은 외국인 모델이 나오는 생활 사진이 많아 도매 시장 같지 않았다.
--  동대문 도매 사진처럼 옷만 찍은 사진(바닥에 펼친 사진, 옷걸이 사진)을 먼저 골랐다.
--  Unsplash 무료 사진(Unsplash License)을 images.unsplash.com 주소로 건다.
--
--  1) 시장에 남긴 60개의 사진을 다시 넣는다
--  2) V905 에서 내린 시드 게시글(SEASON_ENDED)에 남은 해외 데이터셋 사진을 지운다.
--     화면(시장, 검색, 상품, 장바구니)은 판매 중인 게시글만 그려서 보이는 곳은 없다
--
--  ⚠️ 개발 환경 전용(db/seed). 운영에는 안 들어간다.
-- ═══════════════════════════════════════════════════════════════

DELETE FROM wholesale.listing_image
 WHERE listing_id IN (SELECT id FROM wholesale.listing
                       WHERE product_id BETWEEN 1001 AND 2189);

INSERT INTO wholesale.listing_image (listing_id, url, sort_order) VALUES
    (2001, 'https://images.unsplash.com/photo-1651888947765-2e0ec570bc9d?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2001, 'https://images.unsplash.com/photo-1562571151-864223d7d62a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 1),
    (2002, 'https://images.unsplash.com/photo-1683315565563-f72590773805?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2003, 'https://images.unsplash.com/photo-1637069585336-827b298fe84a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2101, 'https://images.unsplash.com/photo-1441035430039-1c421470b6c0?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2102, 'https://images.unsplash.com/photo-1713881587420-113c1c43e28a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2103, 'https://images.unsplash.com/photo-1467043237213-65f2da53396f?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2104, 'https://images.unsplash.com/photo-1755189550602-f9e408fc45dc?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2105, 'https://images.unsplash.com/photo-1657295766073-6be3510e5aa5?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2106, 'https://images.unsplash.com/photo-1557958385-3e26ffe09c9a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2107, 'https://images.unsplash.com/photo-1615206799496-b288ff334d9a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2108, 'https://images.unsplash.com/photo-1569314310775-469add9953f4?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2109, 'https://images.unsplash.com/photo-1729742768394-c9978a06f98d?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2110, 'https://images.unsplash.com/photo-1612889002991-fbfaf775d231?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2111, 'https://images.unsplash.com/photo-1588795904317-2f4ab1a0a852?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2112, 'https://images.unsplash.com/photo-1620900128850-b12749d73093?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2113, 'https://images.unsplash.com/photo-1543076447-215ad9ba6923?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2114, 'https://images.unsplash.com/photo-1631541911232-72bc7448820a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2115, 'https://images.unsplash.com/photo-1584061516874-ed56f46d8e13?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2116, 'https://images.unsplash.com/photo-1621773881532-fe65715b5137?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2117, 'https://images.unsplash.com/photo-1519554318711-aaf73ece6ff9?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2118, 'https://images.unsplash.com/photo-1623658580851-3b25bf83b4ea?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2119, 'https://images.unsplash.com/photo-1707368173483-769cc4180628?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2120, 'https://images.unsplash.com/photo-1649937408746-4d2f603f91c8?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2124, 'https://images.unsplash.com/photo-1655141559697-f927ed68170c?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2142, 'https://images.unsplash.com/photo-1589363360147-4f2d51541551?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2145, 'https://images.unsplash.com/photo-1564438259159-32c208873398?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2164, 'https://images.unsplash.com/photo-1621198059871-0d5f9b449233?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2165, 'https://images.unsplash.com/photo-1777462985111-9da64fb2e6e6?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2198, 'https://images.unsplash.com/photo-1711443813147-def27861b9af?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2202, 'https://images.unsplash.com/photo-1657295766148-6bdd569c663b?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2233, 'https://images.unsplash.com/photo-1773747203071-81d53e35c41a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2236, 'https://images.unsplash.com/photo-1529720317453-c8da503f2051?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2267, 'https://images.unsplash.com/photo-1601679249486-3e2a903f23ee?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2268, 'https://images.unsplash.com/photo-1620799140188-3b2a02fd9a77?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2305, 'https://images.unsplash.com/photo-1588676000557-dc67e2d3bd19?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2306, 'https://images.unsplash.com/photo-1713881842156-3d9ef36418cc?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2337, 'https://images.unsplash.com/photo-1715233749622-3216fe49e682?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2338, 'https://images.unsplash.com/photo-1776838103951-993ff1ffc916?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2371, 'https://images.unsplash.com/photo-1662219708541-3a74d96330eb?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2374, 'https://images.unsplash.com/photo-1715859019107-90c16285b149?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2501, 'https://images.unsplash.com/photo-1558358016-7b71c3335829?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2506, 'https://images.unsplash.com/photo-1658418818804-adc6dc129ac2?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2569, 'https://images.unsplash.com/photo-1527332042004-0b1a4d8646a3?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2570, 'https://images.unsplash.com/photo-1594631773033-814817f000e8?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2638, 'https://images.unsplash.com/photo-1732605559386-bc59426d1b16?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2644, 'https://images.unsplash.com/photo-1594631770635-f2915410b410?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2707, 'https://images.unsplash.com/photo-1651761179569-4ba2aa054997?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2708, 'https://images.unsplash.com/photo-1687380330652-7badcb8f44c3?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2777, 'https://images.unsplash.com/photo-1523380677598-64d85d015339?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2778, 'https://images.unsplash.com/photo-1670080514836-2a007ec86f6a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2847, 'https://images.unsplash.com/photo-1607160199580-1b0c9b736b66?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2850, 'https://images.unsplash.com/photo-1590159983013-d4ff5fc71c1d?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2916, 'https://images.unsplash.com/photo-1739474019626-526d6d0dbcea?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2917, 'https://images.unsplash.com/photo-1595909315417-2edd382a56dc?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2984, 'https://images.unsplash.com/photo-1544967919-44c1ef2f9e7a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (2986, 'https://images.unsplash.com/photo-1624548140129-74786c5f1279?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (3052, 'https://images.unsplash.com/photo-1583401915223-1a3ee3b57299?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (3054, 'https://images.unsplash.com/photo-1713880453396-aa0493e308ec?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (3122, 'https://images.unsplash.com/photo-1544441892-715e8df46144?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0),
    (3124, 'https://images.unsplash.com/photo-1602810316693-3667c854239a?w=900&h=1200&fit=crop&crop=entropy&q=80&auto=format', 0);
