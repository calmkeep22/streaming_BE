package com.example.streaming.Dto;

import com.example.streaming.Entity.SeatGrade;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SeatSectionRequest(
        @NotNull SeatGrade grade,
        @NotNull @Min(1) @Max(1000) Integer seatCount,
        @NotNull @PositiveOrZero Long price
) {
}
