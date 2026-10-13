package com.example.streaming.Entity;

// DB에는 확정 예약과 취소 이력을 남기고, 선점(HELD)은 Redis에서 관리한다.
// 좌석 화면에 보여줄 상태(AVAILABLE, HELD 포함)는 SeatStatus
public enum ReservationStatus {
    RESERVED,
    CANCELED
}
