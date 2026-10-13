package com.example.streaming.Service;

import com.example.streaming.Dto.HoldResponse;
import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.SeatStatus;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    SeatRepository seatRepository;
    @Mock
    ReservationRepository reservationRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    RedisSeatHoldService redisSeatHoldService;
    // 단위 테스트라 실제 트랜잭션은 없다. TransactionTemplate이 콜백을 그대로 실행하게만 한다
    @Mock
    PlatformTransactionManager transactionManager;

    static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    ReservationService reservationService;

    EventEntity event;
    SeatEntity seat;
    UserEntity user;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                seatRepository, reservationRepository, userRepository, redisSeatHoldService,
                new TransactionTemplate(transactionManager), new SimpleMeterRegistry(),
                HOLD_DURATION);

        event = new EventEntity("콘서트", LocalDateTime.now().plusDays(7));
        ReflectionTestUtils.setField(event, "id", 1L);

        seat = new SeatEntity(event, 10);
        ReflectionTestUtils.setField(seat, "id", 100L);

        user = new UserEntity("user1@test.com", "password");
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    // ---------- holdSeat ----------

    @Test
    @DisplayName("빈 좌석이면 Redis 선점에 성공하고 HELD와 선점 시간을 반환한다")
    void hold_success() {
        given(redisSeatHoldService.hold(100L, 1L)).willReturn(true);
        given(userRepository.existsById(1L)).willReturn(true);
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(false);

        HoldResponse response = reservationService.holdSeat(1L, 100L, 1L);

        assertThat(response.seatId()).isEqualTo(100L);
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(SeatStatus.HELD);
        assertThat(response.remainingSeconds()).isEqualTo(300L);
        verify(reservationRepository, never()).save(any());
        verify(redisSeatHoldService, never()).release(anyLong(), anyLong());
    }

    @Test
    @DisplayName("다른 사용자가 선점 중이면(SET NX 실패) DB를 조회하지 않고 예외가 발생한다")
    void hold_heldByOther() {
        given(redisSeatHoldService.hold(100L, 1L)).willReturn(false);

        assertThatThrownBy(() -> reservationService.holdSeat(1L, 100L, 1L))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessage("다른 사용자가 선점 중인 좌석입니다.");
        verifyNoInteractions(userRepository, seatRepository, reservationRepository, transactionManager);
        verify(redisSeatHoldService, never()).release(anyLong(), anyLong());
    }

    @Test
    @DisplayName("이미 최종 예약된 좌석이면 예외가 발생하고 방금 잡은 Redis 선점을 해제한다")
    void hold_alreadyReserved() {
        given(redisSeatHoldService.hold(100L, 1L)).willReturn(true);
        given(userRepository.existsById(1L)).willReturn(true);
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(true);

        assertThatThrownBy(() -> reservationService.holdSeat(1L, 100L, 1L))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessage("이미 예약된 좌석입니다.");
        verify(redisSeatHoldService).release(100L, 1L);
    }

    @Test
    @DisplayName("다른 이벤트의 좌석을 선점하면 예외가 발생하고 Redis 선점을 해제한다")
    void hold_seatOfAnotherEvent() {
        given(redisSeatHoldService.hold(100L, 1L)).willReturn(true);
        given(userRepository.existsById(1L)).willReturn(true);
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));

        assertThatThrownBy(() -> reservationService.holdSeat(2L, 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("해당 이벤트의 좌석이 아닙니다.");
        verify(redisSeatHoldService).release(100L, 1L);
    }

    @Test
    @DisplayName("존재하지 않는 사용자가 선점하면 예외가 발생하고 Redis 선점을 해제한다")
    void hold_userNotFound() {
        given(redisSeatHoldService.hold(100L, 1L)).willReturn(true);
        given(userRepository.existsById(1L)).willReturn(false);

        assertThatThrownBy(() -> reservationService.holdSeat(1L, 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자가 존재하지 않습니다.");
        verify(redisSeatHoldService).release(100L, 1L);
    }

    @Test
    @DisplayName("존재하지 않는 좌석을 선점하면 예외가 발생하고 Redis 선점을 해제한다")
    void hold_seatNotFound() {
        given(redisSeatHoldService.hold(100L, 1L)).willReturn(true);
        given(userRepository.existsById(1L)).willReturn(true);
        given(seatRepository.findById(100L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.holdSeat(1L, 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("좌석이 존재하지 않습니다.");
        verify(redisSeatHoldService).release(100L, 1L);
    }

    @Test
    @DisplayName("DB 확인 실패 후 선점 해제까지 실패해도 원래 예외를 그대로 던진다")
    void hold_releaseFailureKeepsOriginalException() {
        given(redisSeatHoldService.hold(100L, 1L)).willReturn(true);
        given(userRepository.existsById(1L)).willReturn(true);
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(true);
        given(redisSeatHoldService.release(100L, 1L)).willThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> reservationService.holdSeat(1L, 100L, 1L))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessage("이미 예약된 좌석입니다.");
    }

    // ---------- reserve ----------

    @Test
    @DisplayName("본인이 선점한 좌석이면 RESERVED 예약을 저장하고 Redis 선점을 해제한다")
    void reserve_success() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
        given(redisSeatHoldService.isHeldBy(100L, 1L)).willReturn(true);
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(false);
        given(reservationRepository.save(any(ReservationEntity.class)))
                .willAnswer(inv -> {
                    ReservationEntity r = inv.getArgument(0);
                    ReflectionTestUtils.setField(r, "id", 999L);
                    return r;
                });

        ReservationResponse response = reservationService.reserve(1L, 100L, 1L);

        assertThat(response.reservationId()).isEqualTo(999L);
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.seatId()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);

        ArgumentCaptor<ReservationEntity> captor = ArgumentCaptor.forClass(ReservationEntity.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReservationStatus.RESERVED);

        // 단위 테스트에는 트랜잭션이 없어 즉시 해제된다 (실제로는 커밋 후 해제: ReservationConcurrencyTest에서 확인)
        verify(redisSeatHoldService).release(100L, 1L);
    }

    @Test
    @DisplayName("본인 선점이 없거나 만료됐으면 예외가 발생하고 저장하지 않는다")
    void reserve_notHeldByMe() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
        given(redisSeatHoldService.isHeldBy(100L, 1L)).willReturn(false);

        assertThatThrownBy(() -> reservationService.reserve(1L, 100L, 1L))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessage("선점 중인 좌석이 아니거나 선점 시간이 만료되었습니다.");
        verify(reservationRepository, never()).save(any());
        verify(redisSeatHoldService, never()).release(anyLong(), anyLong());
    }

    @Test
    @DisplayName("이미 최종 예약된 좌석이면 예외가 발생하고 저장하지 않는다")
    void reserve_alreadyReserved() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
        given(redisSeatHoldService.isHeldBy(100L, 1L)).willReturn(true);
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(true);

        assertThatThrownBy(() -> reservationService.reserve(1L, 100L, 1L))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessage("이미 예약된 좌석입니다.");
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("확인을 통과했어도 유니크 제약에 걸리면 이미 예약된 좌석 예외로 바꾸고 선점은 해제하지 않는다")
    void reserve_uniqueConstraintViolation() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
        given(redisSeatHoldService.isHeldBy(100L, 1L)).willReturn(true);
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(false);
        given(reservationRepository.save(any(ReservationEntity.class)))
                .willThrow(new DataIntegrityViolationException("uk_reservation_seat"));

        assertThatThrownBy(() -> reservationService.reserve(1L, 100L, 1L))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessage("이미 예약된 좌석입니다.");
        verify(redisSeatHoldService, never()).release(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Redis 선점 해제가 실패해도 예약은 성공으로 응답한다")
    void reserve_releaseFailureIsIgnored() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));
        given(redisSeatHoldService.isHeldBy(100L, 1L)).willReturn(true);
        given(reservationRepository.existsBySeat_IdAndStatus(100L, ReservationStatus.RESERVED)).willReturn(false);
        given(reservationRepository.save(any(ReservationEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(redisSeatHoldService.release(100L, 1L)).willThrow(new IllegalStateException("redis down"));

        assertThat(reservationService.reserve(1L, 100L, 1L).status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("다른 이벤트의 좌석이면 예외가 발생한다")
    void reserve_seatOfAnotherEvent() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(seatRepository.findById(100L)).willReturn(Optional.of(seat));

        assertThatThrownBy(() -> reservationService.reserve(2L, 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("해당 이벤트의 좌석이 아닙니다.");
        verify(redisSeatHoldService, never()).isHeldBy(anyLong(), anyLong());
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 예외가 발생한다")
    void reserve_userNotFound() {
        given(userRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserve(1L, 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자가 존재하지 않습니다.");
        verify(seatRepository, never()).findById(any());
    }

    @Test
    @DisplayName("존재하지 않는 좌석이면 예외가 발생한다")
    void reserve_seatNotFound() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(seatRepository.findById(100L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserve(1L, 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("좌석이 존재하지 않습니다.");
    }
}
