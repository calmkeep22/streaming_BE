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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

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
        // 단건 조회 테스트에서만 쓴다
        lenient().when(seatRepository.findById(100L)).thenReturn(Optional.of(seat));
    }

    @Test
    @DisplayName("이벤트를 생성하면 좌석 번호 1부터 요청한 수만큼 좌석을 만든다")
    void createEvent_createsSeats() {
        LocalDateTime startTime = LocalDateTime.now().plusDays(7);

        EventResponse response = eventService.createEvent(new EventCreateRequest("콘서트", startTime, 3));

        ArgumentCaptor<List<SeatEntity>> captor = ArgumentCaptor.captor();
        verify(seatRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(SeatEntity::getSeatNumber).containsExactly(1, 2, 3);
        assertThat(captor.getValue()).allSatisfy(seat -> assertThat(seat.getEvent().getTitle()).isEqualTo("콘서트"));
        assertThat(response.seatCount()).isEqualTo(3);
        assertThat(response.startTime()).isEqualTo(startTime);
    }

    @Test
    @DisplayName("좌석 목록은 확정 예약이 있으면 RESERVED, 선점만 있으면 HELD, 둘 다 없으면 AVAILABLE")
    void getSeats() {
        EventEntity event = new EventEntity("콘서트", LocalDateTime.now().plusDays(7));
        List<SeatEntity> seats = List.of(seat(event, 1L, 1), seat(event, 2L, 2), seat(event, 3L, 3));
        given(eventRepository.existsById(1L)).willReturn(true);
        given(seatRepository.findAllByEvent_IdOrderBySeatNumber(1L)).willReturn(seats);
        // 3번 좌석은 확정됐지만 커밋 직후라 선점 키가 아직 남아 있다
        given(redisSeatHoldService.getHolders(List.of(1L, 2L, 3L))).willReturn(Arrays.asList(null, "7", "8"));
        given(reservationRepository.findSeatIdsByEventIdAndStatus(1L, ReservationStatus.RESERVED)).willReturn(List.of(3L));

        assertThat(eventService.getSeats(1L))
                .extracting(SeatResponse::status)
                .containsExactly(SeatStatus.AVAILABLE, SeatStatus.HELD, SeatStatus.RESERVED);
    }

    @Test
    @DisplayName("없는 이벤트의 좌석 목록을 조회하면 예외가 발생한다")
    void getSeats_eventNotFound() {
        given(eventRepository.existsById(99L)).willReturn(false);

        assertThatThrownBy(() -> eventService.getSeats(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이벤트가 존재하지 않습니다.");
    }

    private SeatEntity seat(EventEntity event, Long id, int seatNumber) {
        SeatEntity seat = new SeatEntity(event, seatNumber);
        ReflectionTestUtils.setField(seat, "id", id);
        return seat;
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
