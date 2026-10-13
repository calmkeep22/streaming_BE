package com.example.streaming.Dto;

import com.example.streaming.Entity.SeatStatus;

public record HoldResponse(
        Long seatId,
        Long userId,
        SeatStatus status,
        Long remainingSeconds
) {
}
