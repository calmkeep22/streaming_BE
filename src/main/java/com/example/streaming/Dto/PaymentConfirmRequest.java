package com.example.streaming.Dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 결제창 인증이 끝난 뒤 결제사가 클라이언트에 넘겨준 값
public record PaymentConfirmRequest(
        @NotBlank(message = "결제 키는 필수입니다.")
        @Size(max = 200, message = "결제 키는 200자 이하여야 합니다.")
        String paymentKey,

        @NotBlank(message = "주문번호는 필수입니다.")
        @Size(max = 64, message = "주문번호는 64자 이하여야 합니다.")
        String orderId,

        @NotNull(message = "결제 금액은 필수입니다.")
        @Positive(message = "결제 금액은 0보다 커야 합니다.")
        Long amount
) {
}
