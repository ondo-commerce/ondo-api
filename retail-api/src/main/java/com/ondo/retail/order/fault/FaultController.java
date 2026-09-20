package com.ondo.retail.order.fault;

import com.ondo.retail.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 측정용 장애 주입 스위치 (MUL-142).
 *
 * <p>부하를 거는 도중에 도매를 죽이고 살리려고 둔다. 재시도 정책을 비교하려면 세 번을
 * <b>같은 조건</b>에서 돌려야 하는데, 앱을 다시 띄우면 조건이 매번 달라진다.
 *
 * <p><b>막는 장치가 셋이다.</b>
 *
 * <ul>
 *   <li>{@code @Profile("!deploy")} — 배포에는 이 빈이 아예 안 생긴다
 *   <li>다른 API 와 같은 인증·승인 게이트를 지난다 (별도 예외를 두지 않았다)
 *   <li>기본값이 0 이라 켜지 않으면 아무 일도 안 한다
 * </ul>
 */
@Tag(name = "측정용 장애 주입", description = "로컬 측정 전용. 배포에는 없다.")
@RestController
@RequestMapping("/api/retail/dev/fault")
@RequiredArgsConstructor
@Profile("!deploy")
public class FaultController {

    private final FaultSwitch faults;

    @Operation(summary = "장애 주입 비율 조회")
    @GetMapping
    public ApiResponse<Status> status() {
        return ApiResponse.of(new Status(faults.failRate(), faults.blockedCount()));
    }

    /**
     * @param failRate 0 이면 도매가 멀쩡하고 1 이면 통째로 죽은 것과 같다.
     *                 0.5 는 살아는 있는데 불안정한 상태다
     */
    @Operation(summary = "장애 주입 비율 변경",
               description = "0 = 정상 · 0.5 = 불안정 · 1 = 죽음. 돌아가는 중에 바꿀 수 있다.")
    @PostMapping
    public ApiResponse<Status> set(@RequestParam double failRate) {
        faults.set(failRate);
        return ApiResponse.of(new Status(faults.failRate(), faults.blockedCount()));
    }

    /**
     * @param failRate 지금 걸려 있는 비율
     * @param blocked  주입으로 막은 접수 호출 누적 수
     */
    public record Status(double failRate, long blocked) {}
}
