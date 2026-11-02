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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, PaymentControllerTest.MetricsConfig.class})
@TestPropertySource(properties = "payment.toss.client-key=test_ck_public")
class PaymentControllerTest {

    private static final String CONFIRM_BODY = """
            {"paymentKey": "key-1", "orderId": "order-1", "amount": 50000}
            """;

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
    @DisplayName("주문을 만들면 201과 주문번호, 금액을 반환한다")
    void prepare() throws Exception {
        given(paymentService.prepare(1L, 100L, 7L)).willReturn(response(null, PaymentStatus.READY));

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/payments", 1L, 100L)
                        .with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    @DisplayName("결제를 확정하면 200과 확정된 결제 정보를 반환한다")
    void confirm() throws Exception {
        given(paymentService.confirm(7L, "order-1", "key-1", 50_000L)).willReturn(response(20L, PaymentStatus.SUCCESS));

        mockMvc.perform(confirmRequest().with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(10))
                .andExpect(jsonPath("$.reservationId").value(20))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("결제사 승인 결과를 아직 모르면 202와 PROCESSING 상태를 반환한다")
    void confirmProcessing() throws Exception {
        given(paymentService.confirm(7L, "order-1", "key-1", 50_000L)).willReturn(response(null, PaymentStatus.PROCESSING));

        mockMvc.perform(confirmRequest().with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.reservationId").doesNotExist());
    }

    @Test
    @DisplayName("필수값이 비어 있으면 400을 반환하고 서비스를 호출하지 않는다")
    void confirmBlankPaymentKey() throws Exception {
        mockMvc.perform(post("/payments/confirm")
                        .with(jwt().jwt(token -> token.subject("7")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentKey": "", "orderId": "order-1", "amount": 50000}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("토큰이 없으면 결제를 확정할 수 없다")
    void confirmWithoutToken() throws Exception {
        mockMvc.perform(confirmRequest())
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("결제창 설정에는 클라이언트 키와, 사용자 id가 드러나지 않는 구매자 키를 준다")
    void clientConfig() throws Exception {
        mockMvc.perform(get("/payments/client-config").with(jwt().jwt(token -> token.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientKey").value("test_ck_public"))
                // 토스 customerKey 규칙: 22~50자
                .andExpect(jsonPath("$.customerKey").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmRequest() {
        return post("/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CONFIRM_BODY);
    }

    private PaymentResponse response(Long reservationId, PaymentStatus status) {
        return new PaymentResponse(10L, "order-1", reservationId, 100L, 50_000L, "KRW", status);
    }
}
