package com.example.streaming.Service;

import com.example.streaming.Dto.PaymentResponse;
import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Exception.ForbiddenException;
import com.example.streaming.Exception.PaymentGatewayException;
import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.Repository.PaymentRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

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

    /**
     * 1단계: 주문 생성.
     * 주문번호와 금액을 서버가 정해서 돌려주고, 클라이언트는 이 값으로 결제창을 띄운다.
     * 아직 돈이 움직이지 않으므로 결제창을 닫아도 READY로 남을 뿐이다.
     */
    public PaymentResponse prepare(Long eventId, Long seatId, Long userId) {
        return Objects.requireNonNull(transactionTemplate.execute(
                status -> createOrder(eventId, seatId, userId)
        ));
    }

    private PaymentResponse createOrder(Long eventId, Long seatId, Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자가 존재하지 않습니다."));
        SeatEntity seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("좌석이 존재하지 않습니다."));

        if (!seat.getEvent().getId().equals(eventId)) {
            throw new IllegalArgumentException("해당 이벤트의 좌석이 아닙니다.");
        }
        validateSeatStillHeld(seatId, userId);

        PaymentEntity payment = PaymentEntity.ready(
                generateOrderId(),
                user,
                seat,
                ticketPrice,
                paymentGateway.providerName()
        );
        return PaymentResponse.from(paymentRepository.save(payment));
    }

    // 토스 orderId 규칙: 6~64자, 영문 대소문자·숫자·-·_·=
    private String generateOrderId() {
        return "order_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 2단계: 결제 확정.
     * 사용자가 결제창에서 인증을 마치면 클라이언트가 paymentKey를 보내고, 서버가 결제사 승인을 요청한다.
     */
    public PaymentResponse confirm(Long userId, String orderId, String paymentKey, long amount) {
        PaymentStart start;
        try {
            start = Objects.requireNonNull(transactionTemplate.execute(
                    status -> startConfirm(userId, orderId, amount)
            ));
        } catch (ObjectOptimisticLockingFailureException e) {
            // 같은 주문의 확정 요청이 동시에 왔다 (결제 완료 페이지 새로고침 등). 먼저 온 요청만 결제사로 간다
            return findResponse(orderId);
        } catch (SeatUnavailableException e) {
            // 늦게 온 같은 주문의 요청은 READY를 읽은 뒤, 먼저 온 요청이 만든 예약을 보고 여기서 실패할 수 있다.
            // 이미 그 요청이 이 주문을 처리했다면 에러 대신 그 결과를 돌려준다
            PaymentResponse current = findResponse(orderId);
            if (current.status() != PaymentStatus.READY) {
                return current;
            }
            throw e;
        }

        if (start.existingResponse() != null) {
            return start.existingResponse();
        }

        PaymentGateway.PaymentApproval approval;
        try {
            approval = paymentGateway.approve(paymentKey, orderId, amount);
        } catch (PaymentGatewayException e) {
            // 승인됐는지 모른다. FAILED로 바꾸면 실제로 빠져나간 돈을 놓칠 수 있으므로 PROCESSING으로 두고,
            // 결과는 복구 작업(PaymentRecoveryService)이 결제사에 조회해서 정리한다
            log.warn("결제사 승인 결과 불명, 복구 작업에 맡김 paymentId={}, orderId={}", start.paymentId(), orderId, e);
            return findResponse(orderId);
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

    private PaymentStart startConfirm(Long userId, String orderId, long amount) {
        PaymentEntity payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문이 존재하지 않습니다."));

        if (!payment.getUser().getId().equals(userId)) {
            throw new ForbiddenException("주문자가 아닙니다.");
        }
        // 클라이언트를 거쳐 온 금액은 위변조될 수 있으므로, 서버에 저장한 금액과 같을 때만 승인한다
        if (payment.getAmount() != amount) {
            throw new IllegalArgumentException("결제 금액이 주문과 일치하지 않습니다.");
        }
        // 이미 진행했거나 끝난 주문은 결제사를 다시 부르지 않고 현재 결과를 돌려준다 (멱등)
        if (payment.getStatus() != PaymentStatus.READY) {
            return PaymentStart.existing(PaymentResponse.from(payment));
        }

        validateSeatStillHeld(payment.getSeat().getId(), userId);
        payment.startProcessing();
        return PaymentStart.started(payment.getId());
    }

    private void validateSeatStillHeld(Long seatId, Long userId) {
        if (!redisSeatHoldService.isHeldBy(seatId, userId)) {
            throw new SeatUnavailableException("선점 중인 좌석이 아니거나 선점 시간이 만료되었습니다.");
        }
        if (reservationRepository.existsBySeat_IdAndStatus(seatId, ReservationStatus.RESERVED)) {
            throw new SeatUnavailableException("이미 예약된 좌석입니다.");
        }
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

    /**
     * 예약 취소의 1단계. 예약 취소와 같은 트랜잭션 안에서 불러야 한다 (MANDATORY).
     * 이 예약에 연결된 성공 결제를 CANCEL_PENDING으로 바꾸고 결제 id를 돌려준다. 결제 없이 만든 예약이면 null.
     * 이미 CANCEL_PENDING이면(이전 환불이 실패했으면) 그대로 돌려줘서, 사용자가 다시 취소할 때 환불을 다시 시도한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long requestCancelForReservation(Long reservationId) {
        return paymentRepository.findByReservation_IdAndStatusIn(
                        reservationId,
                        List.of(PaymentStatus.SUCCESS, PaymentStatus.CANCEL_PENDING))
                .map(payment -> {
                    payment.requestCancel();
                    return payment.getId();
                })
                .orElse(null);
    }

    /**
     * 예약 취소의 2단계. 예약 취소가 커밋된 뒤 트랜잭션 밖에서 부른다.
     * 결제사 취소에 실패해도 예외를 던지지 않는다. 예약 취소는 이미 끝났고,
     * 결제는 CANCEL_PENDING으로 남아 복구 작업(PaymentRecoveryService)이 재시도한다.
     */
    public void refundCanceledReservation(Long paymentId) {
        String providerPaymentKey = transactionTemplate.execute(status ->
                paymentRepository.findById(paymentId)
                        .filter(payment -> payment.getStatus() == PaymentStatus.CANCEL_PENDING)
                        .map(PaymentEntity::getProviderPaymentKey)
                        .orElse(null));
        if (providerPaymentKey == null) {
            return;
        }

        try {
            paymentGateway.cancel(providerPaymentKey, "예약 취소에 따른 환불");
        } catch (RuntimeException e) {
            log.warn("예약 취소 환불 실패, 복구 작업이 재시도 paymentId={}", paymentId, e);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status ->
                    paymentRepository.findById(paymentId)
                            .filter(payment -> payment.getStatus() == PaymentStatus.CANCEL_PENDING)
                            .ifPresent(PaymentEntity::completeCancel));
        } catch (ObjectOptimisticLockingFailureException e) {
            // 그사이 복구 작업이 같은 환불을 먼저 끝냈다. 결제사 취소는 멱등이라 결과는 같다
            log.info("예약 취소 환불을 다른 쪽이 먼저 완료 paymentId={}", paymentId);
        }
    }

    private PaymentResponse findResponse(String orderId) {
        return Objects.requireNonNull(transactionTemplate.execute(status ->
                paymentRepository.findByOrderId(orderId)
                        .map(PaymentResponse::from)
                        .orElseThrow(() -> new IllegalArgumentException("주문이 존재하지 않습니다."))));
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

    private record PaymentStart(Long paymentId, PaymentResponse existingResponse) {
        private static PaymentStart started(Long paymentId) {
            return new PaymentStart(paymentId, null);
        }

        private static PaymentStart existing(PaymentResponse response) {
            return new PaymentStart(null, response);
        }
    }
}
