package com.example.streaming.Service;

/**
 * 외부 결제사 연동을 감추는 인터페이스.
 * 실제 PG를 붙일 때 FakePaymentGateway 대신 이 인터페이스의 새 구현체를 추가한다.
 *
 * 구현체 규칙
 * - 결제사가 명확히 거절하면 PaymentApproval.failure를 반환한다
 * - 결과를 알 수 없으면(타임아웃, 네트워크 오류, 5xx) PaymentGatewayException을 던진다
 * - cancel은 이미 취소된 결제에 다시 호출돼도 성공으로 끝나야 한다 (복구 작업이 같은 취소를 반복할 수 있다)
 */
public interface PaymentGateway {

    PaymentApproval approve(String orderId, long amount);

    void cancel(String providerPaymentKey, String reason);

    /**
     * 주문번호로 결제사의 실제 결제 상태를 조회한다.
     * 승인 응답을 못 받은 경우 우리에게는 결제 키가 없으므로, 우리가 만든 주문번호로 조회해야 한다.
     */
    PaymentInquiry inquire(String orderId);

    record PaymentApproval(
            boolean successful,
            String providerPaymentKey,
            String failureCode,
            String failureMessage
    ) {
        public static PaymentApproval success(String providerPaymentKey) {
            return new PaymentApproval(true, providerPaymentKey, null, null);
        }

        public static PaymentApproval failure(String failureCode, String failureMessage) {
            return new PaymentApproval(false, null, failureCode, failureMessage);
        }
    }

    record PaymentInquiry(InquiryStatus status, String providerPaymentKey) {

        public static PaymentInquiry approved(String providerPaymentKey) {
            return new PaymentInquiry(InquiryStatus.APPROVED, providerPaymentKey);
        }

        public static PaymentInquiry of(InquiryStatus status) {
            return new PaymentInquiry(status, null);
        }
    }

    enum InquiryStatus {
        // 돈이 빠져나갔다
        APPROVED,
        // 승인 후 취소됐다
        CANCELED,
        // 결제사에 기록이 없거나 승인되지 않았다. 돈이 빠져나가지 않았다
        NOT_FOUND,
        // 결제사에서도 아직 처리 중이다
        IN_PROGRESS
    }
}
