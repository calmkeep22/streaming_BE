package com.example.streaming.Dto;

import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;

public record ReservationResponse(
        Long reservationId,
        Long userId,
        Long seatId,
        ReservationStatus status
) {

    public static ReservationResponse from(ReservationEntity reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getUser().getId(),
                reservation.getSeat().getId(),
                reservation.getStatus()
        );
    }
}
