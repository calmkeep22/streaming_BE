package com.example.streaming.Controller;


import com.example.streaming.Dto.EventCreateRequest;
import com.example.streaming.Dto.EventResponse;
import com.example.streaming.Dto.HoldResponse;
import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Dto.SeatResponse;
import com.example.streaming.Service.EventService;
import com.example.streaming.Service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/events")
public class ReservationController {

    private final ReservationService reservationService;
    private final EventService eventService;


    public ReservationController(ReservationService reservationService, EventService eventService) {
        this.reservationService = reservationService;
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(
        @RequestBody EventCreateRequest request
    ){
        EventResponse event = eventService.createEvent(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(event);
    }

    @GetMapping("/seats/{seatId}")
    public ResponseEntity<SeatResponse> getSeat(
            @PathVariable Long seatId
    ) {
        SeatResponse response = eventService.getSeat(seatId);

        return ResponseEntity.ok(response);
    }

    // 1단계: 좌석 선점
    @PostMapping("/{eventId}/seats/{seatId}/holds")
    public ResponseEntity<HoldResponse> hold(
            @PathVariable Long eventId,
            @PathVariable Long seatId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(Objects.requireNonNull(jwt.getSubject()));
        HoldResponse response =
                reservationService.holdSeat(eventId, seatId, userId);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    // 2단계: 선점한 좌석 예약 확정
    @PostMapping("/{eventId}/seats/{seatId}/reservations")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable Long eventId,
            @PathVariable Long seatId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(Objects.requireNonNull(jwt.getSubject()));
        ReservationResponse response =
                reservationService.reserve(eventId, seatId, userId);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @DeleteMapping("/reservations/{reservationId}")
    public ResponseEntity<Void> cancel(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(Objects.requireNonNull(jwt.getSubject()));
        reservationService.cancel(reservationId, userId);
        return ResponseEntity.noContent().build();
    }
}
