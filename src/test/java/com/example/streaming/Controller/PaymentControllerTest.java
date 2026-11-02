package com.example.streaming.Controller;

import com.example.streaming.Config.SecurityConfig;
import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Service.CustomUserDetailService;
import com.example.streaming.Service.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, PaymentControllerTest.MetricsConfig.class})
class PaymentControllerTest {

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
    PaymentService paymentService;

    @MockitoBean
    CustomUserDetailService customUserDetailService;

    @Test
    @DisplayName("선점한 좌석을 결제하면 201과 확정된 결제 정보를 반환한다")
    void pay() throws Exception {
        given(paymentService.pay(1L, 100L, 7L, "order-1"))
                .willReturn(new PaymentResponse(
                        10L,
                        "order-1",
                        20L,
                        100L,
                        50_000L,
                        "KRW",
                        PaymentStatus.SUCCESS
                ));

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/payments", 1L, 100L)
                        .with(jwt().jwt(token -> token.subject("7")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": "order-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(10))
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.reservationId").value(20))
                .andExpect(jsonPath("$.seatId").value(100))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("결제사 승인 결과를 아직 모르면 202와 PROCESSING 상태를 반환한다")
    void payProcessing() throws Exception {
        given(paymentService.pay(1L, 100L, 7L, "order-1"))
                .willReturn(new PaymentResponse(
                        10L,
                        "order-1",
                        null,
                        100L,
                        50_000L,
                        "KRW",
                        PaymentStatus.PROCESSING
                ));

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/payments", 1L, 100L)
                        .with(jwt().jwt(token -> token.subject("7")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": "order-1"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.reservationId").doesNotExist());
    }

    @Test
    @DisplayName("주문번호가 비어 있으면 400을 반환한다")
    void blankOrderId() throws Exception {
        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/payments", 1L, 100L)
                        .with(jwt().jwt(token -> token.subject("7")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("토큰이 없으면 결제할 수 없다")
    void payWithoutToken() throws Exception {
        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/payments", 1L, 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": "order-1"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
