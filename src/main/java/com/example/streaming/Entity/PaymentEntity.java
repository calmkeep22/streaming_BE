package com.example.streaming.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_order_id", columnNames = "order_id"),
                @UniqueConstraint(name = "uk_payment_provider_key", columnNames = "provider_payment_key")
        },
        // 복구 작업이 "오래된 PROCESSING"을 찾는 쿼리용. 결제 테이블이 커져도 전체를 훑지 않게 한다
        indexes = @Index(name = "idx_payment_status_processing_started_at", columnList = "status, processing_started_at")
)
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 우리 서버에서 생성한 결제 주문번호이자 중복 요청을 구분하는 멱등성 기준
    @Column(name = "order_id", nullable = false, updatable = false, length = 100)
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private SeatEntity seat;

    // 결제 성공과 같은 트랜잭션에서 생성된 최종 예약
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", unique = true)
    private ReservationEntity reservation;

    // KRW 원 단위 정수. 부동소수점 타입은 금액 계산에 사용하지 않는다.
    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_payment_key", length = 200)
    private String providerPaymentKey;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    // 승인 처리를 시작한 시각. 주문 생성(requestedAt) 후 결제창에 머문 시간이 길 수 있어서,
    // 복구 작업의 유예 시간은 이 시각을 기준으로 잰다
    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime failedAt;

    // 복구 작업이 이 결제를 처리하다 실패한 횟수. 최대 횟수를 넘으면 NEEDS_REVIEW로 넘긴다
    @Column(name = "recovery_attempts", nullable = false)
    private int recoveryAttempts;

    // 다음 복구 시도 시각. null이면 바로 시도할 수 있다
    @Column(name = "next_recovery_at")
    private LocalDateTime nextRecoveryAt;

    // 결제 승인 콜백이나 재시도가 동시에 들어왔을 때 상태 변경 충돌을 감지한다.
    @Version
    private Long version;

    public static PaymentEntity ready(
            String orderId,
            UserEntity user,
            SeatEntity seat,
            long amount,
            String provider
    ) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("주문번호는 필수입니다.");
        }
        if (user == null) {
            throw new IllegalArgumentException("결제 사용자는 필수입니다.");
        }
        if (seat == null) {
            throw new IllegalArgumentException("결제 좌석은 필수입니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다.");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("결제 제공자는 필수입니다.");
        }

        PaymentEntity payment = new PaymentEntity();
        payment.orderId = orderId;
        payment.user = user;
        payment.seat = seat;
        payment.amount = amount;
        payment.currency = "KRW";
        payment.provider = provider;
        payment.status = PaymentStatus.READY;
        payment.requestedAt = LocalDateTime.now();
        return payment;
    }

    public void startProcessing() {
        if (status != PaymentStatus.READY) {
            throw new IllegalStateException("결제를 시작할 수 없는 상태입니다.");
        }
        status = PaymentStatus.PROCESSING;
        processingStartedAt = LocalDateTime.now();
    }

    public void succeed(String providerPaymentKey, ReservationEntity reservation) {
        if (status == PaymentStatus.SUCCESS) {
            return;
        }
        if (status != PaymentStatus.READY && status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("결제를 성공 처리할 수 없는 상태입니다.");
        }
        if (providerPaymentKey == null || providerPaymentKey.isBlank()) {
            throw new IllegalArgumentException("결제사 결제 키는 필수입니다.");
        }
        if (reservation == null) {
            throw new IllegalArgumentException("확정 예약은 필수입니다.");
        }

        this.providerPaymentKey = providerPaymentKey;
        this.reservation = reservation;
        this.status = PaymentStatus.SUCCESS;
        this.approvedAt = LocalDateTime.now();
    }

    public void fail(String failureCode, String failureMessage) {
        if (status == PaymentStatus.SUCCESS || status == PaymentStatus.CANCELED) {
            throw new IllegalStateException("완료된 결제는 실패 처리할 수 없습니다.");
        }

        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.failedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (status != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("성공한 결제만 취소할 수 있습니다.");
        }
        status = PaymentStatus.CANCELED;
    }

    /**
     * 결제사 승인은 됐지만 예약으로 이어지지 못해 환불해야 하는 상태로 바꾼다.
     * 결제사 취소는 이 상태를 커밋한 "뒤에" 호출한다. 먼저 상태를 바꿔 두면,
     * 늦게 도착한 요청 스레드가 같은 결제를 SUCCESS로 바꾸는 일을 막을 수 있다 (succeed는 PROCESSING에서만 가능).
     */
    public void requireRefund(String providerPaymentKey, String failureCode, String failureMessage) {
        if (status == PaymentStatus.REFUND_PENDING) {
            return;
        }
        if (status != PaymentStatus.READY && status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("환불 대기로 바꿀 수 없는 상태입니다.");
        }
        if (providerPaymentKey == null || providerPaymentKey.isBlank()) {
            throw new IllegalArgumentException("환불할 결제사 결제 키는 필수입니다.");
        }

        this.providerPaymentKey = providerPaymentKey;
        this.status = PaymentStatus.REFUND_PENDING;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    // 결제사 취소가 끝나 환불이 완료됐다. 사용자 입장에서는 결제 실패다
    public void completeRefund() {
        if (status == PaymentStatus.FAILED) {
            return;
        }
        if (status != PaymentStatus.REFUND_PENDING) {
            throw new IllegalStateException("환불 대기 상태가 아닙니다.");
        }
        this.status = PaymentStatus.FAILED;
        this.failedAt = LocalDateTime.now();
    }

    /**
     * 복구 시도가 실패했을 때 다음 시도 시각을 미룬다 (지수 백오프: base, base*2, base*4 ...).
     * 최대 횟수에 도달하면 더 시도하지 않고 사람이 확인하도록 NEEDS_REVIEW로 바꾼다.
     */
    public void scheduleRecoveryRetry(LocalDateTime now, int maxAttempts, Duration baseBackoff) {
        if (status != PaymentStatus.PROCESSING && status != PaymentStatus.REFUND_PENDING) {
            throw new IllegalStateException("복구 대상 상태가 아닙니다.");
        }

        recoveryAttempts++;
        if (recoveryAttempts >= maxAttempts) {
            status = PaymentStatus.NEEDS_REVIEW;
            nextRecoveryAt = null;
            return;
        }
        nextRecoveryAt = now.plus(baseBackoff.multipliedBy(1L << (recoveryAttempts - 1)));
    }
}
