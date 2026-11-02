package com.example.streaming.Service;

import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Exception.ForbiddenException;
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
 * 결제 흐름(주문 생성 → 결제 확정)의 정상·실패 경로. 비정상 상태의 복구는 PaymentRecoveryServiceTest에서 본다.
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
        // 결제가 예약을 참조하므로, 같은 DB를 쓰는 다른 테스트가 예약을 지울 수 있게 정리한다
        paymentRepository.deleteAll();
    }

    @Test
    @DisplayName("주문을 만들면 서버가 정한 주문번호와 금액을 READY로 돌려주고, 결제사는 부르지 않는다")
    void prepare() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);

        PaymentResponse order = prepareOrder();

        assertThat(order.status()).isEqualTo(PaymentStatus.READY);
        assertThat(order.amount()).isEqualTo(50_000L);
        // 토스 orderId 규칙: 6~64자, 영문·숫자·-·_·=
        assertThat(order.orderId()).matches("[A-Za-z0-9_=-]{6,64}");
        assertThat(fakeGateway.approveCount()).isZero();
    }

    @Test
    @DisplayName("결제를 확정하면 SUCCESS와 확정 예약이 생기고, 커밋 후 선점을 해제한다")
    void confirmSuccess() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        PaymentResponse order = prepareOrder();

        PaymentResponse response = confirm(order);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.reservationId()).isNotNull();
        assertThat(reservationRepository.findById(response.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(InquiryStatus.APPROVED);
        assertThat(paymentRepository.findByOrderId(order.orderId()).orElseThrow().getProviderPaymentKey())
                .isEqualTo(keyOf(order));
        verify(redisSeatHoldService).release(seat.getId(), user.getId());
    }

    @Test
    @DisplayName("같은 주문을 다시 확정하면 결제사를 다시 부르지 않고 기존 결과를 돌려준다 (멱등)")
    void confirmTwice() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        PaymentResponse order = prepareOrder();

        PaymentResponse first = confirm(order);
        PaymentResponse second = confirm(order);

        assertThat(second).isEqualTo(first);
        assertThat(fakeGateway.approveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주문의 확정 요청이 동시에 와도 결제사 승인은 한 번만 나간다")
    void concurrentConfirm() throws Exception {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        PaymentResponse order = prepareOrder();
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<PaymentResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return confirm(order);
                }));
            }
            start.countDown();

            List<PaymentResponse> responses = new ArrayList<>();
            for (Future<PaymentResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            // 늦은 쪽은 먼저 온 요청의 결제를 그대로 돌려받는다 (그 시점 상태는 PROCESSING일 수도, SUCCESS일 수도 있다)
            assertThat(responses).extracting(PaymentResponse::paymentId).containsOnly(order.paymentId());
            assertThat(fakeGateway.approveCount()).isEqualTo(1);
            assertThat(paymentRepository.findByOrderId(order.orderId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.SUCCESS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("클라이언트가 보낸 금액이 주문 금액과 다르면 결제사를 부르지 않는다 (위변조 방지)")
    void amountMismatch() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        PaymentResponse order = prepareOrder();

        assertThatThrownBy(() -> paymentService.confirm(user.getId(), order.orderId(), keyOf(order), 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 금액이 주문과 일치하지 않습니다.");

        assertThat(fakeGateway.approveCount()).isZero();
        assertThat(paymentRepository.findByOrderId(order.orderId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("다른 사용자의 주문은 확정할 수 없다")
    void confirmOrderOfOtherUser() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        PaymentResponse order = prepareOrder();
        UserEntity otherUser = userRepository.save(new UserEntity(UUID.randomUUID() + "@test.com", "password"));

        assertThatThrownBy(() -> paymentService.confirm(otherUser.getId(), order.orderId(), keyOf(order), order.amount()))
                .isInstanceOf(ForbiddenException.class);

        assertThat(fakeGateway.approveCount()).isZero();
    }

    @Test
    @DisplayName("결제사가 거절하면 FAILED와 거절 사유를 저장하고 예약은 만들지 않는다")
    void declinedByProvider() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        PaymentResponse order = prepareOrder();
        fakeGateway.declineNext("CARD_DECLINED", "카드사에서 거절했습니다.");

        PaymentResponse response = confirm(order);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.reservationId()).isNull();
        PaymentEntity saved = paymentRepository.findByOrderId(order.orderId()).orElseThrow();
        assertThat(saved.getFailureCode()).isEqualTo("CARD_DECLINED");
        assertThat(reservationRepository.existsBySeat_IdAndStatus(seat.getId(), ReservationStatus.RESERVED)).isFalse();
    }

    @Test
    @DisplayName("선점하지 않은 좌석은 주문을 만들 수 없다")
    void prepareNotHeld() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(false);

        assertThatThrownBy(this::prepareOrder).isInstanceOf(SeatUnavailableException.class);

        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    @DisplayName("결제창에 있는 동안 선점이 만료되면 승인을 요청하지 않아 돈이 빠지지 않는다")
    void holdExpiredBeforeConfirm() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true, false);
        PaymentResponse order = prepareOrder();

        assertThatThrownBy(() -> confirm(order)).isInstanceOf(SeatUnavailableException.class);

        assertThat(fakeGateway.approveCount()).isZero();
        assertThat(paymentRepository.findByOrderId(order.orderId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("승인 뒤 예약 확정에 실패하면 결제사 취소로 보상하고 FAILED로 남긴다")
    void compensateWhenReservationFails() {
        // 주문 생성과 확정 시작 때는 선점이 있었지만, 결제사 승인 뒤에는 선점이 만료됐다
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true, true, false);
        PaymentResponse order = prepareOrder();

        assertThatThrownBy(() -> confirm(order)).isInstanceOf(SeatUnavailableException.class);

        PaymentEntity saved = paymentRepository.findByOrderId(order.orderId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(saved.getFailureCode()).isEqualTo("RESERVATION_FAILED");
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(InquiryStatus.CANCELED);
        verify(redisSeatHoldService, never()).release(anyLong(), anyLong());
    }

    private PaymentResponse prepareOrder() {
        return paymentService.prepare(eventId, seat.getId(), user.getId());
    }

    private PaymentResponse confirm(PaymentResponse order) {
        return paymentService.confirm(user.getId(), order.orderId(), keyOf(order), order.amount());
    }

    // 실제로는 결제사가 결제창 인증 후 넘겨주는 값
    private String keyOf(PaymentResponse order) {
        return "key_" + order.orderId();
    }
}
