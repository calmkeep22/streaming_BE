package com.example.streaming.Dto;

import com.example.streaming.Entity.SeatStatus;

public record SeatResponse(
        Long id,
        Integer seatNumber,
        SeatStatus status
) {
}