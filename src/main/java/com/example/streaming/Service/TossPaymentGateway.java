package com.example.streaming.Service;

import com.example.streaming.Exception.PaymentGatewayException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * 토스페이먼츠 결제 API 연동.
 * 응답을 세 가지로 나누는 것이 핵심이다.
 * - 승인 성공
 * - 확정 실패 (카드 거절 등): PaymentApproval.failure → 돈이 빠지지 않았다
 * - 결과 불명 (타임아웃, 5xx 등): PaymentGatewayException → 복구 작업이 조회로 확인한다
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "toss")
public class TossPaymentGateway implements PaymentGateway {

    private static final String BASE_URL = "https://api.tosspayments.com";
    private static final int MAX_CANCEL_REASON_LENGTH = 200;

    // 4xx지만 돈이 빠지지 않았다고 단정할 수 없는 승인 응답. 복구 작업의 조회로 넘긴다
    private static final Set<String> UNCERTAIN_APPROVE_CODES = Set.of(
            "ALREADY_PROCESSED_PAYMENT",
            "PROVIDER_ERROR",
            "IDEMPOTENT_REQUEST_PROCESSING"
    );

    private final RestClient restClient;

    @Autowired
    public TossPaymentGateway(
            @Value("${payment.toss.secret-key}") String secretKey,
            @Value("${payment.toss.connect-timeout:PT3S}") Duration connectTimeout,
            // 복구 유예 시간(payment.recovery.grace-period)보다 훨씬 짧아야 한다
            @Value("${payment.toss.read-timeout:PT30S}") Duration readTimeout
    ) {
        this(buildClient(secretKey, connectTimeout, readTimeout));
    }

    TossPaymentGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient buildClient(String secretKey, Duration connectTimeout, Duration readTimeout) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("payment.gateway=toss에는 TOSS_SECRET_KEY가 필요합니다.");
        }

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(secretKey))
                .build();
    }

    // 토스 인증 형식: "시크릿키:"를 Base64로 인코딩한다. 비밀번호가 없어서 뒤에 콜론만 붙는다
    static String basicAuth(String secretKey) {
        byte[] credentials = (secretKey + ":").getBytes(StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(credentials);
    }

    @Override
    public String providerName() {
        return "TOSS";
    }

    @Override
    public PaymentApproval approve(String paymentKey, String orderId, long amount) {
        try {
            TossPayment payment = restClient.post()
                    .uri("/v1/payments/confirm")
                    // 응답을 못 받은 뒤 같은 요청이 다시 나가도 승인이 두 번 처리되지 않게 한다
                    .header("Idempotency-Key", "confirm-" + orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ConfirmRequest(paymentKey, orderId, amount))
                    .retrieve()
                    .body(TossPayment.class);

            if (payment == null || !"DONE".equals(payment.status())) {
                throw new PaymentGatewayException("예상하지 못한 승인 응답입니다. orderId=" + orderId);
            }
            return PaymentApproval.success(payment.paymentKey());
        } catch (RestClientResponseException e) {
            TossError error = readError(e);
            if (isUncertain(e, error)) {
                throw new PaymentGatewayException("결제 승인 결과를 알 수 없습니다. code=" + error.code(), e);
            }
            return PaymentApproval.failure(error.code(), error.message());
        } catch (RestClientException e) {
            throw new PaymentGatewayException("결제 승인 요청 실패 orderId=" + orderId, e);
        }
    }

    private boolean isUncertain(RestClientResponseException e, TossError error) {
        return e.getStatusCode().is5xxServerError()
                || e.getStatusCode().value() == 429
                || UNCERTAIN_APPROVE_CODES.contains(error.code());
    }

    /**
     * 취소에는 멱등키를 붙이지 않는다. 같은 멱등키로 재시도하면 첫 응답이 그대로 돌아와서,
     * 일시적 오류로 실패한 취소를 복구 작업이 다시 시도해도 계속 실패로 끝나기 때문이다.
     * 전액 취소를 반복해도 ALREADY_CANCELED_PAYMENT로 끝나므로 이중 취소는 일어나지 않는다.
     */
    @Override
    public void cancel(String providerPaymentKey, String reason) {
        try {
            restClient.post()
                    .uri("/v1/payments/{paymentKey}/cancel", providerPaymentKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CancelRequest(normalizeReason(reason)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if ("ALREADY_CANCELED_PAYMENT".equals(readError(e).code())) {
                return;
            }
            throw new PaymentGatewayException("결제 취소 실패 code=" + readError(e).code(), e);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("결제 취소 요청 실패", e);
        }
    }

    // cancelReason은 필수이고 200자까지다
    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "결제 취소";
        }
        return reason.length() > MAX_CANCEL_REASON_LENGTH ? reason.substring(0, MAX_CANCEL_REASON_LENGTH) : reason;
    }

    @Override
    public PaymentInquiry inquire(String orderId) {
        try {
            TossPayment payment = restClient.get()
                    .uri("/v1/payments/orders/{orderId}", orderId)
                    .retrieve()
                    .body(TossPayment.class);

            if (payment == null) {
                throw new PaymentGatewayException("결제 조회 응답이 비어 있습니다. orderId=" + orderId);
            }
            return toInquiry(payment);
        } catch (RestClientResponseException e) {
            // 돈이 안 빠졌다는 판단의 근거이므로, 결제 기록이 없다는 코드만 인정한다
            if ("NOT_FOUND_PAYMENT".equals(readError(e).code())) {
                return PaymentInquiry.of(InquiryStatus.NOT_FOUND);
            }
            throw new PaymentGatewayException("결제 조회 실패 code=" + readError(e).code(), e);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("결제 조회 요청 실패 orderId=" + orderId, e);
        }
    }

    /**
     * 부분 취소(PARTIAL_CANCELED)처럼 우리가 자동으로 판단할 수 없는 상태는 예외로 넘긴다.
     * 복구 작업이 재시도하다 한도를 넘으면 NEEDS_REVIEW로 사람이 확인하게 된다.
     */
    private PaymentInquiry toInquiry(TossPayment payment) {
        return switch (payment.status()) {
            case "DONE" -> PaymentInquiry.approved(payment.paymentKey());
            case "CANCELED" -> PaymentInquiry.of(InquiryStatus.CANCELED);
            case "READY", "IN_PROGRESS" -> PaymentInquiry.of(InquiryStatus.IN_PROGRESS);
            // 승인 실패와 만료는 돈이 빠지지 않은 상태다
            case "ABORTED", "EXPIRED" -> PaymentInquiry.of(InquiryStatus.NOT_FOUND);
            default -> throw new PaymentGatewayException("처리할 수 없는 결제 상태입니다. status=" + payment.status());
        };
    }

    private TossError readError(RestClientResponseException e) {
        try {
            TossError error = e.getResponseBodyAs(TossError.class);
            if (error != null && error.code() != null) {
                return error;
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아니면 코드 없는 오류로 다룬다
        }
        return new TossError("UNKNOWN_ERROR", e.getStatusText());
    }

    private record ConfirmRequest(String paymentKey, String orderId, long amount) {
    }

    private record CancelRequest(String cancelReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TossPayment(String paymentKey, String orderId, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TossError(String code, String message) {
    }
}
