package com.ondo.retail.order;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 접수 대기함 설정과 워커를 켠다 (MUL-139 · MUL-140).
 *
 * <p>{@code @EnableScheduling} 이 이 프로젝트에 처음 들어온다. 워커는 아무도 안 불러도
 * 스스로 도는 코드라 스케줄러가 있어야 한다.
 *
 * <p>워커는 소매 앱 안에서 돈다. 태스크가 여럿이면 워커도 그만큼 생기는데, 같은 줄을
 * 두 번 보내지 않는 건 {@code SKIP LOCKED} 가 맡는다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OrderDispatchProperties.class)
public class OrderDispatchConfig {
}
