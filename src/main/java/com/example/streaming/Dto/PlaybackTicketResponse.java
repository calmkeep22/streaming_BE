package com.example.streaming.Dto;

import com.example.streaming.Entity.SeatGrade;
import java.time.Instant;

public record PlaybackTicketResponse(String token, String path, String whepPath,
                                     Instant expiresAt, SeatGrade grade) {
}
