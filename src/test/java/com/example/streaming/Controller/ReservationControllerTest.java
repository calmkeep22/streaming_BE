package com.example.streaming.Controller;

import com.example.streaming.Config.SecurityConfig;
import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Exception.ForbiddenException;
import com.example.streaming.Service.CustomUserDetailService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
@Import({SecurityConfig.class, ReservationControllerTest.MetricsConfig.class})
class ReservationControllerTest {

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
    CustomUserDetailService customUserDetailService;

    @Test
    @DisplayName("예약 취소 시 토큰의 사용자로 처리하고 204를 반환한다")
    void cancel() throws Exception {
        mockMvc.perform(delete("/reservations/{reservationId}", 999L)
                        .with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isNoContent());
        verify(reservationService).cancel(999L, 7L);
    }

    @Test
    @DisplayName("타인의 예약을 취소하면 403을 반환한다")
    void cancel_forbidden() throws Exception {
        willThrow(new ForbiddenException("예약자가 아닙니다")).given(reservationService).cancel(999L, 7L);
        mockMvc.perform(delete("/reservations/{reservationId}", 999L)
                        .with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("예약자가 아닙니다"));
    }

    @Test
    @DisplayName("예약 취소에 토큰이 없으면 401을 반환하고 서비스를 호출하지 않는다")
    void cancel_withoutToken() throws Exception {
        mockMvc.perform(delete("/reservations/{reservationId}", 999L))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("존재하지 않는 예약 취소는 기존 예외 정책에 따라 400을 반환한다")
    void cancel_notFound() throws Exception {
        willThrow(new IllegalArgumentException("예약이 존재하지 않습니다")).given(reservationService).cancel(999L, 7L);
        mockMvc.perform(delete("/reservations/{reservationId}", 999L)
                        .with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("내 예약 조회 시 토큰의 사용자 예약 목록을 반환한다")
    void myReservations() throws Exception {
        given(reservationService.findMyReservations(7L))
                .willReturn(List.of(
                        new ReservationResponse(999L, 7L, 100L, ReservationStatus.RESERVED),
                        new ReservationResponse(998L, 7L, 101L, ReservationStatus.CANCELED)));

        mockMvc.perform(get("/reservations/me")
                        .with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reservationId").value(999))
                .andExpect(jsonPath("$[1].status").value("CANCELED"));
    }

    @Test
    @DisplayName("내 예약 조회에 토큰이 없으면 401을 반환한다")
    void myReservations_withoutToken() throws Exception {
        mockMvc.perform(get("/reservations/me"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reservationService);
    }
}
