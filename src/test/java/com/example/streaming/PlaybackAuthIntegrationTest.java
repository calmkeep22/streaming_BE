package com.example.streaming;

import com.example.streaming.Config.MediaProperties;
import com.example.streaming.Dto.PlaybackTicketResponse;
import com.example.streaming.Entity.*;
import com.example.streaming.Repository.*;
import com.example.streaming.Service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:playback-auth;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlaybackAuthIntegrationTest {
    static final String MEDIA_KEY = Base64.getEncoder().encodeToString("media-only-signing-secret-32-bytes!".getBytes(StandardCharsets.UTF_8));
    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("media.enabled", () -> "true");
        registry.add("media.token-secret", () -> MEDIA_KEY);
        registry.add("media.publisher-password-hash", () -> hash("publisher-test"));
        registry.add("media.monitor-password-hash", () -> hash("monitor-test"));
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Autowired MockMvc mvc;
    @Autowired PlaybackService playback;
    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired PaymentRepository payments;
    @Autowired ReservationRepository reservations;
    @Autowired SeatRepository seats;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @MockitoBean RedisSeatHoldService holds;
    UserEntity user;
    SeatEntity seat;
    EventEntity event;

    @BeforeEach
    void setUp() {
        payments.deleteAll(); reservations.deleteAll(); seats.deleteAll(); events.deleteAll(); users.deleteAll();
        user = users.save(new UserEntity("playback@test.com", "password"));
        event = events.save(new EventEntity("공연", LocalDateTime.now().plusDays(7)));
        seat = seats.save(new SeatEntity(event, 1, SeatGrade.FREE, 0L));
        given(holds.isHeldBy(anyLong(), anyLong())).willReturn(true);
    }

    @Test
    void freeTicketAndCallbackAreScopedAndNotCacheable() throws Exception {
        mvc.perform(post(ticketPath())).andExpect(status().isUnauthorized());
        mvc.perform(post(ticketPath()).with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isForbidden());
        reserve();
        mvc.perform(post(ticketPath()).with(jwt().jwt(token -> token.subject(user.getId().toString())
                        .expiresAt(Instant.now().plusSeconds(3600)))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.path").value(path())).andExpect(jsonPath("$.whepPath").value("/" + path() + "/whep"))
                .andExpect(jsonPath("$.grade").value("FREE")).andExpect(jsonPath("$.token").isNotEmpty());
        PlaybackTicketResponse ticket = ticket();
        assertThat(ticket.expiresAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(120));
        callback(ticket.token(), "read", path(), "webrtc").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.expireAt").value(ticket.expiresAt().toString()));
    }

    @Test
    void cancellationRevokesExistingTokenAndRebookingDoesNotResurrectIt() throws Exception {
        var reservation = reserve();
        PlaybackTicketResponse old = ticket();
        reservationService.cancel(reservation.reservationId(), user.getId());
        callback(old.token(), "read", path(), "webrtc").andExpect(status().isUnauthorized());
        reserve();
        callback(old.token(), "read", path(), "webrtc").andExpect(status().isUnauthorized());
        callback(ticket().token(), "read", path(), "webrtc").andExpect(status().isOk());
    }

    @Test
    void paidTicketRequiresSuccessAndKeepsPaidGrade() throws Exception {
        seats.deleteAll();
        seat = seats.save(new SeatEntity(event, 1, SeatGrade.PREMIUM, 5000L));
        var order = paymentService.prepare(event.getId(), seat.getId(), user.getId());
        mvc.perform(post(ticketPath()).with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isForbidden());
        var paid = paymentService.confirm(user.getId(), order.orderId(), "playback-test", order.amount());
        PlaybackTicketResponse ticket = ticket();
        assertThat(ticket.grade()).isEqualTo(SeatGrade.PREMIUM);
        callback(ticket.token(), "read", path(), "webrtc").andExpect(status().isOk());
        reservationService.cancel(paid.reservationId(), user.getId());
        callback(ticket.token(), "read", path(), "webrtc").andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"events/0", "events/01", "events/999999", "events/1/extra", "other", "events/9223372036854775808"})
    void ticketCannotReadAnotherOrMalformedPath(String requestedPath) throws Exception {
        reserve();
        callback(ticket().token(), "read", requestedPath, "webrtc").andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"publish", "api", "metrics", "pprof", "playback"})
    void readerCannotEscalateActions(String action) throws Exception {
        reserve();
        callback(ticket().token(), action, path(), "webrtc").andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rtmp", "rtsp", "hls", "", "srt"})
    void readerIsLimitedToWebrtc(String protocol) throws Exception {
        reserve();
        callback(ticket().token(), "read", path(), protocol).andExpect(status().isUnauthorized());
    }

    @Test
    void loginAndPlaybackCredentialsCannotBeInterchanged() throws Exception {
        reserve();
        String login = jwtEncoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .subject(user.getId().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build()))
                .getTokenValue();
        callback(login, "read", path(), "webrtc").andExpect(status().isUnauthorized());
        mvc.perform(get("/events").header("Authorization", "Bearer " + ticket().token()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "no-expiry", "issuer", "audience", "purpose", "claims"})
    void rejectsInvalidSignedTickets(String change) throws Exception {
        var reservation = reserve();
        var claims = JwtClaimsSet.builder().subject(user.getId().toString()).issuer(MediaTicketCodec.ISSUER)
                .audience(List.of(MediaTicketCodec.AUDIENCE)).issuedAt(Instant.now().minusSeconds(5))
                .claim("purpose", "media-read").claim("eventId", event.getId()).claim("seatId", seat.getId())
                .claim("reservationId", reservation.reservationId()).claim("grade", "FREE");
        if (!change.equals("no-expiry")) claims.expiresAt(Instant.now().plusSeconds(60));
        switch (change) {
            case "expired" -> claims.expiresAt(Instant.now().minusSeconds(1));
            case "issuer" -> claims.issuer("another-issuer");
            case "audience" -> claims.audience(List.of("backend"));
            case "purpose" -> claims.claim("purpose", "login");
            case "claims" -> claims.claim("seatId", "not-a-number");
        }
        var encoder = NimbusJwtEncoder.withSecretKey(new javax.crypto.spec.SecretKeySpec(
                Base64.getDecoder().decode(MEDIA_KEY), "HmacSHA256")).build();
        String token = encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
        callback(token, "read", path(), "webrtc").andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
    }

    @Test
    void machineCredentialsAreActionSpecificAndOldViewerIsRejected() throws Exception {
        machine("publisher", "publisher-test", "publish", path()).andExpect(status().isOk());
        machine("publisher", "wrong", "publish", path()).andExpect(status().isUnauthorized());
        machine("publisher", "publisher-test", "publish", "events/999999").andExpect(status().isUnauthorized());
        machine("publisher", "publisher-test", "api", "").andExpect(status().isUnauthorized());
        machine("monitor", "monitor-test", "api", "").andExpect(status().isOk());
        machine("monitor", "monitor-test", "publish", path()).andExpect(status().isUnauthorized());
        machine("viewer", "old-shared-password", "read", path()).andExpect(status().isUnauthorized());
    }

    @Test
    void ticketCannotOutliveLoginOrBeIssuedForAnotherUsersSeat() throws Exception {
        reserve();
        Instant loginExpiry = Instant.now().plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        assertThat(playback.issue(event.getId(), seat.getId(), user.getId(), loginExpiry).expiresAt()).isEqualTo(loginExpiry);
        UserEntity other = users.save(new UserEntity("other@playback.test", "password"));
        mvc.perform(post(ticketPath()).with(jwt().jwt(token -> token.subject(other.getId().toString()))))
                .andExpect(status().isForbidden());
        callback("", "read", path(), "webrtc").andExpect(status().isUnauthorized());
    }

    private com.example.streaming.Dto.ReservationResponse reserve() {
        return reservationService.reserveFree(event.getId(), seat.getId(), user.getId());
    }
    private PlaybackTicketResponse ticket() { return playback.issue(event.getId(), seat.getId(), user.getId(), Instant.now().plusSeconds(3600)); }
    private String path() { return "events/" + event.getId(); }
    private String ticketPath() { return "/events/" + event.getId() + "/seats/" + seat.getId() + "/playback-tickets"; }
    private ResultActions callback(String token, String action, String path, String protocol) throws Exception {
        return mvc.perform(post("/internal/media/auth").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"action\":\"" + action + "\",\"path\":\"" + path
                        + "\",\"protocol\":\"" + protocol + "\",\"ip\":\"untrusted\",\"query\":\"userId=999\",\"userAgent\":\"test\"}"));
    }
    private ResultActions machine(String user, String password, String action, String path) throws Exception {
        return mvc.perform(post("/internal/media/auth").contentType(MediaType.APPLICATION_JSON)
                .content("{\"user\":\"" + user + "\",\"password\":\"" + password + "\",\"action\":\"" + action
                        + "\",\"path\":\"" + path + "\",\"protocol\":\"rtmp\"}"));
    }
}
