package com.example.streaming.Exception;

/**
 * 결제사 호출의 결과를 알 수 없을 때 던진다 (타임아웃, 네트워크 오류, 결제사 5xx 등).
 * 카드 거절처럼 결제사가 명확히 "실패"라고 답한 경우와 구분해야 한다.
 * 결과를 모르는데 FAILED로 바꾸면, 실제로는 승인된 돈을 놓칠 수 있다.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
