package com.example.streaming.Dto;

import com.example.streaming.Entity.SeatGrade;

public record AdmissionResponse(Long reservationId, Long eventId, Long seatId, SeatGrade grade) {
}
