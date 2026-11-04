package com.example.streaming.Dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record EventCreateRequest(
        @NotBlank
        String title,

        @NotNull
        @Future
        LocalDateTime startTime,

        // 좌석 번호는 1부터 seatCount까지 만든다
        @NotNull
        @Min(1)
        @Max(1000)
        Integer seatCount
) {
}
