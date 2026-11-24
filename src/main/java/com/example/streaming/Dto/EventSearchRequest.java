package com.example.streaming.Dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record EventSearchRequest(
        @Size(max = 255) String keyword,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size
) {
    public EventSearchRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}
