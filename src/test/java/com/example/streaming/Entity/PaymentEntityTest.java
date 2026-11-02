package com.example.streaming.Entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEntityTest {

    @Test
    @DisplayName("결제 요청을 생성하면 READY 상태와 결제 정보가 저장된다")
    void ready() {
        UserEntity user = new UserEntity("payment@test.com", "password");
        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(1));
        SeatEntity seat = new SeatEntity(event, 1);

        PaymentEntity payment = PaymentEntity.ready(
                "order-1",
                user,
                seat,
                50_000L,
                "FAKE"
        );

        assertThat(payment.getOrderId()).isEqualTo("order-1");
        assertThat(payment.getUser()).isSameAs(user);
        assertThat(payment.getSeat()).isSameAs(seat);
        assertThat(payment.getAmount()).isEqualTo(50_000L);
        assertThat(payment.getCurrency()).isEqualTo("KRW");
        assertThat(payment.getProvider()).isEqualTo("FAKE");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getRequestedAt()).isNotNull();
    }

    @Test
    @DisplayName("결제 성공 시 예약과 결제사 키를 저장하고 SUCCESS로 변경한다")
    void succeed() {
        UserEntity user = new UserEntity("payment@test.com", "password");
        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(1));
        SeatEntity seat = new SeatEntity(event, 1);
        ReservationEntity reservation = ReservationEntity.reserved(user, seat);
        PaymentEntity payment = PaymentEntity.ready("order-1", user, seat, 50_000L, "FAKE");

        payment.startProcessing();
        payment.succeed("provider-key-1", reservation);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getProviderPaymentKey()).isEqualTo("provider-key-1");
        assertThat(payment.getReservation()).isSameAs(reservation);
        assertThat(payment.getApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("실패한 결제는 성공 처리할 수 없다")
    void failedPaymentCannotSucceed() {
        UserEntity user = new UserEntity("payment@test.com", "password");
        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(1));
        SeatEntity seat = new SeatEntity(event, 1);
        ReservationEntity reservation = ReservationEntity.reserved(user, seat);
        PaymentEntity payment = PaymentEntity.ready("order-1", user, seat, 50_000L, "FAKE");
        payment.fail("DECLINED", "결제가 거절되었습니다.");

        assertThatThrownBy(() -> payment.succeed("provider-key-1", reservation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("결제를 성공 처리할 수 없는 상태입니다.");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("DECLINED");
        assertThat(payment.getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("환불 대기로 바꾼 결제는 요청 스레드가 성공 처리할 수 없다")
    void refundPendingCannotSucceed() {
        UserEntity user = new UserEntity("payment@test.com", "password");
        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(1));
        SeatEntity seat = new SeatEntity(event, 1);
        ReservationEntity reservation = ReservationEntity.reserved(user, seat);
        PaymentEntity payment = PaymentEntity.ready("order-1", user, seat, 50_000L, "FAKE");
        payment.startProcessing();

        payment.requireRefund("provider-key-1", "RECOVERY_REFUND", "자동 환불");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getProviderPaymentKey()).isEqualTo("provider-key-1");
        assertThatThrownBy(() -> payment.succeed("provider-key-1", reservation))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("환불을 완료하면 FAILED가 되고, 다시 완료해도 그대로다")
    void completeRefund() {
        UserEntity user = new UserEntity("payment@test.com", "password");
        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(1));
        SeatEntity seat = new SeatEntity(event, 1);
        PaymentEntity payment = PaymentEntity.ready("order-1", user, seat, 50_000L, "FAKE");
        payment.startProcessing();
        payment.requireRefund("provider-key-1", "RECOVERY_REFUND", "자동 환불");

        payment.completeRefund();
        payment.completeRefund();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("RECOVERY_REFUND");
        assertThat(payment.getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("복구 재시도는 지수 백오프로 미뤄지고, 최대 횟수에 도달하면 NEEDS_REVIEW가 된다")
    void scheduleRecoveryRetry() {
        UserEntity user = new UserEntity("payment@test.com", "password");
        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(1));
        SeatEntity seat = new SeatEntity(event, 1);
        PaymentEntity payment = PaymentEntity.ready("order-1", user, seat, 50_000L, "FAKE");
        payment.startProcessing();
        LocalDateTime now = LocalDateTime.of(2026, 10, 30, 12, 0);
        Duration base = Duration.ofMinutes(1);

        payment.scheduleRecoveryRetry(now, 3, base);
        assertThat(payment.getNextRecoveryAt()).isEqualTo(now.plusMinutes(1));

        payment.scheduleRecoveryRetry(now, 3, base);
        assertThat(payment.getNextRecoveryAt()).isEqualTo(now.plusMinutes(2));

        payment.scheduleRecoveryRetry(now, 3, base);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(payment.getRecoveryAttempts()).isEqualTo(3);
        assertThat(payment.getNextRecoveryAt()).isNull();
    }

    @Test
    @DisplayName("0원 이하 금액으로 결제 요청을 생성할 수 없다")
    void amountMustBePositive() {
        UserEntity user = new UserEntity("payment@test.com", "password");
        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(1));
        SeatEntity seat = new SeatEntity(event, 1);

        assertThatThrownBy(() -> PaymentEntity.ready("order-1", user, seat, 0L, "FAKE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 금액은 0보다 커야 합니다.");
    }
}
