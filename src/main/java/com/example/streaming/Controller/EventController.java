package com.example.streaming.Controller;

import com.example.streaming.Dto.*;
import com.example.streaming.Service.EventService;
import com.example.streaming.Service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    // from/to: YYYY-MM-DD, 양 끝 날짜 포함. page는 0부터 시작한다.
    @GetMapping
    public ResponseEntity<EventPageResponse> getEvents(@Valid @ModelAttribute EventSearchRequest request) {
        return ResponseEntity.ok(eventService.getEvents(request));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.getEvent(eventId));
    }

    // 관리자만 호출할 수 있다 (SecurityConfig)
    @PostMapping
    public ResponseEntity<EventResponse> createEvent(
            @Valid @RequestBody EventCreateRequest request
    ){
        EventResponse event = eventService.createEvent(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(event);
    }

    @GetMapping("/{eventId}/seats")
    public ResponseEntity<List<SeatResponse>> getSeats(
            @PathVariable Long eventId
    ) {
        return ResponseEntity.ok(eventService.getSeats(eventId));
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

}
