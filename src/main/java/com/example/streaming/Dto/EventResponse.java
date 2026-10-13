package com.example.streaming.Dto;

import java.time.LocalDateTime;

public record EventResponse(
        Long id,
        String title,
        LocalDateTime startTime
) {
}