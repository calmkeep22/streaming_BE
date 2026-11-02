package com.example.streaming.Controller;

import com.example.streaming.Dto.PaymentRequest;
import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/events")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{eventId}/seats/{seatId}/payments")
    public ResponseEntity<PaymentResponse> pay(
            @PathVariable Long eventId,
            @PathVariable Long seatId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PaymentRequest request
    ) {
        Long userId = Long.valueOf(Objects.requireNonNull(jwt.getSubject()));
        PaymentResponse response = paymentService.pay(
                eventId,
                seatId,
                userId,
                request.orderId()
        );
        // 결제사 승인 결과를 아직 모르면 202: 요청은 받았지만 결과는 나중에 확정된다
        HttpStatus status = response.status() == PaymentStatus.PROCESSING
                ? HttpStatus.ACCEPTED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
