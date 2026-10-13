package com.example.streaming.Service;

import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.SeatStatus;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    EventRepository eventRepository;
    @Mock
    SeatRepository seatRepository;
    @Mock
    ReservationRepository reservationRepository;
    @Mock
    RedisSeatHoldService redisSeatHoldService;

    EventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, seatRepository, reservationRepository, redisSeatHoldService,
                new SimpleMeterRegistry());

        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(7));
        SeatEntity seat = new SeatEntity(event, 10);
        ReflectionTestUtils.setField(seat, "id", 100L);
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
    }

    @Test
    @DisplayName("확정 예약이 있으면 RESERVED")
    void reserved() {
        given(redisSeatHoldService.getHolder(100L)).willReturn(null);
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(true);

        assertThat(eventService.getSeat(100L).status()).isEqualTo(SeatStatus.RESERVED);
    }

    @Test
    @DisplayName("확정 예약은 없고 Redis 선점이 있으면 HELD")
    void held() {
        given(redisSeatHoldService.getHolder(100L)).willReturn("1");
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(false);

        assertThat(eventService.getSeat(100L).status()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("확정 예약도 Redis 선점도 없으면 AVAILABLE")
    void available() {
        given(redisSeatHoldService.getHolder(100L)).willReturn(null);
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(false);

        assertThat(eventService.getSeat(100L).status()).isEqualTo(SeatStatus.AVAILABLE);
    }
}
