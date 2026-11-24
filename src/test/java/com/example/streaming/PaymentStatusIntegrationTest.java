package com.example.streaming;

import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.PaymentRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import com.example.streaming.Service.FakePaymentGateway;
import com.example.streaming.Service.PaymentRecoveryService;
import com.example.streaming.Service.PaymentService;
import com.example.streaming.Service.RedisSeatHoldService;
import com.example.streaming.Service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-status;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "payment.gateway=fake",
        "payment.recovery.grace-period=PT10M"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentStatusIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired PaymentService paymentService;
    @Autowired ReservationService reservationService;
    @Autowired PaymentRecoveryService recoveryService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean RedisSeatHoldService redisSeatHoldService;
    @MockitoSpyBean FakePaymentGateway fakeGateway;

    UserEntity user;
    EventEntity event;
    SeatEntity seat;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        reservationRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
        fakeGateway.reset();
        user = userRepository.save(new UserEntity("owner@test.com", "password"));
        event = eventRepository.save(new EventEntity("공연", LocalDateTime.now().plusDays(7)));
        seat = seatRepository.save(new SeatEntity(event, 1));
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("모든 결제 상태를 본인에게 200으로 반환하고 조회로 상태를 바꾸지 않는다")
    void readsEveryStatus(PaymentStatus paymentStatus) throws Exception {
        PaymentEntity payment = savePayment(paymentStatus);
        Long version = payment.getVersion();

        ResultActions result = getPayment(payment.getOrderId(), user.getId())
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.paymentId").value(payment.getId()))
                .andExpect(jsonPath("$.orderId").value(payment.getOrderId()))
                .andExpect(jsonPath("$.seatId").value(seat.getId()))
                .andExpect(jsonPath("$.amount").value(50_000))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.status").value(paymentStatus.name()))
                .andExpect(jsonPath("$.providerPaymentKey").doesNotExist());
        if (payment.getReservation() == null) {
            result.andExpect(jsonPath("$.reservationId").doesNotExist());
        } else {
            result.andExpect(jsonPath("$.reservationId").value(payment.getReservation().getId()));
        }

        PaymentEntity after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(paymentStatus);
        assertThat(after.getVersion()).isEqualTo(version);
    }

    @Test
    @DisplayName("타인의 주문은 404를 반환하고 결제 정보를 노출하지 않는다")
    void otherUsersOrder() throws Exception {
        PaymentEntity payment = savePayment(PaymentStatus.SUCCESS);
        UserEntity other = userRepository.save(new UserEntity("other@test.com", "password"));

        getPayment(payment.getOrderId(), other.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("주문을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.paymentId").doesNotExist())
                .andExpect(jsonPath("$.reservationId").doesNotExist())
                .andExpect(jsonPath("$.amount").doesNotExist());
    }

    @Test
    @DisplayName("없는 주문도 같은 404 응답을 반환한다")
    void missingOrder() throws Exception {
        getPayment("missing-order", user.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("주문을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("인증하지 않은 사용자는 결제 상태를 조회할 수 없다")
    void authenticationRequired() throws Exception {
        PaymentEntity payment = savePayment(PaymentStatus.READY);
        clearInvocations(fakeGateway, redisSeatHoldService);

        mockMvc.perform(get("/payments/{orderId}", payment.getOrderId()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(fakeGateway, redisSeatHoldService);
    }

    @Test
    @DisplayName("주문 생성·결제 확정·예약 취소가 커밋되면 다음 조회에서 변경된 상태를 반환한다")
    void followsPaymentAndCancellation() throws Exception {
        PaymentResponse order = prepareOrder();
        getPayment(order.orderId(), user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        PaymentResponse paid = confirm(order);
        getPayment(order.orderId(), user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.reservationId").value(paid.reservationId()));

        reservationService.cancel(paid.reservationId(), user.getId());
        getPayment(order.orderId(), user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        assertThat(fakeGateway.approveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("승인 응답이 유실되면 PROCESSING을 조회하고 복구 환불 후에는 FAILED를 조회한다")
    void followsRecoveryAfterLostApproval() throws Exception {
        PaymentResponse order = prepareOrder();
        fakeGateway.loseNextApproveResponse();
        assertThat(confirm(order).status()).isEqualTo(PaymentStatus.PROCESSING);

        getPayment(order.orderId(), user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.reservationId").doesNotExist());

        assertThat(recoveryService.recoverDue(LocalDateTime.now().plusMinutes(11))).isEqualTo(1);
        getPayment(order.orderId(), user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("환불 실패 시 CANCEL_PENDING을 조회하고 복구가 끝나면 CANCELED를 조회한다")
    void followsCancellationRecovery() throws Exception {
        PaymentResponse paid = confirm(prepareOrder());
        fakeGateway.failNextCancels(1);
        reservationService.cancel(paid.reservationId(), user.getId());

        getPayment(paid.orderId(), user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCEL_PENDING"));

        assertThat(recoveryService.recoverDue(LocalDateTime.now())).isEqualTo(1);
        getPayment(paid.orderId(), user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    private PaymentResponse prepareOrder() {
        given(redisSeatHoldService.isHeldBy(seat.getId(), user.getId())).willReturn(true);
        return paymentService.prepare(event.getId(), seat.getId(), user.getId());
    }

    private PaymentResponse confirm(PaymentResponse order) {
        return paymentService.confirm(user.getId(), order.orderId(), "key-" + order.orderId(), order.amount());
    }

    private ResultActions getPayment(String orderId, Long userId) throws Exception {
        // 준비·결제·복구에서 발생한 호출과 구분해, HTTP GET 자체가 외부 호출을 하지 않는지 확인한다.
        clearInvocations(fakeGateway, redisSeatHoldService);
        ResultActions result = mockMvc.perform(get("/payments/{orderId}", orderId)
                .with(jwt().jwt(token -> token.subject(userId.toString()))));
        verifyNoInteractions(fakeGateway, redisSeatHoldService);
        return result;
    }

    private PaymentEntity savePayment(PaymentStatus paymentStatus) {
        PaymentEntity payment = PaymentEntity.ready(
                "order_" + UUID.randomUUID().toString().replace("-", ""), user, seat, 50_000, "FAKE");
        if (paymentStatus != PaymentStatus.READY) {
            payment.startProcessing();
        }
        switch (paymentStatus) {
            case READY, PROCESSING -> { }
            case FAILED -> payment.fail("DECLINED", "결제 거절");
            case REFUND_PENDING -> payment.requireRefund("refund-key", "COMPENSATE", "환불 대기");
            case NEEDS_REVIEW -> payment.scheduleRecoveryRetry(LocalDateTime.now(), 1, Duration.ofMinutes(1));
            case SUCCESS, CANCEL_PENDING, CANCELED -> {
                ReservationEntity reservation = reservationRepository.save(ReservationEntity.reserved(user, seat));
                payment.succeed("approved-key", reservation);
                if (paymentStatus != PaymentStatus.SUCCESS) {
                    reservation.cancel();
                    reservationRepository.save(reservation);
                    payment.requestCancel();
                }
                if (paymentStatus == PaymentStatus.CANCELED) {
                    payment.completeCancel();
                }
            }
        }
        return paymentRepository.saveAndFlush(payment);
    }
}
