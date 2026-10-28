package com.example.streaming.Service;

import com.example.streaming.Dto.HoldResponse;
import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.SeatStatus;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.Exception.ForbiddenException;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final RedisSeatHoldService redisSeatHoldService;
    private final TransactionTemplate transactionTemplate;

    private final Duration holdDuration;

    private final Timer redisHoldTimer;
    private final Counter successCounter;
    private final Counter alreadyReservedCounter;
    private final Counter holdSuccessCounter;
    private final Counter holdUnavailableCounter;

    public ReservationService(SeatRepository seatRepository, ReservationRepository reservationRepository, UserRepository userRepository,
                              RedisSeatHoldService redisSeatHoldService, TransactionTemplate transactionTemplate, MeterRegistry meterRegistry,
                              @Value("${reservation.hold-duration:PT5M}") Duration holdDuration) {
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.redisSeatHoldService = redisSeatHoldService;
        this.transactionTemplate = transactionTemplate;
        this.holdDuration = holdDuration;

        // 좌석 선점 경쟁 단계(Redis SET NX 왕복)에 걸린 시간
        this.redisHoldTimer = Timer.builder("reservation.hold.redis")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.successCounter = Counter.builder("reservation.result")
                .tag("result", "success")
                .register(meterRegistry);
        this.alreadyReservedCounter = Counter.builder("reservation.result")
                .tag("result", "already_reserved")
                .register(meterRegistry);
        this.holdSuccessCounter = Counter.builder("reservation.hold.result")
                .tag("result", "success")
                .register(meterRegistry);
        this.holdUnavailableCounter = Counter.builder("reservation.hold.result")
                .tag("result", "unavailable")
                .register(meterRegistry);
    }

    /**
     * 1단계: 좌석 선점.
     * 메서드 전체에 트랜잭션을 걸면 시작할 때 DB 커넥션을 잡는다. SET NX에 진 요청이
     * 커넥션을 잡지 않도록, DB 작업 구간만 transactionTemplate으로 감싼다.
     */
    public HoldResponse holdSeat(Long eventId, Long seatId, Long userId) {
        try {
            HoldResponse response = holdWithRedis(eventId, seatId, userId);
            holdSuccessCounter.increment();
            return response;
        } catch (SeatUnavailableException e) {
            holdUnavailableCounter.increment();
            throw e;
        }
    }

    /**
     * 선점 경쟁은 Redis SET NX로 먼저 끝내고, 이긴 요청만 MySQL에서 사용자/좌석/최종 예약 여부를 확인한다.
     * DB 행 락도, DB INSERT도 하지 않는다.
     * - SET NX에 진 요청은 DB를 거치지 않는다 (커넥션을 잡지 않음)
     * - 예약 확정은 커밋한 뒤에 Redis 키를 지운다. 그래서 키를 잡은 "뒤에" 시작한 트랜잭션은 그 커밋을 반드시 본다.
     *   DB를 먼저 읽으면 REPEATABLE READ 스냅샷이 SET NX 이전에 고정돼 방금 확정된 예약을 못 볼 수 있다
     * - 확인에 실패하면 내가 잡은 선점만 지운다
     */
    private HoldResponse holdWithRedis(Long eventId, Long seatId, Long userId) {
        boolean held = redisHoldTimer.record(() -> redisSeatHoldService.hold(seatId, userId));
        if (!held) {
            alreadyReservedCounter.increment();
            throw new SeatUnavailableException("다른 사용자가 선점 중인 좌석입니다.");
        }

        try {
            transactionTemplate.executeWithoutResult(status -> validateRedisHold(eventId, seatId, userId));
        } catch (RuntimeException e) {
            releaseQuietly(seatId, userId);
            throw e;
        }

        return new HoldResponse(seatId, userId, SeatStatus.HELD, holdDuration.toSeconds());
    }

    // SET NX에 이긴 요청의 DB 확인: 사용자/좌석/이벤트가 맞고, 이미 최종 예약된 좌석이 아니어야 한다
    private void validateRedisHold(Long eventId, Long seatId, Long userId) {
        validateUser(userId);

        SeatEntity seat = findSeat(seatId);
        validateEvent(seat, eventId);

        if (reservationRepository.existsBySeat_IdAndStatus(seatId, ReservationStatus.RESERVED)) {
            alreadyReservedCounter.increment();
            throw new SeatUnavailableException("이미 예약된 좌석입니다.");
        }
    }

    /**
     * 2단계: 예약 확정.
     * Redis에서 본인 선점을 확인한 뒤 MySQL에 RESERVED 예약을 만들고, 커밋이 성공하면 Redis 선점을 지운다.
     */
    @Transactional
    public ReservationResponse reserve(Long eventId, Long seatId, Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("사용자가 존재하지 않습니다."));

        SeatEntity seat = findSeat(seatId);
        validateEvent(seat, eventId);

        if (!redisSeatHoldService.isHeldBy(seatId, userId)) {
            throw new SeatUnavailableException("선점 중인 좌석이 아니거나 선점 시간이 만료되었습니다.");
        }

        if (reservationRepository.existsBySeat_IdAndStatus(seatId, ReservationStatus.RESERVED)) {
            alreadyReservedCounter.increment();
            throw new SeatUnavailableException("이미 예약된 좌석입니다.");
        }

        ReservationEntity reservation;
        try {
            reservation = reservationRepository.save(ReservationEntity.reserved(user, seat));
        } catch (DataIntegrityViolationException e) {
            // Redis와 DB는 한 트랜잭션이 아니라서 위 확인을 통과한 요청이 둘일 수 있다. seat_id 유니크 제약이 최종 방어선
            alreadyReservedCounter.increment();
            throw new SeatUnavailableException("이미 예약된 좌석입니다.");
        }

        releaseHoldAfterCommit(seatId, userId);
        successCounter.increment();

        return toResponse(reservation);
    }

    /**
     * 커밋 전에 Redis 선점을 지우면, 커밋 실패 시 예약도 선점도 없는 상태가 되고
     * 커밋 직전 틈에 다른 사용자가 선점을 잡을 수 있다. 그래서 커밋이 성공한 뒤에 지운다.
     */
    private void releaseHoldAfterCommit(Long seatId, Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            releaseQuietly(seatId, userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                releaseQuietly(seatId, userId);
            }
        });
    }

    // 예약은 이미 커밋됐으므로 Redis 삭제 실패로 요청을 실패시키지 않는다. 남은 키는 TTL로 사라지고, 재선점은 RESERVED 확인에서 막힌다
    private void releaseQuietly(Long seatId, Long userId) {
        try {
            redisSeatHoldService.release(seatId, userId);
        } catch (RuntimeException e) {
            log.warn("좌석 선점 해제 실패 seatId={}, userId={}", seatId, userId, e);
        }
    }

    @Transactional
    public void cancel(Long reservationId, Long userId) {
        ReservationEntity reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("예약이 존재하지 않습니다"));
        if (!reservation.getUser().getId().equals(userId)) {
            throw new ForbiddenException("예약자가 아닙니다");
        }
        reservation.cancel();
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> findMyReservations(Long userId) {
        return reservationRepository.findAllByUser_Id(userId)
                .stream()
                .map(ReservationResponse::from)
                .toList();
    }

    // ---------- 공통 ----------

    private void validateUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("사용자가 존재하지 않습니다.");
        }
    }

    private SeatEntity findSeat(Long seatId) {
        return seatRepository.findById(seatId)
                .orElseThrow(() ->
                        new IllegalArgumentException("좌석이 존재하지 않습니다."));
    }

    // 요청한 event의 좌석이 맞는지 확인
    private void validateEvent(SeatEntity seat, Long eventId) {
        if (!seat.getEvent().getId().equals(eventId)) {
            throw new IllegalArgumentException("해당 이벤트의 좌석이 아닙니다.");
        }
    }

    private ReservationResponse toResponse(ReservationEntity reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getUser().getId(),
                reservation.getSeat().getId(),
                reservation.getStatus()
        );
    }
}
