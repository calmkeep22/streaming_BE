package com.example.streaming.Service;

import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Exception.PaymentGatewayException;
import com.example.streaming.Repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 결과가 확정되지 않은 결제(PROCESSING)와 환불이 남은 결제(REFUND_PENDING)를 결제사 상태와 맞춘다.
 *
 * 우리 DB와 결제사는 한 트랜잭션으로 묶을 수 없어서, 서버가 죽거나 응답이 유실되면 둘이 어긋난다.
 * 돈에 대해서는 결제사가 진실의 원천이므로, 결제사에 실제 상태를 물어보고 우리 DB를 거기에 맞춘다.
 *
 * 판단 표 (우리 PROCESSING 기준)
 *   결제사 NOT_FOUND    → 돈이 안 빠졌다. FAILED
 *   결제사 CANCELED     → 이미 취소됐다. FAILED
 *   결제사 IN_PROGRESS  → 아직 모른다. 나중에 다시
 *   결제사 APPROVED     → 돈이 빠졌는데 예약이 없다. 환불한다
 *                         (복구 시점엔 좌석 선점이 만료됐을 가능성이 높아, 예약을 만들지 않고 환불하는 정책)
 *
 * 규칙
 * - 결제사를 호출하는 동안 DB 트랜잭션을 열지 않는다 (PaymentService와 같은 원칙)
 * - 상태는 "기대한 상태일 때만" 바꾼다. 다른 쪽(요청 스레드, 다른 복구 실행)이 먼저 바꿨으면 건너뛴다
 * - 결제사 호출이 실패하면 지수 백오프로 재시도하고, 최대 횟수를 넘으면 NEEDS_REVIEW로 넘긴다
 */
@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    private final Duration gracePeriod;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final int batchSize;

    public PaymentRecoveryService(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            // 결제사 호출 타임아웃보다 충분히 길어야 한다. 짧으면 아직 처리 중인 결제를 건드린다
            @Value("${payment.recovery.grace-period:PT10M}") Duration gracePeriod,
            @Value("${payment.recovery.max-attempts:5}") int maxAttempts,
            @Value("${payment.recovery.base-backoff:PT1M}") Duration baseBackoff,
            @Value("${payment.recovery.batch-size:50}") int batchSize
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
        this.gracePeriod = gracePeriod;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
        this.batchSize = batchSize;
    }

    public enum Result {
        // 다른 쪽이 먼저 처리했거나 복구 대상이 아니다
        SKIPPED,
        // 결제사에 기록이 없거나 이미 취소돼 있어 FAILED로 정리했다
        MARKED_FAILED,
        // 결제사 취소(환불)까지 끝내고 FAILED로 정리했다
        REFUNDED,
        // 이번엔 처리하지 못해 다음 시도를 예약했다
        RETRY_SCHEDULED,
        // 재시도를 다 써서 사람이 확인해야 한다
        NEEDS_REVIEW
    }

    /**
     * 스케줄러의 진입점. now를 인자로 받는 이유는 테스트에서 시간을 마음대로 정하기 위해서다.
     * 결제 하나가 실패해도 나머지는 계속 처리한다.
     */
    public int recoverDue(LocalDateTime now) {
        List<Long> ids = paymentRepository.findRecoveryTargetIds(
                PaymentStatus.PROCESSING,
                PaymentStatus.REFUND_PENDING,
                now.minus(gracePeriod),
                now,
                PageRequest.of(0, batchSize)
        );

        for (Long id : ids) {
            try {
                recover(id, now);
            } catch (RuntimeException e) {
                record(Result.RETRY_SCHEDULED);
                log.error("결제 복구 중 예상하지 못한 오류 paymentId={}", id, e);
            }
        }
        return ids.size();
    }

    /**
     * 결제 하나를 복구한다. 유예 시간·재시도 대기는 대상을 고르는 recoverDue에서 걸러지므로,
     * 이 메서드를 직접 부르면 그 조건 없이 바로 처리한다.
     */
    public Result recover(Long paymentId, LocalDateTime now) {
        Snapshot snapshot = transactionTemplate.execute(status ->
                paymentRepository.findById(paymentId).map(Snapshot::of).orElse(null));

        if (snapshot == null || !snapshot.isRecoveryTarget()) {
            return record(Result.SKIPPED);
        }

        Result result;
        try {
            result = snapshot.status() == PaymentStatus.REFUND_PENDING
                    ? refund(snapshot, now)
                    : resolveProcessing(snapshot, now);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 우리가 읽은 뒤 다른 쪽이 상태를 바꿨다. 그쪽 결과를 존중하고 손대지 않는다
            result = Result.SKIPPED;
        }
        return record(result);
    }

    private Result resolveProcessing(Snapshot snapshot, LocalDateTime now) {
        PaymentGateway.PaymentInquiry inquiry;
        try {
            inquiry = paymentGateway.inquire(snapshot.orderId());
        } catch (PaymentGatewayException e) {
            log.warn("결제사 상태 조회 실패, 재시도 예약 paymentId={}", snapshot.id(), e);
            return scheduleRetry(snapshot, now);
        }

        return switch (inquiry.status()) {
            case NOT_FOUND -> markFailed(snapshot, "RECOVERY_NOT_APPROVED", "결제사에 승인 기록이 없어 결제를 실패 처리했습니다.");
            case CANCELED -> markFailed(snapshot, "RECOVERY_CANCELED", "결제사에서 이미 취소된 결제입니다.");
            case IN_PROGRESS -> scheduleRetry(snapshot, now);
            case APPROVED -> claimAndRefund(snapshot, inquiry.providerPaymentKey(), now);
        };
    }

    /**
     * 환불하기 전에 먼저 PROCESSING → REFUND_PENDING을 커밋한다 (선점).
     * 이 순서를 지켜야, 결제사 취소를 하는 사이에 요청 스레드가 같은 결제를 SUCCESS로 만드는 일이 없다.
     * 선점에 실패하면(다른 쪽이 먼저 바꿈) 환불하지 않는다.
     */
    private Result claimAndRefund(Snapshot snapshot, String providerPaymentKey, LocalDateTime now) {
        boolean claimed = updateIfStatus(snapshot, PaymentStatus.PROCESSING, payment ->
                payment.requireRefund(providerPaymentKey, "RECOVERY_REFUND", "결제 확인이 지연돼 자동으로 환불했습니다."));
        if (!claimed) {
            return Result.SKIPPED;
        }
        return refund(snapshot.withRefundPending(providerPaymentKey), now);
    }

    private Result refund(Snapshot snapshot, LocalDateTime now) {
        try {
            // 결제사 cancel은 멱등이어야 한다. 이전 시도에서 취소는 됐는데 상태 저장만 실패했어도 안전하다
            paymentGateway.cancel(snapshot.providerPaymentKey(), "결제 복구 환불");
        } catch (PaymentGatewayException e) {
            log.warn("결제사 환불 실패, 재시도 예약 paymentId={}", snapshot.id(), e);
            return scheduleRetry(snapshot, now);
        }

        boolean updated = updateIfStatus(snapshot, PaymentStatus.REFUND_PENDING, PaymentEntity::completeRefund);
        return updated ? Result.REFUNDED : Result.SKIPPED;
    }

    private Result markFailed(Snapshot snapshot, String code, String message) {
        boolean updated = updateIfStatus(snapshot, PaymentStatus.PROCESSING, payment -> payment.fail(code, message));
        return updated ? Result.MARKED_FAILED : Result.SKIPPED;
    }

    private Result scheduleRetry(Snapshot snapshot, LocalDateTime now) {
        Optional<PaymentStatus> after = changeIfStatus(snapshot, snapshot.status(),
                payment -> payment.scheduleRecoveryRetry(now, maxAttempts, baseBackoff));
        if (after.isEmpty()) {
            return Result.SKIPPED;
        }
        if (after.get() == PaymentStatus.NEEDS_REVIEW) {
            log.error("결제 복구 재시도 한도 초과, 수동 확인 필요 paymentId={}, orderId={}",
                    snapshot.id(), snapshot.orderId());
            return Result.NEEDS_REVIEW;
        }
        return Result.RETRY_SCHEDULED;
    }

    private boolean updateIfStatus(Snapshot snapshot, PaymentStatus expected, Consumer<PaymentEntity> change) {
        return changeIfStatus(snapshot, expected, change).isPresent();
    }

    /**
     * 짧은 트랜잭션에서 결제를 다시 읽고, 기대한 상태일 때만 바꾼다. 바꿨으면 바뀐 뒤의 상태를 돌려준다.
     * - 읽어 보니 상태가 이미 다르면 empty (다른 쪽이 처리함)
     * - 읽은 뒤 커밋 전에 다른 쪽이 바꾸면 @Version 충돌 → ObjectOptimisticLockingFailureException
     */
    private Optional<PaymentStatus> changeIfStatus(Snapshot snapshot, PaymentStatus expected, Consumer<PaymentEntity> change) {
        PaymentStatus after = transactionTemplate.execute(status -> {
            PaymentEntity payment = paymentRepository.findById(snapshot.id()).orElse(null);
            if (payment == null || payment.getStatus() != expected) {
                return null;
            }
            change.accept(payment);
            return payment.getStatus();
        });
        return Optional.ofNullable(after);
    }

    private Result record(Result result) {
        meterRegistry.counter("payment.recovery.result", "result", result.name().toLowerCase()).increment();
        return result;
    }

    // 트랜잭션 밖으로 엔티티를 들고 나가지 않으려고, 필요한 값만 복사해 둔다
    private record Snapshot(Long id, String orderId, PaymentStatus status, String providerPaymentKey) {

        static Snapshot of(PaymentEntity payment) {
            return new Snapshot(payment.getId(), payment.getOrderId(), payment.getStatus(), payment.getProviderPaymentKey());
        }

        boolean isRecoveryTarget() {
            return status == PaymentStatus.PROCESSING || status == PaymentStatus.REFUND_PENDING;
        }

        Snapshot withRefundPending(String providerPaymentKey) {
            return new Snapshot(id, orderId, PaymentStatus.REFUND_PENDING, providerPaymentKey);
        }
    }
}
