package com.example.streaming.Controller;

import com.example.streaming.Config.SecurityConfig;
import com.example.streaming.Dto.EventCreateRequest;
import com.example.streaming.Dto.EventResponse;
import com.example.streaming.Dto.HoldResponse;
import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Dto.SeatResponse;
import com.example.streaming.Entity.ReservationStatus;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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

    @Test
    @DisplayName("이벤트 생성 시 201과 생성된 이벤트를 반환한다")
    void createEvent() throws Exception {
        LocalDateTime startTime = LocalDateTime.of(2026, 12, 25, 19, 0);
        given(eventService.createEvent(any(EventCreateRequest.class)))
                .willReturn(new EventResponse(1L, "콘서트", startTime));

        mockMvc.perform(post("/events").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "콘서트", "startTime": "2026-12-25T19:00:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("콘서트"));
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

    @Test
    @DisplayName("예약 시 201과 예약 정보를 반환한다")
    void reserve() throws Exception {
        given(reservationService.reserve(1L, 100L, 7L))
                .willReturn(new ReservationResponse(999L, 7L, 100L, ReservationStatus.RESERVED));

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/reservations", 1L, 100L)
                        .with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(999))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    @DisplayName("토큰이 없으면 401을 반환한다")
    void reserve_withoutToken() throws Exception {
        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/reservations", 1L, 100L))
                .andExpect(status().isUnauthorized());
    }
}
