package com.example.streaming.Dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public record EventCreateRequest(
        @NotBlank
        String title,

        @NotNull
        @Future
        LocalDateTime startTime,

        // 기존 요청: STANDARD 등급, 기존 가격으로 생성한다.
        @Min(1)
        @Max(1000)
        Integer seatCount,

        @Valid @Size(min = 1, max = 4)
        List<@NotNull SeatSectionRequest> seatSections
) {
    public EventCreateRequest(String title, LocalDateTime startTime, Integer seatCount) {
        this(title, startTime, seatCount, null);
    }
}
