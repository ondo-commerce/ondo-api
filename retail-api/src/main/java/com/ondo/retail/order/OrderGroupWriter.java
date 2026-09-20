package com.ondo.retail.order;

import com.ondo.retail.cart.CartItemRepository;
import com.ondo.retail.cart.domain.CartItem;
import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.order.domain.OrderGroup;
import com.ondo.retail.order.domain.OrderGroupStatus;
import com.ondo.retail.order.domain.OrderDispatch;
import com.ondo.retail.order.dto.PlaceOrderRequest;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수의 DB 쓰기만 모은다 (MUL-98).
 *
 * <p><b>{@link OrderPlaceService} 와 나눠 둔 이유가 트랜잭션이다.</b> 같은 클래스 안에서
 * 부르면 {@code @Transactional} 이 안 걸린다 — 스프링은 프록시로 가로채는데 자기 호출은
 * 프록시를 안 지난다. 도매 호출을 트랜잭션 밖에 두려면 빈이 갈라져 있어야 한다.
 *
 * <p>그래서 쓰기가 둘로 끊긴다. 그 사이에 도매를 부른다.
 *
 * <pre>
 *   open()    주문서를 만들고 커밋      도매를 부르려면 이 id 가 필요하다
 *   ...       도매 호출 (트랜잭션 밖)
 *   settle()  금액 · 상태 · 장바구니 정리
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderGroupWriter {

    private final OrderGroupRepository orderGroupRepository;
    private final OrderDispatchRepository dispatchRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final CartItemRepository cartItemRepository;
    private final OrderDispatchProperties properties;

    /**
     * 주문서를 연다. 같은 멱등키로 다시 왔으면 있던 걸 이어 쓴다.
     *
     * <p><b>「처음 결과 그대로」는 아직 안 한다</b>(숙제 7번). 실패한 도매처의 사유를
     * 어디에도 안 남겨서 재현할 수가 없다. 대신 부르는 쪽이 <b>도매를 다시 부른다</b> —
     * 이미 받은 곳은 도매가 막고, 실패했던 곳만 새로 들어간다. 데이터는 안 깨지고
     * 결과적으로 재시도가 된다. 다만 응답의 실패 사유는 "이번 시도" 기준이라
     * 처음과 다를 수 있다.
     */
    @Transactional
    public OrderGroup open(Long retailerId, String idempotencyKey, PlaceOrderRequest request,
                           Function<OrderGroup, List<WholesaleOrderCommand>> commands) {
        String requestId = (idempotencyKey == null || idempotencyKey.isBlank())
                // 열쇠를 안 보내면 연타를 막을 방법이 없다. 그래도 접수는 되게 두되
                // 서로 다른 주문으로 본다 — request_id 가 NOT NULL 이라 값은 있어야 한다
                ? "no-key-" + UUID.randomUUID()
                : idempotencyKey;

        OrderGroup group = orderGroupRepository.findByRequestId(requestId)
                .map(existing -> {
                    // 남의 열쇠를 주워 쓰면 남의 주문서에 주문을 붙이게 된다
                    if (!existing.getRetailerId().equals(retailerId)) {
                        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                    }
                    log.info("같은 멱등키로 다시 왔다. 도매를 다시 부른다. orderId={}", existing.getId());
                    return existing;
                })
                .orElseGet(() -> create(retailerId, requestId, request));

        enqueue(group, commands.apply(group));
        return group;
    }

    /**
     * 도매에 보낼 명령을 대기함에 넣는다 (MUL-139).
     *
     * <p><b>실패했을 때가 아니라 지금 넣는다.</b> 주문서와 같은 트랜잭션이다. 도매를
     * 부르는 도중에 태스크가 내려가면 "실패를 감지해 기록" 할 주체가 없어 주문 의사가
     * 통째로 사라진다. 여기서 같이 써 두면 둘 다 되거나 둘 다 안 된다.
     *
     * <p>같은 멱등키로 다시 온 경우에는 이미 줄이 있다. 그대로 둔다 — 시도 횟수와
     * 기한은 처음 눌렀을 때 기준이어야 한다. 기한이 갱신되면 다시 누를 때마다 무한정
     * 늘어나 기한을 둔 뜻이 없어진다.
     */
    private void enqueue(OrderGroup group, List<WholesaleOrderCommand> commands) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = DispatchDeadline.of(group.getOrderedAt(), properties.maxWait());

        for (WholesaleOrderCommand command : commands) {
            if (dispatchRepository.findByOrderGroupIdAndWholesalerId(
                    group.getId(), command.wholesalerId()).isPresent()) {
                continue;
            }
            dispatchRepository.save(OrderDispatch.builder()
                    .orderGroupId(group.getId())
                    .wholesalerId(command.wholesalerId())
                    .payload(command)
                    // 지금 바로 동기로 부른다. 그게 실패하면 워커가 이 줄을 집는다
                    .nextAttemptAt(now)
                    .expiresAt(expiresAt)
                    .build());
        }
    }

    /**
     * 동기 호출의 결과를 대기함에 반영한다 (MUL-139).
     *
     * <p>세 갈래다. 갈라두지 않으면 안 될 일을 계속 두드리게 된다.
     *
     * <ul>
     *   <li><b>접수됨</b> — {@code SENT}. 끝이다
     *   <li><b>도매가 거절</b> — {@code REJECTED}. 재고 부족·판매 종료는 다시 해도 같은
     *       답이 오고, 사용자는 지금 알아야 한다
     *   <li><b>도매가 안 뜸</b> — {@code PENDING} 그대로 둔다. 워커가 집어간다
     * </ul>
     */
    @Transactional
    public void recordAttempt(Long orderGroupId, Long wholesalerId, WholesaleOrderReceipt receipt) {
        dispatchRepository.findByOrderGroupIdAndWholesalerId(orderGroupId, wholesalerId)
                .ifPresent(dispatch -> {
                    if (receipt.accepted()) {
                        dispatch.markSent();
                    } else if (!receipt.retryable()) {
                        dispatch.markRejected(receipt.reason());
                    } else {
                        // PENDING 그대로. 시도 횟수만 올리고 워커에게 넘긴다
                        dispatch.retryAt(dispatch.getNextAttemptAt(), receipt.reason());
                    }
                });
    }

    /**
     * 새 주문서를 만든다.
     *
     * <p>{@code UNIQUE(request_id)} 위반을 잡아 다시 읽는 이유 — <b>진짜 연타</b>는 두 요청이
     * 거의 동시에 온다. 둘 다 위에서 "없다" 를 보고 둘 다 만들려 들면 하나가 제약에 걸려
     * 500 이 난다. 연타를 막으라고 둔 열쇠가 연타 때 터지는 셈이다.
     */
    private OrderGroup create(Long retailerId, String requestId, PlaceOrderRequest request) {
        OffsetDateTime orderedAt = OffsetDateTime.now();
        try {
            return orderGroupRepository.saveAndFlush(OrderGroup.builder()
                    .retailerId(retailerId)
                    .requestId(requestId)
                    .orderNo(orderNoGenerator.next(orderedAt))
                    .agentName(request.agentName())
                    .agentPhone(request.agentPhone())
                    .orderedAt(orderedAt)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.info("같은 멱등키가 동시에 들어왔다. 먼저 만든 주문서를 쓴다. requestId={}", requestId);
            return orderGroupRepository.findByRequestId(requestId)
                    .orElseThrow(() -> e);
        }
    }

    /** 이미 접수까지 끝난 주문서인지. 연타의 두 번째 요청을 여기서 끊는다. */
    @Transactional(readOnly = true)
    public java.util.Optional<OrderGroup> findAccepted(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return java.util.Optional.empty();
        }
        return orderGroupRepository.findByRequestId(idempotencyKey)
                .filter(g -> g.getStatus() == OrderGroupStatus.ACCEPTED);
    }

    /**
     * 도매 결과를 주문서에 반영한다.
     *
     * <p><b>장바구니에서 빼는 기준이 바뀌었다</b> (MUL-141). 전에는 접수된 줄만 뺐다 —
     * 실패한 줄은 남겨야 사용자가 다시 누를 수 있었다. 이제는 서버가 대신 보내므로
     * <b>서버가 맡은 줄도 뺀다.</b> 안 빼면 사용자와 서버가 각자 주문해 같은 물건이
     * 두 번 들어간다. 도매의 멱등 제약은 이걸 못 막는다 — 사용자가 다시 누른 건
     * 주문서가 달라서 서로 다른 주문으로 보인다.
     *
     * <p>맡은 줄은 서버가 손을 뗄 때 돌려준다({@link DispatchCartReturn}).
     * 재고 부족처럼 다시 해도 소용없는 거절만 지금 그대로 남는다.
     *
     * @param acceptedCartItems 접수된 줄. 영영 빠진다
     * @param pendingCartItems  서버가 맡은 줄. 포기·취소하면 돌아온다
     * @param anyAccepted       한 곳이라도 받아졌는지. 하나도 없으면 {@code FAILED} 로 남긴다.
     *                          지우지 않는 건 왜 실패했는지 남기고 멱등키를 살리기 위해서다
     */
    @Transactional
    public OrderGroup settle(Long orderGroupId, long acceptedAmount, boolean anyAccepted,
                             List<CartItem> acceptedCartItems, List<CartItem> pendingCartItems) {
        OrderGroup group = orderGroupRepository.findById(orderGroupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));

        group.settle(acceptedAmount, anyAccepted);
        if (!acceptedCartItems.isEmpty()) {
            cartItemRepository.deleteAll(acceptedCartItems);
        }
        if (!pendingCartItems.isEmpty()) {
            cartItemRepository.deleteAll(pendingCartItems);
        }
        return group;
    }
}
