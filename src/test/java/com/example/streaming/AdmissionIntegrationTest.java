package com.example.streaming;

import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Entity.*;
import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.Repository.*;
import com.example.streaming.Service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admission;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "payment.gateway=fake"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdmissionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired ReservationRepository reservations;
    @Autowired PaymentRepository payments;
    @Autowired SeatRepository seats;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @Autowired FakePaymentGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RedisSeatHoldService holds;

    UserEntity buyer;
    UserEntity other;
    EventEntity event;

    @BeforeEach
    void setUp() {
        payments.deleteAll();
        reservations.deleteAll();
        seats.deleteAll();
        events.deleteAll();
        users.deleteAll();
        gateway.reset();
        buyer = users.save(new UserEntity("buyer@admission.test", "password"));
        other = users.save(new UserEntity("other@admission.test", "password"));
        event = events.save(new EventEntity("공연", LocalDateTime.now().plusDays(7)));
        given(holds.isHeldBy(anyLong(), anyLong())).willReturn(true);
    }

    @Test
    void freeReservationReplayAdmissionAndCancellation() throws Exception {
        SeatEntity seat = seat(SeatGrade.FREE, 0);
        admission(seat, buyer).andExpect(status().isForbidden());
        reserve(seat, buyer).andExpect(status().isCreated());
        ReservationEntity reservation = reservations.findAll().getFirst();
        assertThat(reservation.getAdmissionType()).isEqualTo(AdmissionType.FREE);
        assertThat(payments.count()).isZero();
        admission(seat, buyer).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.reservationId").value(reservation.getId()))
                .andExpect(jsonPath("$.eventId").value(event.getId()))
                .andExpect(jsonPath("$.seatId").value(seat.getId()))
                .andExpect(jsonPath("$.grade").value("FREE"));
        given(holds.isHeldBy(anyLong(), anyLong())).willReturn(false);
        clearInvocations(holds);
        reserve(seat, buyer).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(reservation.getId()));
        assertThat(reservations.count()).isEqualTo(1);
        verify(holds, never()).isHeldBy(anyLong(), anyLong());
        admission(seat, other).andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.reservationId").doesNotExist());
        mvc.perform(delete("/reservations/{id}", reservation.getId())
                        .with(jwt().jwt(token -> token.subject(buyer.getId().toString()))))
                .andExpect(status().isNoContent());
        admission(seat, buyer).andExpect(status().isForbidden());
        assertThat(gateway.approveCount()).isZero();
    }

    @Test
    void freeRequiresOwnLiveHoldAndRejectsAnotherReservation() throws Exception {
        SeatEntity seat = seat(SeatGrade.FREE, 0);
        given(holds.isHeldBy(seat.getId(), buyer.getId())).willReturn(false);
        reserve(seat, buyer).andExpect(status().isConflict());
        assertThat(reservations.count()).isZero();
        given(holds.isHeldBy(seat.getId(), buyer.getId())).willReturn(true);
        reserve(seat, buyer).andExpect(status().isCreated());
        reserve(seat, other).andExpect(status().isConflict());
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    void cancelledFreeSeatCanBeBookedByAnotherUser() throws Exception {
        SeatEntity seat = seat(SeatGrade.FREE, 0);
        ReservationResponse first = reservationService.reserveFree(event.getId(), seat.getId(), buyer.getId());
        reservationService.cancel(first.reservationId(), buyer.getId());
        ReservationResponse next = reservationService.reserveFree(event.getId(), seat.getId(), other.getId());
        assertThat(next.reservationId()).isNotEqualTo(first.reservationId());
        admission(seat, buyer).andExpect(status().isForbidden());
        admission(seat, other).andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = SeatGrade.class, names = {"STANDARD", "PREMIUM", "VIP"})
    void paidGradesCannotBypassPayment(SeatGrade grade) throws Exception {
        SeatEntity seat = seat(grade, 3000);
        reserve(seat, buyer).andExpect(status().isBadRequest());
        assertThat(reservations.count()).isZero();
        assertThat(payments.count()).isZero();
        admission(seat, buyer).andExpect(status().isForbidden());
    }

    @Test
    void paidFlowUsesSnapshotAndRevokesAdmissionEvenWhenRefundFails() throws Exception {
        SeatEntity seat = seat(SeatGrade.PREMIUM, 5000);
        PaymentResponse order = paymentService.prepare(event.getId(), seat.getId(), buyer.getId());
        admission(seat, buyer).andExpect(status().isForbidden());
        // The ticket grade comes from the prepared order, not the current seat grade.
        jdbc.update("UPDATE seat_entity SET grade='FREE',price=0 WHERE id=?", seat.getId());
        PaymentResponse paid = paymentService.confirm(buyer.getId(), order.orderId(), "paid-key", order.amount());
        assertThat(reservations.findById(paid.reservationId()).orElseThrow().getAdmissionType())
                .isEqualTo(AdmissionType.PAID);
        admission(seat, buyer).andExpect(status().isOk()).andExpect(jsonPath("$.grade").value("PREMIUM"));
        admission(seat, other).andExpect(status().isForbidden());
        gateway.failNextCancels(1);
        reservationService.cancel(paid.reservationId(), buyer.getId());
        assertThat(payments.findById(paid.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        admission(seat, buyer).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void onlySuccessfulPaymentGrantsPaidAdmission(PaymentStatus state) throws Exception {
        SeatEntity seat = seat(SeatGrade.VIP, 10000);
        ReservationEntity reservation = reservations.save(ReservationEntity.reserved(buyer, seat));
        PaymentEntity payment = payments.save(PaymentEntity.ready(UUID.randomUUID().toString(), buyer, seat, 10000, "FAKE"));
        jdbc.update("UPDATE payments SET status=?,reservation_id=? WHERE id=?", state.name(), reservation.getId(), payment.getId());
        admission(seat, buyer).andExpect(state == PaymentStatus.SUCCESS ? status().isOk() : status().isForbidden());
    }

    @Test
    void legacyUnpaidReservationDoesNotBecomeFreeWhenSeatChanges() throws Exception {
        SeatEntity seat = seat(SeatGrade.STANDARD, 3000);
        reservations.save(ReservationEntity.reserved(buyer, seat));
        jdbc.update("UPDATE seat_entity SET grade='FREE',price=0 WHERE id=?", seat.getId());
        admission(seat, buyer).andExpect(status().isForbidden());
        reserve(seat, buyer).andExpect(status().isConflict());
        assertThat(payments.count()).isZero();
    }

    @Test
    void freeAdmissionAndReplayRetainOriginalTypeAfterSeatChanges() throws Exception {
        SeatEntity seat = seat(SeatGrade.FREE, 0);
        ReservationResponse reservation = reservationService.reserveFree(event.getId(), seat.getId(), buyer.getId());
        jdbc.update("UPDATE seat_entity SET grade='VIP',price=10000 WHERE id=?", seat.getId());
        admission(seat, buyer).andExpect(status().isOk()).andExpect(jsonPath("$.grade").value("FREE"));
        reserve(seat, buyer).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(reservation.reservationId()));
    }

    @Test
    void paymentMustMatchReservationOwnerAndSeat() throws Exception {
        SeatEntity seat = seat(SeatGrade.VIP, 10000);
        SeatEntity anotherSeat = seat(SeatGrade.STANDARD, 3000);
        ReservationEntity reservation = reservations.save(ReservationEntity.reserved(buyer, seat));
        PaymentEntity payment = payments.save(PaymentEntity.ready("mismatched", other, anotherSeat, 3000, "FAKE"));
        jdbc.update("UPDATE payments SET status='SUCCESS',reservation_id=? WHERE id=?", reservation.getId(), payment.getId());
        admission(seat, buyer).andExpect(status().isForbidden());
    }

    @Test
    void authenticationAndEventBindingAreRequired() throws Exception {
        SeatEntity seat = seat(SeatGrade.FREE, 0);
        String path = path(seat);
        mvc.perform(post(path + "/free-reservations")).andExpect(status().isUnauthorized());
        mvc.perform(get(path + "/admission")).andExpect(status().isUnauthorized());
        reservationService.reserveFree(event.getId(), seat.getId(), buyer.getId());
        EventEntity another = events.save(new EventEntity("다른 공연", LocalDateTime.now().plusDays(7)));
        mvc.perform(get("/events/{event}/seats/{seat}/admission", another.getId(), seat.getId())
                        .with(jwt().jwt(token -> token.subject(buyer.getId().toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/events/{event}/seats/{seat}/free-reservations", another.getId(), seat.getId())
                        .with(jwt().jwt(token -> token.subject(buyer.getId().toString()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRetriesReturnOneReservation() throws Exception {
        SeatEntity seat = seat(SeatGrade.FREE, 0);
        CountDownLatch bothCheckedHold = new CountDownLatch(2);
        given(holds.isHeldBy(seat.getId(), buyer.getId())).willAnswer(invocation -> {
            bothCheckedHold.countDown();
            if (!bothCheckedHold.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent request did not arrive");
            return true;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ReservationResponse> call = () -> reservationService.reserveFree(event.getId(), seat.getId(), buyer.getId());
            Future<ReservationResponse> first = pool.submit(call);
            Future<ReservationResponse> second = pool.submit(call);
            assertThat(first.get(30, TimeUnit.SECONDS).reservationId())
                    .isEqualTo(second.get(30, TimeUnit.SECONDS).reservationId());
            assertThat(reservations.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void databasePreventsDoubleBookingEvenIfRedisAllowsCompetingUsers() throws Exception {
        SeatEntity seat = seat(SeatGrade.FREE, 0);
        List<Long> competitors = new ArrayList<>();
        for (int i = 0; i < 12; i++) competitors.add(users.save(new UserEntity("race" + i + "@test.com", "password")).getId());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Long user : competitors) results.add(pool.submit(() -> {
                start.await();
                try {
                    reservationService.reserveFree(event.getId(), seat.getId(), user);
                    return true;
                } catch (SeatUnavailableException expected) {
                    return false;
                }
            }));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(reservations.count()).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SeatEntity seat(SeatGrade grade, long price) {
        return seats.save(new SeatEntity(event, Math.toIntExact(seats.count()) + 1, grade, price));
    }

    private String path(SeatEntity seat) {
        return "/events/" + event.getId() + "/seats/" + seat.getId();
    }

    private ResultActions reserve(SeatEntity seat, UserEntity user) throws Exception {
        return mvc.perform(post(path(seat) + "/free-reservations")
                .with(jwt().jwt(token -> token.subject(user.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"FREE\",\"price\":0}"));
    }

    private ResultActions admission(SeatEntity seat, UserEntity user) throws Exception {
        return mvc.perform(get(path(seat) + "/admission")
                .with(jwt().jwt(token -> token.subject(user.getId().toString()))));
    }
}
