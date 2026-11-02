package com.example.streaming.Controller;

import com.example.streaming.Dto.PaymentClientConfigResponse;
import com.example.streaming.Dto.PaymentConfirmRequest;
import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

@RestController
public class PaymentController {

    private final PaymentService paymentService;
    private final String clientKey;

    public PaymentController(
            PaymentService paymentService,
            @Value("${payment.toss.client-key:}") String clientKey
    ) {
        this.paymentService = paymentService;
        this.clientKey = clientKey;
    }

    // 1단계: 주문 생성. 응답의 주문번호와 금액으로 결제창을 띄운다
    @PostMapping("/events/{eventId}/seats/{seatId}/payments")
    public ResponseEntity<PaymentResponse> prepare(
            @PathVariable Long eventId,
            @PathVariable Long seatId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        PaymentResponse response = paymentService.prepare(eventId, seatId, userId(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // 2단계: 결제창 인증이 끝난 결제를 승인하고 예약을 확정한다
    @PostMapping("/payments/confirm")
    public ResponseEntity<PaymentResponse> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PaymentConfirmRequest request
    ) {
        PaymentResponse response = paymentService.confirm(
                userId(jwt),
                request.orderId(),
                request.paymentKey(),
                request.amount()
        );
        // 결제사 승인 결과를 아직 모르면 202: 요청은 받았지만 결과는 나중에 확정된다
        HttpStatus status = response.status() == PaymentStatus.PROCESSING
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/payments/client-config")
    public PaymentClientConfigResponse clientConfig(@AuthenticationPrincipal Jwt jwt) {
        // customerKey는 구매자를 구분하는 값이다. 사용자 id가 그대로 드러나지 않게 UUID로 바꾼다
        String customerKey = UUID.nameUUIDFromBytes(
                ("customer:" + userId(jwt)).getBytes(StandardCharsets.UTF_8)
        ).toString();
        return new PaymentClientConfigResponse(clientKey, customerKey);
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(Objects.requireNonNull(jwt.getSubject()));
    }
}
