package com.example.streaming.Dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
        @NotBlank(message = "주문번호는 필수입니다.")
        @Size(max = 100, message = "주문번호는 100자 이하여야 합니다.")
        String orderId
) {
}
