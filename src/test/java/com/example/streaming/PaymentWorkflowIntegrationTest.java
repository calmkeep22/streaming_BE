package com.example.streaming;

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
import com.example.streaming.Service.FakePaymentGateway;
import com.example.streaming.Service.PaymentGateway;
import com.example.streaming.Service.PaymentRecoveryService;
import com.example.streaming.Service.PaymentService;
import com.example.streaming.Service.RedisSeatHoldService;
import com.example.streaming.Service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.recovery.enabled=false",
        "payment.recovery.grace-period=PT10M",
        "payment.recovery.max-attempts=3",
        "payment.recovery.base-backoff=PT1M"
})
@AutoConfigureMockMvc
@Import(PaymentWorkflowIntegrationTest.GatewayConfiguration.class)
@Timeout(120)
class PaymentWorkflowIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.44")
            .withDatabaseName("payment_workflow_test").withUsername("test").withPassword("test");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired MockMvc mockMvc;
    @Autowired PaymentService paymentService;
    @Autowired PaymentRecoveryService recoveryService;
    @Autowired ReservationService reservationService;
    @Autowired RedisSeatHoldService holdService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired FakePaymentGateway fakeGateway;
    @Autowired ControlledGateway gateway;
    @Autowired PaymentRepository payments;
    @Autowired ReservationRepository reservations;
    @Autowired SeatRepository seats;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    UserEntity user;
    SeatEntity seat;
    Long eventId;

    @BeforeEach
    void setUp() {
        dropInsertFailure();
        payments.deleteAllInBatch();
        reservations.deleteAllInBatch();
        seats.deleteAllInBatch();
        events.deleteAllInBatch();
        users.deleteAllInBatch();
        // This database belongs exclusively to the disposable Redis container above.
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        fakeGateway.reset();
        gateway.reset();
        user = users.save(new UserEntity("buyer@workflow.test", "password"));
        EventEntity event = events.save(new EventEntity("Workflow test", LocalDateTime.now().plusDays(7)));
        eventId = event.getId();
        seat = seats.save(new SeatEntity(event, 1));
    }

    @AfterEach
    void clearFaults() {
        dropInsertFailure();
        assertThat(gateway.callsInsideTransaction.get())
                .as("PG network operations must not hold a database transaction")
                .isZero();
    }

    @Test
    @DisplayName("HTTP 선점부터 결제 확정까지 연결되고 재확정은 승인과 예약을 중복 생성하지 않는다")
    void httpHoldPrepareConfirmAndReplay() throws Exception {
        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/holds", eventId, seat.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isCreated());
        assertThat(holdService.getHolder(seat.getId())).isEqualTo(user.getId().toString());

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/payments", eventId, seat.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.amount").value(50_000));
        PaymentEntity order = payments.findAll().getFirst();
        String body = confirmBody(order.getOrderId(), order.getAmount());

        mockMvc.perform(post("/payments/confirm").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        Long reservationId = load(order.getOrderId()).getReservation().getId();
        mockMvc.perform(post("/payments/confirm").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId));

        assertThat(fakeGateway.approveCount()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(reservations.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(holdService.getHolder(seat.getId())).isNull();
    }

    @Test
    @DisplayName("동일 주문 동시 확정은 실제 MySQL에서도 승인 1회와 예약 1건으로 수렴한다")
    void concurrentConfirmCallsProviderOnce() throws Exception {
        PaymentResponse order = heldOrder(user);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch approved = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        gateway.afterNextApproval.set(() -> {
            approved.countDown();
            await(releaseResponse);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ExecutorCompletionService<PaymentResponse> completion = new ExecutorCompletionService<>(executor);
        try {
            for (int i = 0; i < 2; i++) {
                completion.submit(() -> { await(start); return confirm(user, order); });
            }
            start.countDown();
            assertThat(approved.await(30, TimeUnit.SECONDS)).isTrue();
            Future<PaymentResponse> duplicate = completion.poll(30, TimeUnit.SECONDS);
            assertThat(duplicate).as("The duplicate must return while the first PG response is blocked").isNotNull();
            PaymentResponse processing = duplicate.get(30, TimeUnit.SECONDS);
            assertThat(processing.paymentId()).isEqualTo(order.paymentId());
            assertThat(processing.status()).isEqualTo(PaymentStatus.PROCESSING);
            releaseResponse.countDown();
            Future<PaymentResponse> winner = completion.poll(30, TimeUnit.SECONDS);
            assertThat(winner).isNotNull();
            assertThat(winner.get(30, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(fakeGateway.approveCount()).isEqualTo(1);
            assertThat(reservations.count()).isEqualTo(1);
            assertThat(load(order.orderId()).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(holdService.getHolder(seat.getId())).isNull();
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("PG 승인 후 실제 MySQL INSERT 실패는 예약을 롤백하고 승인 금액을 보상 환불한다")
    void databaseInsertFailureRefundsApprovedPayment() {
        PaymentResponse order = heldOrder(user);
        failReservationInsert();
        assertThatThrownBy(() -> confirm(user, order))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("injected reservation insert failure");
        assertThat(reservations.count()).isZero();
        assertThat(load(order.orderId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.CANCELED);
        assertThat(gateway.cancelCalls.get()).isEqualTo(1);
        assertThat(holdService.getHolder(seat.getId())).isEqualTo(user.getId().toString());
    }

    @Test
    @DisplayName("DB 실패 후 보상 환불도 실패하면 REFUND_PENDING과 재시도 대기를 거쳐 복구한다")
    void failedCompensationIsRetriedWithBackoff() {
        PaymentResponse order = heldOrder(user);
        failReservationInsert();
        fakeGateway.failNextCancels(2);
        assertThatThrownBy(() -> confirm(user, order)).isInstanceOf(DataAccessException.class);
        assertThat(load(order.orderId()).getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.APPROVED);

        LocalDateTime now = LocalDateTime.now();
        assertThat(recoveryService.recoverDue(now)).isEqualTo(1);
        PaymentEntity waiting = load(order.orderId());
        assertThat(waiting.getRecoveryAttempts()).isEqualTo(1);
        assertThat(waiting.getNextRecoveryAt()).isAfter(now);
        assertThat(recoveryService.recoverDue(now)).isZero();
        assertThat(gateway.cancelCalls.get()).isEqualTo(2);

        assertThat(recoveryService.recoverDue(waiting.getNextRecoveryAt().plusSeconds(1))).isEqualTo(1);
        assertThat(load(order.orderId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.CANCELED);
        assertThat(gateway.cancelCalls.get()).isEqualTo(3);
        assertThat(reservations.count()).isZero();
        assertThat(recoveryService.recoverDue(now.plusHours(1))).isZero();
    }

    @Test
    @DisplayName("예약 취소 환불 실패는 좌석을 풀고 CANCEL_PENDING에서 복구 후 CANCELED로 끝난다")
    void cancellationRefundFailureDoesNotKeepSeatReserved() throws Exception {
        PaymentResponse paid = confirm(user, heldOrder(user));
        fakeGateway.failNextCancels(1);
        mockMvc.perform(delete("/reservations/{id}", paid.reservationId())
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isNoContent());
        assertThat(load(paid.orderId()).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(reservations.findById(paid.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELED);
        UserEntity nextUser = users.save(new UserEntity("next@workflow.test", "password"));
        reservationService.holdSeat(eventId, seat.getId(), nextUser.getId());
        assertThat(holdService.isHeldBy(seat.getId(), nextUser.getId())).isTrue();

        assertThat(recoveryService.recoverDue(LocalDateTime.now())).isEqualTo(1);
        assertThat(load(paid.orderId()).getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(fakeGateway.statusOf(paid.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.CANCELED);
        reservationService.cancel(paid.reservationId(), user.getId());
        assertThat(gateway.cancelCalls.get()).isEqualTo(2);
        assertThat(holdService.isHeldBy(seat.getId(), nextUser.getId())).isTrue();
    }

    @Test
    @DisplayName("승인 응답이 유실되면 202 PROCESSING을 유지하고 유예 후 조회와 환불로 정리한다")
    void lostApprovalResponseIsRecoveredWithoutAnotherApproval() throws Exception {
        PaymentResponse order = heldOrder(user);
        fakeGateway.loseNextApproveResponse();
        String body = confirmBody(order.orderId(), order.amount());
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/payments/confirm").contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PROCESSING"));
        }
        assertThat(fakeGateway.approveCount()).isEqualTo(1);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.APPROVED);
        assertThat(recoveryService.recoverDue(LocalDateTime.now())).isZero();
        assertThat(recoveryService.recoverDue(LocalDateTime.now().plusMinutes(11))).isEqualTo(1);
        assertThat(load(order.orderId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fakeGateway.statusOf(order.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.CANCELED);
        assertThat(reservations.count()).isZero();
    }

    @Test
    @DisplayName("A의 승인 응답 중 선점이 만료돼 B가 결제하면 B만 예약되고 A는 환불된다")
    void expiredHoldDuringApprovalCannotReplaceNewReservation() throws Exception {
        PaymentResponse firstOrder = heldOrder(user);
        CountDownLatch approved = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        gateway.afterNextApproval.set(() -> { approved.countDown(); await(releaseResponse); });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentResponse> first = executor.submit(() -> confirm(user, firstOrder));
            assertThat(approved.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(fakeGateway.statusOf(firstOrder.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.APPROVED);
            // Expire only this real Redis key immediately; no five-minute sleep or mocked ownership.
            assertThat(redisTemplate.expire(holdService.createKey(seat.getId()), Duration.ZERO)).isTrue();
            UserEntity nextUser = users.save(new UserEntity("competitor@workflow.test", "password"));
            PaymentResponse second = confirm(nextUser, heldOrder(nextUser));
            assertThat(second.status()).isEqualTo(PaymentStatus.SUCCESS);
            releaseResponse.countDown();
            assertThatThrownBy(() -> first.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SeatUnavailableException.class);
            assertThat(reservations.count()).isEqualTo(1);
            assertThat(load(firstOrder.orderId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(load(second.orderId()).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(fakeGateway.statusOf(firstOrder.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.CANCELED);
            assertThat(fakeGateway.statusOf(second.orderId()).status()).isEqualTo(PaymentGateway.InquiryStatus.APPROVED);
            assertThat(fakeGateway.approveCount()).isEqualTo(2);
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private PaymentResponse heldOrder(UserEntity buyer) {
        reservationService.holdSeat(eventId, seat.getId(), buyer.getId());
        return paymentService.prepare(eventId, seat.getId(), buyer.getId());
    }

    private PaymentResponse confirm(UserEntity buyer, PaymentResponse order) {
        return paymentService.confirm(buyer.getId(), order.orderId(), "key_" + order.orderId(), order.amount());
    }

    private PaymentEntity load(String orderId) {
        return payments.findByOrderId(orderId).orElseThrow();
    }

    private String confirmBody(String orderId, long amount) {
        return "{\"orderId\":\"" + orderId + "\",\"paymentKey\":\"key_" + orderId + "\",\"amount\":" + amount + "}";
    }

    private void failReservationInsert() {
        // Fail an actual database write in this disposable schema, rather than mocking a repository exception.
        jdbc.execute("CREATE TRIGGER test_reservation_insert_failure BEFORE INSERT ON reservation_entity "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected reservation insert failure'");
    }

    private void dropInsertFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_reservation_insert_failure");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) { throw new AssertionError("Test synchronization timed out"); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test synchronization interrupted", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean
        @Primary
        ControlledGateway controlledGateway(FakePaymentGateway fakeGateway) {
            return new ControlledGateway(fakeGateway);
        }
    }

    static class ControlledGateway implements PaymentGateway {
        private final FakePaymentGateway delegate;
        final AtomicReference<Runnable> afterNextApproval = new AtomicReference<>();
        final AtomicInteger cancelCalls = new AtomicInteger();
        final AtomicInteger callsInsideTransaction = new AtomicInteger();

        ControlledGateway(FakePaymentGateway delegate) { this.delegate = delegate; }

        @Override public String providerName() { return delegate.providerName(); }

        @Override
        public PaymentApproval approve(String paymentKey, String orderId, long amount) {
            recordTransaction();
            PaymentApproval approval = delegate.approve(paymentKey, orderId, amount);
            Runnable hook = afterNextApproval.getAndSet(null);
            if (hook != null) { hook.run(); }
            return approval;
        }

        @Override
        public void cancel(String providerPaymentKey, String reason) {
            recordTransaction();
            cancelCalls.incrementAndGet();
            delegate.cancel(providerPaymentKey, reason);
        }

        @Override
        public PaymentInquiry inquire(String orderId) {
            recordTransaction();
            return delegate.inquire(orderId);
        }

        private void recordTransaction() {
            if (TransactionSynchronizationManager.isActualTransactionActive()) { callsInsideTransaction.incrementAndGet(); }
        }

        void reset() {
            afterNextApproval.set(null);
            cancelCalls.set(0);
            callsInsideTransaction.set(0);
        }
    }
}
