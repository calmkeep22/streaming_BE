package com.example.streaming.Config;

import com.example.streaming.Service.PaymentRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

/**
 * 결제 복구 작업을 주기적으로 실행한다. 로직은 PaymentRecoveryService에 두고 여기서는 부르기만 한다.
 * 그래야 테스트에서 시간을 기다리지 않고 서비스 메서드를 직접 호출할 수 있다.
 *
 * fixedDelay: 이전 실행이 "끝난 뒤"부터 간격을 잰다. fixedRate와 달리 실행이 겹치지 않는다.
 * 서버가 여러 대가 되면 각 서버에서 동시에 돌므로 ShedLock 같은 분산 락이 필요하다
 * (지금도 상태 변경은 조건부라 중복 처리는 막히지만, 결제사 조회가 중복으로 나간다).
 */
@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty(name = "payment.recovery.enabled", matchIfMissing = true)
public class PaymentRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryScheduler.class);

    private final PaymentRecoveryService paymentRecoveryService;

    public PaymentRecoveryScheduler(PaymentRecoveryService paymentRecoveryService) {
        this.paymentRecoveryService = paymentRecoveryService;
    }

    @Scheduled(
            initialDelayString = "${payment.recovery.initial-delay:PT1M}",
            fixedDelayString = "${payment.recovery.interval:PT1M}"
    )
    public void recover() {
        int processed = paymentRecoveryService.recoverDue(LocalDateTime.now());
        if (processed > 0) {
            log.info("결제 복구 대상 {}건 처리", processed);
        }
    }
}
