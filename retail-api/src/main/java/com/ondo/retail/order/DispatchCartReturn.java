package com.ondo.retail.order;

import com.ondo.retail.cart.CartItemRepository;
import com.ondo.retail.cart.domain.CartItem;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 서버가 손을 뗀 주문을 장바구니로 돌려놓는다 (MUL-141).
 *
 * <p><b>왜 필요한가</b> — 대기함이 맡은 줄은 장바구니에서 뺀다. 안 빼면 사장님과 서버가
 * 각자 주문해 같은 물건이 두 번 들어간다. 그런데 빼놓기만 하고 끝나면 서버가 포기했을 때
 * 물건이 증발한다. 맡았으면 돌려줄 책임도 같이 진다.
 *
 * <p>돌려주는 경우는 <b>성공 말고 전부</b>다 — 기한 만료 · 재시도 소진 · 도매 거절 ·
 * 사장님 취소. 접수된 것만 장바구니에서 영영 빠진다.
 *
 * <p>무엇을 돌려줄지는 굳혀 둔 명령에서 읽는다. 장바구니 줄은 이미 지워졌고 그때 담았던
 * 수량을 아는 건 이 명령뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchCartReturn {

    private final CartItemRepository cartItemRepository;

    /**
     * 명령에 담긴 줄을 장바구니로 되돌린다.
     *
     * <p><b>합친다, 덮어쓰지 않는다.</b> 기다리는 사이 사장님이 같은 옵션을 새로 담았을 수
     * 있다. 덮어쓰면 새로 담은 게 사라지고, 그냥 넣으면 {@code UNIQUE (retailer_id,
     * variant_id)} 에 걸려 터진다.
     */
    public void restore(WholesaleOrderCommand command, String reason) {
        Long retailerId = command.retailerId();

        for (WholesaleOrderCommand.Line line : command.items()) {
            cartItemRepository.findByRetailerIdAndVariantId(retailerId, line.variantId())
                    .ifPresentOrElse(
                            existing -> existing.addQty(line.qty()),
                            () -> cartItemRepository.save(
                                    CartItem.of(retailerId, line.variantId(), line.qty())));
        }

        log.info("대기 주문을 장바구니로 돌려놨다. 소매처={} 도매처={} 줄={} 사유={}",
                retailerId, command.wholesalerId(), command.items().size(), reason);
    }
}
