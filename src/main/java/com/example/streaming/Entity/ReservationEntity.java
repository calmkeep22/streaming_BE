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
        // 좌석당 확정(RESERVED) 예약은 1건. Redis 선점과 DB 확정 사이의 경쟁을 막는 최종 방어선
        // seat_id에 걸면 취소된 좌석을 다시 예약할 수 없으므로, RESERVED일 때만 값이 있는 active_seat_id에 건다
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

    // RESERVED면 seat_id와 같고, 취소되면 NULL. MySQL 유니크 인덱스는 NULL 중복을 허용하므로 취소 이력은 여러 건 남을 수 있다
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

    public void cancel(){
        if(this.status == ReservationStatus.CANCELED){
            return;
        }
        this.status = ReservationStatus.CANCELED;
        this.activeSeatId = null;
        this.canceledAt = LocalDateTime.now();
    }
}
