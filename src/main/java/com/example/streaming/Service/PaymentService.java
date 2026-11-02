package com.example.streaming.Service;

import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Exception.PaymentGatewayException;
import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.Repository.PaymentRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PAYMENT_PROVIDER = "FAKE";

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final RedisSeatHoldService redisSeatHoldService;
    private final PaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;
    private final long ticketPrice;

    public PaymentService(
            PaymentRepository paymentRepository,
            ReservationRepository reservationRepository,
            SeatRepository seatRepository,
            UserRepository userRepository,
            RedisSeatHoldService redisSeatHoldService,
            PaymentGateway paymentGateway,
            TransactionTemplate transactionTemplate,
            @Value("${payment.ticket-price:50000}") long ticketPrice
    ) {
        this.paymentRepository = paymentRepository;
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.redisSeatHoldService = redisSeatHoldService;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = transactionTemplate;
        this.ticketPrice = ticketPrice;
    }

    public PaymentResponse pay(Long eventId, Long seatId, Long userId, String orderId) {
        validateRequest(eventId, seatId, userId, orderId);

        PaymentStart start;
        try {
            start = Objects.requireNonNull(transactionTemplate.execute(
                    status -> preparePayment(eventId, seatId, userId, orderId)
            ));
        } catch (DataIntegrityViolationException e) {
            return findConcurrentPayment(orderId, seatId, userId, e);
        }

        if (start.existingResponse() != null) {
            return start.existingResponse();
        }

        PaymentGateway.PaymentApproval approval;
        try {
            approval = paymentGateway.approve(orderId, start.amount());
        } catch (PaymentGatewayException e) {
            // 승인됐는지 모른다. FAILED로 바꾸면 실제로 빠져나간 돈을 놓칠 수 있으므로 PROCESSING으로 두고,
            // 결과는 복구 작업(PaymentRecoveryService)이 결제사에 조회해서 정리한다
            log.warn("결제사 승인 결과 불명, 복구 작업에 맡김 paymentId={}, orderId={}", start.paymentId(), orderId, e);
            return findResponse(start.paymentId());
        }

        if (!approval.successful()) {
            return failPayment(
                    start.paymentId(),
                    approval.failureCode(),
                    approval.failureMessage()
            );
        }

        try {
            return Objects.requireNonNull(transactionTemplate.execute(
                    status -> completePayment(start.paymentId(), approval.providerPaymentKey())
            ));
        } catch (RuntimeException e) {
            compensateApprovedPayment(start.paymentId(), approval.providerPaymentKey(), e);
            throw e;
        }
    }

    private PaymentStart preparePayment(
            Long eventId,
            Long seatId,
            Long userId,
            String orderId
    ) {
        PaymentEntity existing = paymentRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            validateExistingPayment(existing, seatId, userId);
            return PaymentStart.existing(PaymentResponse.from(existing));
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자가 존재하지 않습니다."));
        SeatEntity seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("좌석이 존재하지 않습니다."));

        if (!seat.getEvent().getId().equals(eventId)) {
            throw new IllegalArgumentException("해당 이벤트의 좌석이 아닙니다.");
        }
        if (!redisSeatHoldService.isHeldBy(seatId, userId)) {
            throw new SeatUnavailableException("선점 중인 좌석이 아니거나 선점 시간이 만료되었습니다.");
        }
        if (reservationRepository.existsBySeat_IdAndStatus(seatId, ReservationStatus.RESERVED)) {
            throw new SeatUnavailableException("이미 예약된 좌석입니다.");
        }

        PaymentEntity payment = PaymentEntity.ready(
                orderId,
                user,
                seat,
                ticketPrice,
                PAYMENT_PROVIDER
        );
        paymentRepository.saveAndFlush(payment);
        payment.startProcessing();

        return PaymentStart.created(payment.getId(), payment.getAmount());
    }

    private PaymentResponse completePayment(Long paymentId, String providerPaymentKey) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제 요청이 존재하지 않습니다."));

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return PaymentResponse.from(payment);
        }
        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("결제를 확정할 수 없는 상태입니다.");
        }

        Long seatId = payment.getSeat().getId();
        Long userId = payment.getUser().getId();

        if (!redisSeatHoldService.isHeldBy(seatId, userId)) {
            throw new SeatUnavailableException("결제 중 좌석 선점 시간이 만료되었습니다.");
        }
        if (reservationRepository.existsBySeat_IdAndStatus(seatId, ReservationStatus.RESERVED)) {
            throw new SeatUnavailableException("이미 예약된 좌석입니다.");
        }

        ReservationEntity reservation = reservationRepository.saveAndFlush(
                ReservationEntity.reserved(payment.getUser(), payment.getSeat())
        );
        payment.succeed(providerPaymentKey, reservation);
        releaseHoldAfterCommit(seatId, userId);

        return PaymentResponse.from(payment);
    }

    private PaymentResponse failPayment(Long paymentId, String code, String message) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            PaymentEntity payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new IllegalArgumentException("결제 요청이 존재하지 않습니다."));
            payment.fail(code, message);
            return PaymentResponse.from(payment);
        }));
    }

    private void compensateApprovedPayment(
            Long paymentId,
            String providerPaymentKey,
            RuntimeException originalException
    ) {
        try {
            paymentGateway.cancel(providerPaymentKey, originalException.getMessage());
        } catch (RuntimeException cancelException) {
            log.error("승인된 결제 취소 실패, 환불 대기로 전환 paymentId={}, providerPaymentKey={}",
                    paymentId, providerPaymentKey, cancelException);
            originalException.addSuppressed(cancelException);
            markRefundPending(paymentId, providerPaymentKey, originalException);
            return;
        }

        try {
            failPayment(paymentId, "RESERVATION_FAILED", originalException.getMessage());
        } catch (RuntimeException failureUpdateException) {
            log.error("결제 실패 상태 저장 실패 paymentId={}", paymentId, failureUpdateException);
            originalException.addSuppressed(failureUpdateException);
        }
    }

    /**
     * 환불이 필요하다는 건 확실하므로 REFUND_PENDING으로 남겨 복구 작업이 바로 재시도하게 한다.
     * 이 저장마저 실패하면 결제는 PROCESSING으로 남는다. 그래도 유예 시간이 지나면 복구 작업이
     * 결제사에 조회해 APPROVED를 확인하고 환불하므로 돈이 새지 않는다 (2단 안전장치).
     */
    private void markRefundPending(Long paymentId, String providerPaymentKey, RuntimeException originalException) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                PaymentEntity payment = paymentRepository.findById(paymentId)
                        .orElseThrow(() -> new IllegalArgumentException("결제 요청이 존재하지 않습니다."));
                payment.requireRefund(providerPaymentKey, "RESERVATION_FAILED", originalException.getMessage());
            });
        } catch (RuntimeException e) {
            log.error("환불 대기 상태 저장 실패, 복구 작업의 결제사 조회에 맡김 paymentId={}", paymentId, e);
            originalException.addSuppressed(e);
        }
    }

    private PaymentResponse findResponse(Long paymentId) {
        return Objects.requireNonNull(transactionTemplate.execute(status ->
                paymentRepository.findById(paymentId)
                        .map(PaymentResponse::from)
                        .orElseThrow(() -> new IllegalArgumentException("결제 요청이 존재하지 않습니다."))));
    }

    private PaymentResponse findConcurrentPayment(
            String orderId,
            Long seatId,
            Long userId,
            DataIntegrityViolationException originalException
    ) {
        PaymentEntity existing = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> originalException);
        validateExistingPayment(existing, seatId, userId);
        return PaymentResponse.from(existing);
    }

    private void validateExistingPayment(PaymentEntity payment, Long seatId, Long userId) {
        if (!payment.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("다른 사용자가 사용 중인 주문번호입니다.");
        }
        if (!payment.getSeat().getId().equals(seatId)) {
            throw new IllegalArgumentException("주문번호의 좌석 정보가 일치하지 않습니다.");
        }
    }

    private void validateRequest(Long eventId, Long seatId, Long userId, String orderId) {
        if (eventId == null || seatId == null || userId == null) {
            throw new IllegalArgumentException("이벤트, 좌석, 사용자 정보는 필수입니다.");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("주문번호는 필수입니다.");
        }
        if (orderId.length() > 100) {
            throw new IllegalArgumentException("주문번호는 100자 이하여야 합니다.");
        }
    }

    private void releaseHoldAfterCommit(Long seatId, Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisSeatHoldService.release(seatId, userId);
                } catch (RuntimeException e) {
                    log.warn("결제 완료 후 좌석 선점 해제 실패 seatId={}, userId={}", seatId, userId, e);
                }
            }
        });
    }

    private record PaymentStart(Long paymentId, long amount, PaymentResponse existingResponse) {
        private static PaymentStart created(Long paymentId, long amount) {
            return new PaymentStart(paymentId, amount, null);
        }

        private static PaymentStart existing(PaymentResponse response) {
            return new PaymentStart(null, 0L, response);
        }
    }
}
