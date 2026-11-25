package com.example.streaming.Dto;

import com.example.streaming.Entity.SeatStatus;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.SeatGrade;

public record SeatResponse(
        Long id,
        Integer seatNumber,
        SeatStatus status,
        SeatGrade grade,
        Long price
) {
    public static SeatResponse from(SeatEntity seat, SeatStatus status) {
        return new SeatResponse(seat.getId(), seat.getSeatNumber(), status, seat.getGrade(), seat.getPrice());
    }
}
