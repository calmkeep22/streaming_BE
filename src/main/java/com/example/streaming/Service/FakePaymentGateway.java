package com.example.streaming.Service;

import com.example.streaming.Exception.PaymentGatewayException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 개발과 테스트에 사용하는 가짜 결제사.
 * 실제 결제사처럼 주문번호별 결제 상태를 기억해서 inquire로 조회할 수 있다.
 * 복구 작업을 테스트하려고 장애 상황을 일부러 만들 수 있다 (failNext... 메서드).
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "fake", matchIfMissing = true)
public class FakePaymentGateway implements PaymentGateway {

    // 주문번호 → 결제사 쪽 결제 기록
    private final Map<String, FakePayment> payments = new ConcurrentHashMap<>();

    // 다음 승인 요청은 "승인은 해놓고 응답을 잃어버린" 상황을 흉내 낸다
    private final AtomicBoolean loseNextApproveResponse = new AtomicBoolean(false);
    // 승인 요청이 결제사까지 몇 번 왔는지. 멱등 처리가 결제사 호출을 막는지 확인할 때 쓴다
    private final AtomicInteger approveCalls = new AtomicInteger(0);
    // 다음 승인 요청을 카드 거절처럼 명확히 실패시킨다
    private final AtomicReference<PaymentApproval> nextDecline = new AtomicReference<>();
    // 다음 취소 요청을 몇 번 연속으로 실패시킬지
    private final AtomicInteger failingCancels = new AtomicInteger(0);
    // 다음 조회 직전에 실행할 동작. "복구 작업이 조회하는 사이 요청 스레드가 결제를 끝낸" 경쟁 상황을 재현한다
    private final AtomicReference<Runnable> beforeNextInquire = new AtomicReference<>();

    @Override
    public String providerName() {
        return "FAKE";
    }

    // 결제창 없이 승인을 만들 때 쓰는 테스트용 진입점. 결제 키를 주문번호로 만든다
    public PaymentApproval approve(String orderId, long amount) {
        String paymentKey = "fake_" + UUID.nameUUIDFromBytes(
                (orderId + ":" + amount).getBytes(StandardCharsets.UTF_8)
        );
        return approve(paymentKey, orderId, amount);
    }

    @Override
    public PaymentApproval approve(String paymentKey, String orderId, long amount) {
        if (orderId == null || orderId.isBlank()) {
            return PaymentApproval.failure("INVALID_ORDER_ID", "주문번호가 올바르지 않습니다.");
        }
        if (amount <= 0) {
            return PaymentApproval.failure("INVALID_AMOUNT", "결제 금액이 올바르지 않습니다.");
        }

        approveCalls.incrementAndGet();
        PaymentApproval decline = nextDecline.getAndSet(null);
        if (decline != null) {
            // 거절은 결제사에 승인 기록을 남기지 않는다
            return decline;
        }

        // 실제 결제사처럼 같은 주문번호는 한 번만 승인한다
        FakePayment payment = payments.computeIfAbsent(orderId, id -> new FakePayment(paymentKey));

        if (loseNextApproveResponse.compareAndSet(true, false)) {
            throw new PaymentGatewayException("결제사 응답 시간 초과 (승인은 처리됨)");
        }
        return PaymentApproval.success(payment.paymentKey);
    }

    @Override
    public void cancel(String providerPaymentKey, String reason) {
        if (providerPaymentKey == null || providerPaymentKey.isBlank()) {
            throw new IllegalArgumentException("취소할 결제 키가 없습니다.");
        }
        if (failingCancels.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new PaymentGatewayException("결제사 취소 요청 실패");
        }
        // 이미 취소된 결제를 다시 취소해도 성공으로 끝난다 (멱등)
        payments.values().stream()
                .filter(payment -> payment.paymentKey.equals(providerPaymentKey))
                .forEach(payment -> payment.canceled = true);
    }

    @Override
    public PaymentInquiry inquire(String orderId) {
        Runnable hook = beforeNextInquire.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        return statusOf(orderId);
    }

    // ---------- 테스트용 장애 주입 ----------

    public void loseNextApproveResponse() {
        loseNextApproveResponse.set(true);
    }

    public int approveCount() {
        return approveCalls.get();
    }

    public void declineNext(String failureCode, String failureMessage) {
        nextDecline.set(PaymentApproval.failure(failureCode, failureMessage));
    }

    public void failNextCancels(int count) {
        failingCancels.set(count);
    }

    public void beforeNextInquire(Runnable action) {
        beforeNextInquire.set(action);
    }

    // 장애 주입 없이 결제사 쪽 상태만 본다 (테스트 검증용)
    public PaymentInquiry statusOf(String orderId) {
        FakePayment payment = payments.get(orderId);
        if (payment == null) {
            return PaymentInquiry.of(InquiryStatus.NOT_FOUND);
        }
        return payment.canceled ? PaymentInquiry.of(InquiryStatus.CANCELED) : PaymentInquiry.approved(payment.paymentKey);
    }

    public void reset() {
        payments.clear();
        loseNextApproveResponse.set(false);
        approveCalls.set(0);
        nextDecline.set(null);
        failingCancels.set(0);
        beforeNextInquire.set(null);
    }

    private static final class FakePayment {
        private final String paymentKey;
        private volatile boolean canceled;

        private FakePayment(String paymentKey) {
            this.paymentKey = paymentKey;
        }
    }
}
