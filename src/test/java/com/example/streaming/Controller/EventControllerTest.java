package com.example.streaming.Controller;

import com.example.streaming.Config.SecurityConfig;
import com.example.streaming.Dto.EventCreateRequest;
import com.example.streaming.Dto.EventResponse;
import com.example.streaming.Dto.HoldResponse;
import com.example.streaming.Dto.SeatResponse;
import com.example.streaming.Entity.SeatStatus;
import com.example.streaming.Service.CustomUserDetailService;
import com.example.streaming.Service.EventService;
import com.example.streaming.Service.ReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
@Import({SecurityConfig.class, EventControllerTest.MetricsConfig.class})
class EventControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReservationService reservationService;
    @MockitoBean
    EventService eventService;
    @MockitoBean
    CustomUserDetailService customUserDetailService;

    @Autowired
    JwtEncoder jwtEncoder;

    static final String CREATE_EVENT_BODY = """
            {"title": "콘서트", "startTime": "2099-12-25T19:00:00", "seatCount": 100}
            """;

    @Test
    @DisplayName("관리자가 이벤트를 생성하면 201과 생성된 이벤트, 좌석 수를 반환한다")
    void createEvent() throws Exception {
        LocalDateTime startTime = LocalDateTime.of(2099, 12, 25, 19, 0);
        given(eventService.createEvent(any(EventCreateRequest.class)))
                .willReturn(new EventResponse(1L, "콘서트", startTime, 100));

        mockMvc.perform(post("/events").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_EVENT_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("콘서트"))
                .andExpect(jsonPath("$.seatCount").value(100));
    }

    @Test
    @DisplayName("로그인 토큰의 role 클레임이 ADMIN이면 이벤트를 생성할 수 있다")
    void createEvent_withAdminToken() throws Exception {
        given(eventService.createEvent(any(EventCreateRequest.class)))
                .willReturn(new EventResponse(1L, "콘서트", LocalDateTime.of(2099, 12, 25, 19, 0), 100));

        mockMvc.perform(post("/events").header("Authorization", "Bearer " + token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_EVENT_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("일반 사용자가 이벤트를 생성하면 403을 반환하고 생성하지 않는다")
    void createEvent_byUser() throws Exception {
        mockMvc.perform(post("/events").header("Authorization", "Bearer " + token("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_EVENT_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(eventService);
    }

    @Test
    @DisplayName("토큰 없이 이벤트를 생성하면 401을 반환한다")
    void createEvent_withoutToken() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_EVENT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("좌석 수가 범위를 벗어나거나 시작 시각이 지났으면 400을 반환한다")
    void createEvent_invalidRequest() throws Exception {
        for (String body : new String[]{
                """
                {"title": "콘서트", "startTime": "2099-12-25T19:00:00", "seatCount": 0}
                """,
                """
                {"title": "콘서트", "startTime": "2099-12-25T19:00:00", "seatCount": 1001}
                """,
                """
                {"title": "콘서트", "startTime": "2000-01-01T19:00:00", "seatCount": 100}
                """,
                """
                {"title": "", "startTime": "2099-12-25T19:00:00", "seatCount": 100}
                """}) {
            mockMvc.perform(post("/events").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(eventService);
    }

    @Test
    @DisplayName("이벤트 좌석 목록 조회 시 200과 좌석별 상태를 반환한다")
    void getSeats() throws Exception {
        given(eventService.getSeats(1L)).willReturn(List.of(
                new SeatResponse(100L, 1, SeatStatus.AVAILABLE),
                new SeatResponse(101L, 2, SeatStatus.HELD),
                new SeatResponse(102L, 3, SeatStatus.RESERVED)));

        mockMvc.perform(get("/events/{eventId}/seats", 1L).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].seatNumber").value(1))
                .andExpect(jsonPath("$[1].status").value("HELD"))
                .andExpect(jsonPath("$[2].status").value("RESERVED"));
    }

    private String token(String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("7")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    @DisplayName("좌석 조회 시 200과 좌석 정보를 반환한다")
    void getSeat() throws Exception {
        given(eventService.getSeat(100L))
                .willReturn(new SeatResponse(100L, 10, SeatStatus.AVAILABLE));

        mockMvc.perform(get("/events/seats/{seatId}", 100L).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.seatNumber").value(10))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("좌석 선점 시 201과 HELD 상태, 남은 선점 시간을 반환한다")
    void hold() throws Exception {
        given(reservationService.holdSeat(1L, 100L, 7L))
                .willReturn(new HoldResponse(100L, 7L, SeatStatus.HELD, 300L));

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/holds", 1L, 100L)
                        .with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seatId").value(100))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.remainingSeconds").value(300));
    }

}
