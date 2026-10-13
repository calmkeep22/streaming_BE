package com.example.streaming.Entity;

// 예약 행의 상태. 선점(HELD)은 Redis에만 있으므로 DB 예약 행은 확정 상태만 가진다.
// 좌석 화면에 보여줄 상태(AVAILABLE, HELD 포함)는 SeatStatus
public enum ReservationStatus {
    RESERVED
}
