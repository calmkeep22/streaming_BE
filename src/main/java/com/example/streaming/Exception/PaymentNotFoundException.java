package com.example.streaming.Exception;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException() {
        super("주문을 찾을 수 없습니다.");
    }
}
