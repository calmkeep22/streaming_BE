package com.example.streaming.Controller;

import com.example.streaming.Dto.*;
import com.example.streaming.Service.EventService;
import com.example.streaming.Service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;
    private final ReservationService reservationService;

    public EventController(EventService eventService, ReservationService reservationService) {
        this.eventService = eventService;
        this.reservationService = reservationService;
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
}
