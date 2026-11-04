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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

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

        List<SeatEntity> seats = IntStream.rangeClosed(1, request.seatCount())
                .mapToObj(seatNumber -> new SeatEntity(event, seatNumber))
                .toList();
        seatRepository.saveAll(seats);

        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getStartTime(),
                seats.size()
        );
    }

    /**
     * 이벤트의 좌석 목록과 상태. 좌석마다 조회하지 않도록 선점은 Redis MGET, 확정 예약은 쿼리 한 번으로 읽는다.
     * 단건 조회(toSeatStatus)와 같은 이유로 Redis를 먼저 읽는다.
     */
    public List<SeatResponse> getSeats(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new IllegalArgumentException("이벤트가 존재하지 않습니다.");
        }

        List<SeatEntity> seats = seatRepository.findAllByEvent_IdOrderBySeatNumber(eventId);
        List<String> holders = redisSeatHoldService.getHolders(
                seats.stream().map(SeatEntity::getId).toList());
        Set<Long> reservedSeatIds = new HashSet<>(
                reservationRepository.findSeatIdsByEventIdAndStatus(eventId, ReservationStatus.RESERVED));

        List<SeatResponse> responses = new ArrayList<>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            SeatEntity seat = seats.get(i);
            SeatStatus status = reservedSeatIds.contains(seat.getId())
                    ? SeatStatus.RESERVED
                    : holders.get(i) != null ? SeatStatus.HELD : SeatStatus.AVAILABLE;
            responses.add(new SeatResponse(seat.getId(), seat.getSeatNumber(), status));
        }
        return responses;
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