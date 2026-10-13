package com.example.streaming.Service;

import com.example.streaming.Dto.EventCreateRequest;
import com.example.streaming.Dto.EventResponse;
import com.example.streaming.Dto.SeatResponse;
import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.SeatStatus;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import jakarta.transaction.Transactional;

import org.springframework.stereotype.Service;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final RedisSeatHoldService redisSeatHoldService;

    // getSeat 내부 측정용 Timer
    private final Timer seatQueryTimer;
    private final Timer redisTimer;
    private final Timer reservationQueryTimer;
    private final Timer mappingTimer;

    public EventService(
            EventRepository eventRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            RedisSeatHoldService redisSeatHoldService,
            MeterRegistry meterRegistry
    ) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.redisSeatHoldService = redisSeatHoldService;

        this.seatQueryTimer =
                Timer.builder("experiment.getseat.seat.query")
                        .description("Seat findById duration")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(meterRegistry);

        this.redisTimer =
                Timer.builder("experiment.getseat.redis")
                        .description("Redis seat holder lookup duration")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(meterRegistry);

        this.reservationQueryTimer =
                Timer.builder("experiment.getseat.reservation.query")
                        .description("Reservation exists query duration")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(meterRegistry);

        this.mappingTimer =
                Timer.builder("experiment.getseat.mapping")
                        .description("SeatResponse mapping duration")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(meterRegistry);
    }

    @Transactional
    public EventResponse createEvent(EventCreateRequest request) {

        EventEntity event =
                new EventEntity(
                        request.title(),
                        request.startTime()
                );

        eventRepository.save(event);

        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getStartTime()
        );
    }

    public SeatResponse getSeat(Long seatId) {

        // 1. MySQL: Seat 조회
        SeatEntity seat = seatQueryTimer.record(() ->
                seatRepository.findById(seatId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "좌석이 존재하지 않습니다."
                                )
                        )
        );

        // 2 + 3. Redis + Reservation DB 조회
        SeatStatus status = toSeatStatus(seatId);

        // 4. DTO 변환
        return mappingTimer.record(() ->
                new SeatResponse(
                        seat.getId(),
                        seat.getSeatNumber(),
                        status
                )
        );
    }

    private SeatStatus toSeatStatus(Long seatId) {

        // 2. Redis 조회
        String holder = redisTimer.record(() ->
                redisSeatHoldService.getHolder(seatId)
        );

        // 3. MySQL: 확정 예약 존재 여부 조회
        boolean reserved =
                reservationQueryTimer.record(() ->
                        reservationRepository
                                .existsBySeat_IdAndStatus(
                                        seatId,
                                        ReservationStatus.RESERVED
                                )
                );

        if (reserved) {
            return SeatStatus.RESERVED;
        }

        if (holder != null) {
            return SeatStatus.HELD;
        }

        return SeatStatus.AVAILABLE;
    }
}