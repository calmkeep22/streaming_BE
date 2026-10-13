package com.example.streaming.Dto;

import java.time.LocalDateTime;

public record EventCreateRequest(
        String title,
        LocalDateTime startTime
) {
}
