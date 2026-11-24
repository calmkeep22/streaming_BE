package com.example.streaming.Dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record EventPageResponse(
        List<EventResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static EventPageResponse from(Page<EventResponse> result) {
        return new EventPageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }
}
