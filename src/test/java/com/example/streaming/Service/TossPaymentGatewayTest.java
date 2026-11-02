package com.example.streaming.Service;

import com.example.streaming.Exception.PaymentGatewayException;
import com.example.streaming.Service.PaymentGateway.InquiryStatus;
import com.example.streaming.Service.PaymentGateway.PaymentApproval;
import com.example.streaming.Service.PaymentGateway.PaymentInquiry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 토스 응답을 "승인 / 확정 실패 / 결과 불명"으로 올바르게 나누는지 확인한다.
 * 실제 네트워크 대신 가짜 HTTP 서버를 쓰므로 키 없이 돌아간다.
 */
class TossPaymentGatewayTest {

    private static final String BASE_URL = "https://api.tosspayments.com";

    MockRestServiceServer server;
    TossPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new TossPaymentGateway(builder.build());
    }

    @Test
    @DisplayName("인증 헤더는 시크릿 키 뒤에 콜론을 붙여 Base64로 인코딩한다")
    void basicAuth() {
        String encoded = TossPaymentGateway.basicAuth("test_sk_abc").substring("Basic ".length());

        assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)).isEqualTo("test_sk_abc:");
    }

    // ---------- 승인 ----------

    @Test
    @DisplayName("승인 요청은 멱등키와 함께 나가고, DONE이면 성공이다")
    void approveSuccess() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "confirm-order-1"))
                .andExpect(jsonPath("$.paymentKey").value("key-1"))
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.amount").value(50000))
                .andRespond(withSuccess("""
                        {"paymentKey": "key-1", "orderId": "order-1", "status": "DONE", "totalAmount": 50000}
                        """, MediaType.APPLICATION_JSON));

        PaymentApproval approval = gateway.approve("key-1", "order-1", 50_000L);

        assertThat(approval.successful()).isTrue();
        assertThat(approval.providerPaymentKey()).isEqualTo("key-1");
        server.verify();
    }

    @Test
    @DisplayName("카드 거절처럼 결제사가 명확히 거절하면 확정 실패다")
    void approveDeclined() {
        respondToConfirm(HttpStatus.FORBIDDEN, """
                {"code": "REJECT_CARD_COMPANY", "message": "결제 승인이 거절되었습니다."}
                """);

        PaymentApproval approval = gateway.approve("key-1", "order-1", 50_000L);

        assertThat(approval.successful()).isFalse();
        assertThat(approval.failureCode()).isEqualTo("REJECT_CARD_COMPANY");
    }

    @Test
    @DisplayName("이미 처리된 결제 응답은 돈이 빠졌을 수 있으므로 결과 불명으로 다룬다")
    void approveAlreadyProcessedIsUncertain() {
        respondToConfirm(HttpStatus.BAD_REQUEST, """
                {"code": "ALREADY_PROCESSED_PAYMENT", "message": "이미 처리된 결제 입니다."}
                """);

        assertThatThrownBy(() -> gateway.approve("key-1", "order-1", 50_000L))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("결제사 5xx는 결과 불명이다 (본문이 JSON이 아니어도)")
    void approveServerErrorIsUncertain() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("bad gateway").contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> gateway.approve("key-1", "order-1", 50_000L))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("200이어도 DONE이 아니면 승인됐다고 보지 않는다")
    void approveUnexpectedStatusIsUncertain() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("""
                        {"paymentKey": "key-1", "orderId": "order-1", "status": "IN_PROGRESS"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.approve("key-1", "order-1", 50_000L))
                .isInstanceOf(PaymentGatewayException.class);
    }

    // ---------- 취소 ----------

    @Test
    @DisplayName("취소 사유는 200자로 잘라서 보낸다")
    void cancelTruncatesReason() {
        server.expect(requestTo(BASE_URL + "/v1/payments/key-1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.cancelReason").value("가".repeat(200)))
                .andRespond(withSuccess("""
                        {"paymentKey": "key-1", "status": "CANCELED"}
                        """, MediaType.APPLICATION_JSON));

        gateway.cancel("key-1", "가".repeat(300));

        server.verify();
    }

    @Test
    @DisplayName("이미 취소된 결제를 다시 취소하면 성공으로 끝난다 (멱등)")
    void cancelAlreadyCanceled() {
        server.expect(requestTo(BASE_URL + "/v1/payments/key-1/cancel"))
                .andRespond(errorBody(HttpStatus.BAD_REQUEST, """
                        {"code": "ALREADY_CANCELED_PAYMENT", "message": "이미 취소된 결제 입니다."}
                        """));

        assertThatCode(() -> gateway.cancel("key-1", "환불")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("그 밖의 취소 실패는 복구 작업이 재시도하도록 예외로 알린다")
    void cancelFailure() {
        server.expect(requestTo(BASE_URL + "/v1/payments/key-1/cancel"))
                .andRespond(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, """
                        {"code": "FAILED_INTERNAL_SYSTEM_PROCESSING", "message": "내부 시스템 처리 작업이 실패했습니다."}
                        """));

        assertThatThrownBy(() -> gateway.cancel("key-1", "환불"))
                .isInstanceOf(PaymentGatewayException.class);
    }

    // ---------- 조회 ----------

    @ParameterizedTest
    @CsvSource({
            "DONE, APPROVED",
            "CANCELED, CANCELED",
            "READY, IN_PROGRESS",
            "IN_PROGRESS, IN_PROGRESS",
            "ABORTED, NOT_FOUND",
            "EXPIRED, NOT_FOUND"
    })
    @DisplayName("토스 결제 상태를 우리 조회 상태로 바꾼다")
    void inquireMapsStatus(String tossStatus, InquiryStatus expected) {
        respondToInquiry(tossStatus);

        PaymentInquiry inquiry = gateway.inquire("order-1");

        assertThat(inquiry.status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("부분 취소는 자동으로 판단하지 않고 예외로 넘긴다")
    void inquirePartialCanceled() {
        respondToInquiry("PARTIAL_CANCELED");

        assertThatThrownBy(() -> gateway.inquire("order-1"))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("결제 기록이 없다는 코드는 NOT_FOUND다")
    void inquireNotFoundPayment() {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/order-1"))
                .andRespond(errorBody(HttpStatus.NOT_FOUND, """
                        {"code": "NOT_FOUND_PAYMENT", "message": "존재하지 않는 결제 정보 입니다."}
                        """));

        assertThat(gateway.inquire("order-1").status()).isEqualTo(InquiryStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("다른 404는 돈이 안 빠졌다는 근거가 아니므로 예외로 넘긴다")
    void inquireOtherNotFound() {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/order-1"))
                .andRespond(errorBody(HttpStatus.NOT_FOUND, """
                        {"code": "NOT_FOUND", "message": "존재하지 않는 정보 입니다."}
                        """));

        assertThatThrownBy(() -> gateway.inquire("order-1"))
                .isInstanceOf(PaymentGatewayException.class);
    }

    private void respondToConfirm(HttpStatus status, String body) {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm")).andRespond(errorBody(status, body));
    }

    private void respondToInquiry(String tossStatus) {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/order-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"paymentKey": "key-1", "orderId": "order-1", "status": "%s"}
                        """.formatted(tossStatus), MediaType.APPLICATION_JSON));
    }

    private org.springframework.test.web.client.ResponseCreator errorBody(HttpStatus status, String body) {
        return withStatus(status).body(body).contentType(MediaType.APPLICATION_JSON);
    }
}
