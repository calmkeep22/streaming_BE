package com.example.streaming.Service;

import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 결제 흐름(pay)의 정상·실패 경로. 비정상 상태의 복구는 PaymentRecoveryServiceTest에서 본다.
 * 설정(@TestPropertySource, @MockitoBean)을 PaymentRecoveryServiceTest와 똑같이 맞춰 Spring 컨텍스트를 재사용한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "payment.recovery.grace-period=PT10M",
        "payment.recovery.max-attempts=3",
        "payment.recovery.base-backoff=PT1M"
})
class PaymentServiceTest {

    @Autowired
    PaymentService paymentService;
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

    @MockitoBean
    RedisSeatHoldService redisSeatHoldService;

    UserEntity user;
    SeatEntity seat;
    Long eventId;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        fakeGateway.reset();

        user = userRepository.save(new UserEntity(UUID.randomUUID() + "@test.com", "password"));
        EventEntity event = eventRepository.save(new EventEntity("콘서트", LocalDateTime.now().plusDays(7)));
        seat = seatRepository.save(new SeatEntity(event, 1));
        eventId = event.getId();
    }

    @AfterEach
    void tearDown() {
        fakeGateway.reset();
    }

    @Test
    @DisplayName("선점한 좌석을 결제하면 SUCCESS와 확정 예약이 생기고, 커밋 후 선점을 해제한다")
    void paySuccess() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);

        PaymentResponse response = paymentService.pay(eventId, seat.getId(), user.getId(), "order-success");

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.amount()).isEqualTo(50_000L);
        assertThat(response.reservationId()).isNotNull();
        assertThat(reservationRepository.findById(response.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(fakeGateway.statusOf("order-success").status()).isEqualTo(InquiryStatus.APPROVED);
        verify(redisSeatHoldService).release(seat.getId(), user.getId());
    }

    @Test
    @DisplayName("같은 주문번호로 다시 요청하면 결제사를 다시 부르지 않고 기존 결과를 돌려준다 (멱등)")
    void sameOrderIdReturnsExistingPayment() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);

        PaymentResponse first = paymentService.pay(eventId, seat.getId(), user.getId(), "order-replay");
        PaymentResponse second = paymentService.pay(eventId, seat.getId(), user.getId(), "order-replay");

        assertThat(second).isEqualTo(first);
        assertThat(fakeGateway.approveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자가 쓴 주문번호로는 결제할 수 없다")
    void orderIdOfOtherUser() {
        given(redisSeatHoldService.isHeldBy(anyLong(), anyLong())).willReturn(true);
        paymentService.pay(eventId, seat.getId(), user.getId(), "order-owned");
        UserEntity otherUser = userRepository.save(new UserEntity(UUID.randomUUID() + "@test.com", "password"));

        assertThatThrownBy(() -> paymentService.pay(eventId, seat.getId(), otherUser.getId(), "order-owned"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("다른 사용자가 사용 중인 주문번호입니다.");
    }

    @Test
    @DisplayName("결제사가 거절하면 FAILED와 거절 사유를 저장하고 예약은 만들지 않는다")
    void declinedByProvider() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        fakeGateway.declineNext("CARD_DECLINED", "카드사에서 거절했습니다.");

        PaymentResponse response = paymentService.pay(eventId, seat.getId(), user.getId(), "order-declined");

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.reservationId()).isNull();
        PaymentEntity saved = paymentRepository.findByOrderId("order-declined").orElseThrow();
        assertThat(saved.getFailureCode()).isEqualTo("CARD_DECLINED");
        assertThat(reservationRepository.existsBySeat_IdAndStatus(seat.getId(), ReservationStatus.RESERVED)).isFalse();
    }

    @Test
    @DisplayName("선점하지 않은 좌석은 결제를 시작하지 않는다 (결제 기록도 남지 않는다)")
    void notHeld() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(false);

        assertThatThrownBy(() -> paymentService.pay(eventId, seat.getId(), user.getId(), "order-not-held"))
                .isInstanceOf(SeatUnavailableException.class);

        assertThat(paymentRepository.findByOrderId("order-not-held")).isEmpty();
        assertThat(fakeGateway.approveCount()).isZero();
    }

    @Test
    @DisplayName("승인 뒤 예약 확정에 실패하면 결제사 취소로 보상하고 FAILED로 남긴다")
    void compensateWhenReservationFails() {
        // 준비 때는 선점이 있었지만, 결제사 승인 뒤 확정 단계에서는 선점이 만료됐다
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true, false);

        assertThatThrownBy(() -> paymentService.pay(eventId, seat.getId(), user.getId(), "order-compensated"))
                .isInstanceOf(SeatUnavailableException.class);

        PaymentEntity saved = paymentRepository.findByOrderId("order-compensated").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(saved.getFailureCode()).isEqualTo("RESERVATION_FAILED");
        assertThat(fakeGateway.statusOf("order-compensated").status()).isEqualTo(InquiryStatus.CANCELED);
        verify(redisSeatHoldService, never()).release(anyLong(), anyLong());
    }

    @Test
    @DisplayName("같은 주문번호로 동시에 요청해도 결제는 하나만 생기고 결제사 승인도 한 번만 나간다")
    void concurrentSameOrderId() throws Exception {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<PaymentResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return paymentService.pay(eventId, seat.getId(), user.getId(), "order-concurrent");
                }));
            }
            start.countDown();

            List<PaymentResponse> responses = new ArrayList<>();
            for (Future<PaymentResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            // 늦은 쪽은 먼저 온 요청의 결제를 그대로 돌려받는다 (그 시점 상태는 PROCESSING일 수도, SUCCESS일 수도 있다)
            assertThat(responses).extracting(PaymentResponse::paymentId).containsOnly(responses.get(0).paymentId());
            assertThat(fakeGateway.approveCount()).isEqualTo(1);
            assertThat(paymentRepository.findByOrderId("order-concurrent").orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.SUCCESS);
        } finally {
            executor.shutdownNow();
        }
    }
}
