package com.example.streaming.Service;

import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.PaymentRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import com.example.streaming.Service.PaymentGateway.InquiryStatus;
import com.example.streaming.Service.PaymentRecoveryService.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 결제사와 우리 DB가 어긋나는 상황을 가짜 결제사로 재현하고, 복구 작업이 정리하는지 확인한다.
 * 실제 트랜잭션 경계(조건부 상태 변경, @Version)를 검증해야 하므로 테스트에 @Transactional을 걸지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "payment.recovery.grace-period=PT10M",
        "payment.recovery.max-attempts=3",
        "payment.recovery.base-backoff=PT1M"
})
class PaymentRecoveryServiceTest {

    @Autowired
    PaymentRecoveryService recoveryService;
    @Autowired
    PaymentService paymentService;
    @Autowired
    ReservationService reservationService;
    @Autowired
    FakePaymentGateway fakeGateway;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    ReservationRepository reservationRepository;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    SeatRepository seatRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    JdbcTemplate jdbcTemplate;

    // 결제 흐름(pay)은 Redis 선점을 확인하므로, 테스트에서 선점 결과를 정할 수 있게 바꿔 둔다
    @MockitoBean
    RedisSeatHoldService redisSeatHoldService;

    UserEntity user;
    SeatEntity seat;
    // 유예 시간(10분)이 지난 시점
    LocalDateTime later;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        fakeGateway.reset();

        user = userRepository.save(new UserEntity(UUID.randomUUID() + "@test.com", "password"));
        EventEntity event = eventRepository.save(new EventEntity("콘서트", LocalDateTime.now().plusDays(7)));
        seat = seatRepository.save(new SeatEntity(event, 1));
        later = LocalDateTime.now().plusMinutes(11);
    }

    @AfterEach
    void tearDown() {
        fakeGateway.reset();
        // 결제가 예약을 참조하므로, 같은 DB를 쓰는 다른 테스트가 예약을 지울 수 있게 정리한다
        paymentRepository.deleteAll();
    }

    // ---------- 판단 표 ----------

    @Test
    @DisplayName("결제사에 기록이 없으면(서버가 승인 요청 전에 죽음) FAILED로 정리한다")
    void notFoundAtProvider() {
        PaymentEntity payment = saveProcessing("order-not-found");

        recoveryService.recoverDue(later);

        PaymentEntity recovered = reload(payment);
        assertThat(recovered.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(recovered.getFailureCode()).isEqualTo("RECOVERY_NOT_APPROVED");
    }

    @Test
    @DisplayName("결제사는 승인했는데 응답을 못 받았으면 결제사 취소(환불) 후 FAILED로 정리한다")
    void approvedAtProvider() {
        PaymentEntity payment = saveProcessing("order-approved");
        fakeGateway.approve("order-approved", payment.getAmount());

        Result result = recoveryService.recover(payment.getId(), later);

        assertThat(result).isEqualTo(Result.REFUNDED);
        PaymentEntity recovered = reload(payment);
        assertThat(recovered.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(recovered.getFailureCode()).isEqualTo("RECOVERY_REFUND");
        assertThat(fakeGateway.statusOf("order-approved").status()).isEqualTo(InquiryStatus.CANCELED);
    }

    @Test
    @DisplayName("결제사에서 이미 취소된 결제는 FAILED로 정리한다")
    void canceledAtProvider() {
        PaymentEntity payment = saveProcessing("order-canceled");
        String key = fakeGateway.approve("order-canceled", payment.getAmount()).providerPaymentKey();
        fakeGateway.cancel(key, "이미 취소됨");

        Result result = recoveryService.recover(payment.getId(), later);

        assertThat(result).isEqualTo(Result.MARKED_FAILED);
        assertThat(reload(payment).getFailureCode()).isEqualTo("RECOVERY_CANCELED");
    }

    // ---------- 대상 선정 ----------

    @Test
    @DisplayName("유예 시간 안의 PROCESSING은 요청 스레드가 처리 중일 수 있으므로 건드리지 않는다")
    void skipsPaymentWithinGracePeriod() {
        PaymentEntity payment = saveProcessing("order-young");

        int processed = recoveryService.recoverDue(LocalDateTime.now().plusMinutes(5));

        assertThat(processed).isZero();
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    @DisplayName("주문을 오래전에 만들었어도 승인을 방금 시작했으면 건드리지 않는다 (결제창에 오래 머문 경우)")
    void gracePeriodStartsWhenProcessingStarts() {
        PaymentEntity payment = saveProcessing("order-slow-window");
        LocalDateTime longAgo = LocalDateTime.now().minusMinutes(30);
        jdbcTemplate.update("update payments set requested_at = ? where id = ?", longAgo, payment.getId());

        int processed = recoveryService.recoverDue(LocalDateTime.now());

        assertThat(processed).isZero();
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    @DisplayName("이미 끝난 결제를 다시 복구하면 아무것도 하지 않는다 (멱등)")
    void recoverTwice() {
        PaymentEntity payment = saveProcessing("order-twice");
        fakeGateway.approve("order-twice", payment.getAmount());

        assertThat(recoveryService.recover(payment.getId(), later)).isEqualTo(Result.REFUNDED);
        assertThat(recoveryService.recover(payment.getId(), later)).isEqualTo(Result.SKIPPED);
    }

    // ---------- 재시도 ----------

    @Test
    @DisplayName("환불이 실패하면 백오프 시간 뒤에 다시 시도하고, 그 전에는 대상에서 빠진다")
    void retryAfterBackoff() {
        PaymentEntity payment = saveRefundPending("order-retry");
        fakeGateway.failNextCancels(1);
        // DB(DATETIME(6))는 마이크로초까지만 저장하므로 비교할 시각도 맞춰 자른다
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        recoveryService.recoverDue(now);
        PaymentEntity afterFirst = reload(payment);
        assertThat(afterFirst.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(afterFirst.getRecoveryAttempts()).isEqualTo(1);
        assertThat(afterFirst.getNextRecoveryAt()).isEqualTo(now.plusMinutes(1));

        assertThat(recoveryService.recoverDue(now.plusSeconds(30))).isZero();

        recoveryService.recoverDue(now.plusMinutes(1));
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("재시도를 최대 횟수만큼 실패하면 NEEDS_REVIEW로 넘기고 더 시도하지 않는다")
    void needsReviewAfterMaxAttempts() {
        PaymentEntity payment = saveRefundPending("order-review");
        fakeGateway.failNextCancels(10);
        LocalDateTime now = LocalDateTime.now();

        assertThat(recoveryService.recover(payment.getId(), now)).isEqualTo(Result.RETRY_SCHEDULED);
        assertThat(recoveryService.recover(payment.getId(), now)).isEqualTo(Result.RETRY_SCHEDULED);
        assertThat(recoveryService.recover(payment.getId(), now)).isEqualTo(Result.NEEDS_REVIEW);

        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(recoveryService.recoverDue(now.plusDays(1))).isZero();
    }

    // ---------- 동시성 ----------

    @Test
    @DisplayName("복구 작업이 결제사를 조회하는 사이 요청 스레드가 결제를 끝냈으면, 환불하지 않고 건너뛴다")
    void requestThreadWinsRace() {
        PaymentEntity payment = saveProcessing("order-race");
        String key = fakeGateway.approve("order-race", payment.getAmount()).providerPaymentKey();

        // 복구 작업은 PROCESSING을 읽었지만, 결제사 조회 직전에 요청 스레드가 SUCCESS로 커밋한다
        fakeGateway.beforeNextInquire(() -> transactionTemplate.executeWithoutResult(status -> {
            PaymentEntity current = paymentRepository.findById(payment.getId()).orElseThrow();
            ReservationEntity reservation = reservationRepository.save(ReservationEntity.reserved(current.getUser(), current.getSeat()));
            current.succeed(key, reservation);
        }));

        Result result = recoveryService.recover(payment.getId(), later);

        assertThat(result).isEqualTo(Result.SKIPPED);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        // 성공한 결제를 환불하지 않았다
        assertThat(fakeGateway.statusOf("order-race").status()).isEqualTo(InquiryStatus.APPROVED);
    }

    // ---------- 결제 흐름(pay)과 연결 ----------

    @Test
    @DisplayName("승인 응답이 유실되면 pay는 PROCESSING을 돌려주고, 복구 작업이 나중에 환불한다")
    void lostApproveResponseIsRecovered() {
        given(redisSeatHoldService.isHeldBy(anyLong(), anyLong())).willReturn(true);
        PaymentResponse order = paymentService.prepare(seat.getEvent().getId(), seat.getId(), user.getId());
        fakeGateway.loseNextApproveResponse();

        PaymentResponse response = paymentService.confirm(user.getId(), order.orderId(), "key-lost", order.amount());

        assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);
        // 결제사 쪽에서는 실제로 승인됐다
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(InquiryStatus.APPROVED);

        recoveryService.recoverDue(later);

        assertThat(paymentRepository.findByOrderId(order.orderId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(InquiryStatus.CANCELED);
    }

    @Test
    @DisplayName("예약 확정과 보상 취소가 모두 실패하면 REFUND_PENDING으로 남고, 복구 작업이 유예 없이 환불한다")
    void failedCompensationBecomesRefundPending() {
        // 주문 생성과 확정 시작 때는 선점이 있었는데, 결제사 승인 뒤에는 선점이 만료됐다
        given(redisSeatHoldService.isHeldBy(anyLong(), anyLong())).willReturn(true, true, false);
        fakeGateway.failNextCancels(1);
        PaymentResponse order = paymentService.prepare(seat.getEvent().getId(), seat.getId(), user.getId());

        assertThatThrownBy(() -> paymentService.confirm(user.getId(), order.orderId(), "key-compensate", order.amount()))
                .isInstanceOf(SeatUnavailableException.class);

        PaymentEntity pending = paymentRepository.findByOrderId(order.orderId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(InquiryStatus.APPROVED);

        // REFUND_PENDING은 환불이 필요하다는 게 확실하므로 유예 시간을 기다리지 않는다
        recoveryService.recoverDue(LocalDateTime.now());

        assertThat(reload(pending).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(InquiryStatus.CANCELED);
    }

    // ---------- 예약 취소 환불 ----------

    @Test
    @DisplayName("예약 취소 환불이 실패하면 예약은 취소되고 결제는 CANCEL_PENDING으로 남으며, 복구 작업이 유예 없이 환불한다")
    void failedCancelRefundIsRecovered() {
        given(redisSeatHoldService.isHeldBy(anyLong(), anyLong())).willReturn(true);
        PaymentResponse paid = pay("key-cancel-recover");
        fakeGateway.failNextCancels(1);

        reservationService.cancel(paid.reservationId(), user.getId());

        assertThat(reservationRepository.findById(paid.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELED);
        PaymentEntity pending = paymentRepository.findByOrderId(paid.orderId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(fakeGateway.statusOf(paid.orderId()).status()).isEqualTo(InquiryStatus.APPROVED);

        recoveryService.recoverDue(LocalDateTime.now());

        assertThat(reload(pending).getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(fakeGateway.statusOf(paid.orderId()).status()).isEqualTo(InquiryStatus.CANCELED);
    }

    @Test
    @DisplayName("환불이 실패한 예약을 사용자가 다시 취소하면 그때 환불을 다시 시도한다")
    void retryCancelRefundsAgain() {
        given(redisSeatHoldService.isHeldBy(anyLong(), anyLong())).willReturn(true);
        PaymentResponse paid = pay("key-cancel-retry");
        fakeGateway.failNextCancels(1);
        reservationService.cancel(paid.reservationId(), user.getId());

        reservationService.cancel(paid.reservationId(), user.getId());

        assertThat(paymentRepository.findByOrderId(paid.orderId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELED);
    }

    // ---------- 준비 ----------

    private PaymentResponse pay(String paymentKey) {
        PaymentResponse order = paymentService.prepare(seat.getEvent().getId(), seat.getId(), user.getId());
        return paymentService.confirm(user.getId(), order.orderId(), paymentKey, order.amount());
    }

    private PaymentEntity saveProcessing(String orderId) {
        PaymentEntity payment = PaymentEntity.ready(orderId, user, seat, 50_000L, "FAKE");
        payment.startProcessing();
        return paymentRepository.save(payment);
    }

    private PaymentEntity saveRefundPending(String orderId) {
        String key = fakeGateway.approve(orderId, 50_000L).providerPaymentKey();
        PaymentEntity payment = PaymentEntity.ready(orderId, user, seat, 50_000L, "FAKE");
        payment.startProcessing();
        payment.requireRefund(key, "RESERVATION_FAILED", "예약 확정 실패");
        return paymentRepository.save(payment);
    }

    private PaymentEntity reload(PaymentEntity payment) {
        return paymentRepository.findById(payment.getId()).orElseThrow();
    }
}
