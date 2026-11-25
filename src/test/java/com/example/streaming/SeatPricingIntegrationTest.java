package com.example.streaming;

import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.*;
import com.example.streaming.Repository.*;
import com.example.streaming.Service.FakePaymentGateway;
import com.example.streaming.Service.PaymentService;
import com.example.streaming.Service.RedisSeatHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seat-pricing;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "payment.gateway=fake"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeatPricingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EventRepository events;
    @Autowired SeatRepository seats;
    @Autowired UserRepository users;
    @Autowired PaymentRepository payments;
    @Autowired ReservationRepository reservations;
    @Autowired PaymentService paymentService;
    @Autowired FakePaymentGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RedisSeatHoldService holds;

    UserEntity buyer;

    @BeforeEach
    void setUp() {
        payments.deleteAll();
        reservations.deleteAll();
        seats.deleteAll();
        events.deleteAll();
        users.deleteAll();
        gateway.reset();
        buyer = users.save(new UserEntity("buyer@pricing.test", "password"));
        given(holds.isHeldBy(anyLong(), anyLong())).willReturn(true);
        given(holds.getHolders(anyList())).willAnswer(invocation ->
                Collections.nCopies(((List<?>) invocation.getArgument(0)).size(), null));
    }

    @Test
    void createsMixedGradesAndReturnsStoredPrices() throws Exception {
        create("\"seatSections\":[{\"grade\":\"FREE\",\"seatCount\":2,\"price\":0},"
                + "{\"grade\":\"STANDARD\",\"seatCount\":1,\"price\":3000},"
                + "{\"grade\":\"PREMIUM\",\"seatCount\":1,\"price\":5000},"
                + "{\"grade\":\"VIP\",\"seatCount\":1,\"price\":10000}]")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.seatCount").value(5));
        Long eventId = events.findAll().getFirst().getId();
        List<SeatEntity> saved = seats.findAllByEvent_IdOrderBySeatNumber(eventId);
        assertThat(saved).extracting(SeatEntity::getSeatNumber).containsExactly(1, 2, 3, 4, 5);
        mvc.perform(get("/events/{id}/seats", eventId).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grade").value("FREE"))
                .andExpect(jsonPath("$[0].price").value(0))
                .andExpect(jsonPath("$[2].price").value(3000))
                .andExpect(jsonPath("$[3].grade").value("PREMIUM"))
                .andExpect(jsonPath("$[4].price").value(10000));
        mvc.perform(get("/events/seats/{id}", saved.getLast().getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grade").value("VIP"))
                .andExpect(jsonPath("$.price").value(10000));
    }

    @Test
    void legacyRequestStillCreatesStandardSeats() throws Exception {
        create("\"seatCount\":2").andExpect(status().isCreated());
        assertThat(seats.findAll()).allSatisfy(seat -> {
            assertThat(seat.getGrade()).isEqualTo(SeatGrade.STANDARD);
            assertThat(seat.getPrice()).isEqualTo(50_000L);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "\"seatCount\":0", "\"seatCount\":1001", "\"seatSections\":[]",
            "\"seatSections\":[null]",
            "\"seatCount\":1,\"seatSections\":[{\"grade\":\"VIP\",\"seatCount\":1,\"price\":10000}]",
            "\"seatSections\":[{\"grade\":\"FREE\",\"seatCount\":1,\"price\":1}]",
            "\"seatSections\":[{\"grade\":\"VIP\",\"seatCount\":1,\"price\":0}]",
            "\"seatSections\":[{\"grade\":\"STANDARD\",\"seatCount\":1,\"price\":-1}]",
            "\"seatSections\":[{\"seatCount\":1,\"price\":1000}]",
            "\"seatSections\":[{\"grade\":\"VIP\",\"seatCount\":1}]",
            "\"seatSections\":[{\"grade\":\"VIP\",\"seatCount\":0,\"price\":10000}]",
            "\"seatSections\":[{\"grade\":\"VIP\",\"seatCount\":1,\"price\":10000},{\"grade\":\"VIP\",\"seatCount\":1,\"price\":20000}]",
            "\"seatSections\":[{\"grade\":\"FREE\",\"seatCount\":600,\"price\":0},{\"grade\":\"VIP\",\"seatCount\":401,\"price\":10000}]"
    })
    void rejectsInvalidSectionsWithoutPartialData(String configuration) throws Exception {
        create(configuration).andExpect(status().isBadRequest());
        assertThat(events.count()).isZero();
        assertThat(seats.count()).isZero();
    }

    @Test
    void ordinaryUserCannotSetSeatPrices() throws Exception {
        mvc.perform(post("/events").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"seatSections\":[{\"grade\":\"VIP\",\"seatCount\":1,\"price\":1}]")))
                .andExpect(status().isForbidden());
        assertThat(events.count()).isZero();
    }

    @Test
    void usesServerPriceAndPreservesOrderSnapshotAfterSeatChanges() throws Exception {
        SeatEntity seat = seat(SeatGrade.PREMIUM, 5_000L);
        mvc.perform(post("/events/{event}/seats/{seat}/payments", seat.getEvent().getId(), seat.getId())
                        .with(jwt().jwt(token -> token.subject(buyer.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"grade\":\"FREE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.seatGrade").value("PREMIUM"));
        PaymentEntity payment = payments.findAll().getFirst();
        jdbc.update("UPDATE seat_entity SET price=10000, grade='VIP' WHERE id=?", seat.getId());
        PaymentResponse snapshot = paymentService.getPayment(buyer.getId(), payment.getOrderId());
        assertThat(snapshot.amount()).isEqualTo(5_000L);
        assertThat(snapshot.seatGrade()).isEqualTo(SeatGrade.PREMIUM);
        PaymentResponse paid = paymentService.confirm(buyer.getId(), snapshot.orderId(), "fake-price-key", 5_000L);
        assertThat(paid.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paid.amount()).isEqualTo(5_000L);
        assertThat(paid.seatGrade()).isEqualTo(SeatGrade.PREMIUM);
        assertThat(gateway.approveCount()).isEqualTo(1);
    }

    @Test
    void rejectsTamperedConfirmationBeforeCallingGateway() throws Exception {
        SeatEntity seat = seat(SeatGrade.VIP, 10_000L);
        PaymentResponse order = paymentService.prepare(seat.getEvent().getId(), seat.getId(), buyer.getId());
        mvc.perform(post("/payments/confirm").with(jwt().jwt(token -> token.subject(buyer.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order.orderId() + "\",\"paymentKey\":\"fake-tampered\",\"amount\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(gateway.approveCount()).isZero();
        assertThat(payments.findById(order.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    void freeSeatDoesNotCreatePaymentOrCallGateway() throws Exception {
        SeatEntity seat = seat(SeatGrade.FREE, 0L);
        mvc.perform(post("/events/{event}/seats/{seat}/payments", seat.getEvent().getId(), seat.getId())
                        .with(jwt().jwt(token -> token.subject(buyer.getId().toString()))))
                .andExpect(status().isBadRequest());
        assertThat(payments.count()).isZero();
        assertThat(gateway.approveCount()).isZero();
    }

    private SeatEntity seat(SeatGrade grade, long price) {
        EventEntity event = events.save(new EventEntity("공연", LocalDateTime.now().plusDays(7)));
        return seats.save(new SeatEntity(event, 1, grade, price));
    }

    private org.springframework.test.web.servlet.ResultActions create(String configuration) throws Exception {
        return mvc.perform(post("/events").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body(configuration)));
    }

    private String body(String configuration) {
        return "{\"title\":\"등급 공연\",\"startTime\":\"2099-12-25T19:00:00\""
                + (configuration.isEmpty() ? "" : "," + configuration) + "}";
    }
}
