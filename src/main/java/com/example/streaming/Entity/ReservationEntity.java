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

    // 예약을 만들 때 확정한 입장 방식. 이후 좌석 가격/등급 변경으로 결제 조건이 바뀌지 않는다.
    @Enumerated(EnumType.STRING)
    @Column(name = "admission_type", nullable = false, updatable = false, length = 20)
    private AdmissionType admissionType;

    @Column(name = "active_seat_id")
    private Long activeSeatId;

    private LocalDateTime canceledAt;

    // 선점은 Redis에 있으므로 DB에는 바로 확정 상태로 만든다
    public static ReservationEntity reserved(UserEntity user, SeatEntity seat) {
        return create(user, seat, AdmissionType.PAID);
    }

    public static ReservationEntity free(UserEntity user, SeatEntity seat) {
        if (seat.getGrade() != SeatGrade.FREE || seat.getPrice() != 0L) {
            throw new IllegalArgumentException("무료 좌석만 결제 없이 예약할 수 있습니다.");
        }
        return create(user, seat, AdmissionType.FREE);
    }

    private static ReservationEntity create(UserEntity user, SeatEntity seat, AdmissionType admissionType) {
        ReservationEntity reservation = new ReservationEntity();
        reservation.user = user;
        reservation.seat = seat;
        reservation.status = ReservationStatus.RESERVED;
        reservation.admissionType = admissionType;
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
