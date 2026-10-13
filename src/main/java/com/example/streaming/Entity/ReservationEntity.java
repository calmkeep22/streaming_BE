package com.example.streaming.Entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        // 확정 예약만 좌석당 1건으로 제한하고, 취소 이력은 유지한다.
        uniqueConstraints = @UniqueConstraint(name = "uk_reservation_active_seat", columnNames = "active_seat_id")
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

    @Column(name = "active_seat_id")
    private Long activeSeatId;

    private LocalDateTime canceledAt;

    // 선점은 Redis에 있으므로 DB에는 바로 확정 상태로 만든다
    public static ReservationEntity reserved(UserEntity user, SeatEntity seat) {
        ReservationEntity reservation = new ReservationEntity();
        reservation.user = user;
        reservation.seat = seat;
        reservation.status = ReservationStatus.RESERVED;
        reservation.activeSeatId = seat.getId();
        return reservation;
    }

    public void cancel() {
        if (status == ReservationStatus.CANCELED) {
            return;
        }
        status = ReservationStatus.CANCELED;
        activeSeatId = null;
        canceledAt = LocalDateTime.now();
    }
}
