package com.example.streaming.Dto;

import com.example.streaming.Entity.ReservationStatus;

public record ReservationResponse(
        Long reservationId,
        Long userId,
        Long seatId,
        ReservationStatus status
) {
}
