package com.example.streaming.Entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        // 좌석당 확정 예약은 1건. Redis 선점과 DB 확정 사이의 경쟁을 막는 최종 방어선
        uniqueConstraints = @UniqueConstraint(name = "uk_reservation_seat", columnNames = "seat_id")
)
public class ReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    private SeatEntity seat;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    // 선점은 Redis에 있으므로 DB에는 바로 확정 상태로 만든다
    public static ReservationEntity reserved(UserEntity user, SeatEntity seat) {
        ReservationEntity reservation = new ReservationEntity();
        reservation.user = user;
        reservation.seat = seat;
        reservation.status = ReservationStatus.RESERVED;
        return reservation;
    }
}
