package com.example.streaming.Repository;

import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock으로는 검증이 안 되는 파생 쿼리와 유니크 제약을 실제 DB(H2)로 확인한다.
 */
@DataJpaTest
class ReservationRepositoryTest {

    @Autowired
    ReservationRepository reservationRepository;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    SeatRepository seatRepository;
    @Autowired
    UserRepository userRepository;

    SeatEntity seat;
    UserEntity user;

    @BeforeEach
    void setUp() {
        EventEntity event = eventRepository.save(new EventEntity("콘서트", LocalDateTime.now().plusDays(7)));
        seat = seatRepository.save(new SeatEntity(event, 1));
        user = userRepository.save(new UserEntity("user1@test.com", "password"));
    }

    @Test
    @DisplayName("좌석에 RESERVED 예약이 있을 때만 existsBySeat_IdAndStatus가 true다")
    void existsReserved() {
        assertThat(reservationRepository.existsBySeat_IdAndStatus(seat.getId(), ReservationStatus.RESERVED)).isFalse();

        reservationRepository.save(ReservationEntity.reserved(user, seat));

        assertThat(reservationRepository.existsBySeat_IdAndStatus(seat.getId(), ReservationStatus.RESERVED)).isTrue();
    }

    @Test
    @DisplayName("같은 좌석에 예약을 두 번 저장하면 유니크 제약 위반이 발생한다")
    void uniqueSeat() {
        UserEntity otherUser = userRepository.save(new UserEntity("user2@test.com", "password"));
        reservationRepository.saveAndFlush(ReservationEntity.reserved(user, seat));

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(ReservationEntity.reserved(otherUser, seat)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test
    @DisplayName("취소된 좌석은 다시 예약할 수 있고 취소 이력은 남는다")
    void reReserveAfterCancel() {
        UserEntity otherUser = userRepository.save(new UserEntity("user2@test.com", "password"));
        ReservationEntity first = reservationRepository.saveAndFlush(ReservationEntity.reserved(user, seat));
        first.cancel();
        reservationRepository.saveAndFlush(first);
        reservationRepository.saveAndFlush(ReservationEntity.reserved(otherUser, seat));

        assertThat(reservationRepository.count()).isEqualTo(2);
        assertThat(reservationRepository.existsBySeat_IdAndStatus(seat.getId(), ReservationStatus.RESERVED)).isTrue();
        assertThat(reservationRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("취소 이력이 여러 건이어도 유니크 제약에 걸리지 않는다")
    void multipleCanceled() {
        for (int i = 0; i < 2; i++) {
            ReservationEntity reservation = reservationRepository.saveAndFlush(ReservationEntity.reserved(user, seat));
            reservation.cancel();
            reservationRepository.saveAndFlush(reservation);
        }
        assertThat(reservationRepository.count()).isEqualTo(2);
        assertThat(reservationRepository.existsBySeat_IdAndStatus(seat.getId(), ReservationStatus.RESERVED)).isFalse();
    }

    @Test
    @DisplayName("findAllByUser_Id는 해당 사용자의 예약만 취소 이력까지 반환한다")
    void findAllByUser() {
        UserEntity otherUser = userRepository.save(new UserEntity("user2@test.com", "password"));
        SeatEntity otherSeat = seatRepository.save(new SeatEntity(seat.getEvent(), 2));
        ReservationEntity canceled = reservationRepository.saveAndFlush(ReservationEntity.reserved(user, seat));
        canceled.cancel();
        reservationRepository.saveAndFlush(canceled);
        reservationRepository.saveAndFlush(ReservationEntity.reserved(otherUser, seat));
        reservationRepository.saveAndFlush(ReservationEntity.reserved(user, otherSeat));

        assertThat(reservationRepository.findAllByUser_Id(user.getId()))
                .extracting(ReservationEntity::getStatus)
                .containsExactlyInAnyOrder(ReservationStatus.CANCELED, ReservationStatus.RESERVED);
    }
}
