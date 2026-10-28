package com.example.streaming.Controller;


import com.example.streaming.Dto.EventCreateRequest;
import com.example.streaming.Dto.EventResponse;
import com.example.streaming.Dto.HoldResponse;
import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Dto.SeatResponse;
import com.example.streaming.Service.EventService;
import com.example.streaming.Service.ReservationService;
import jakarta.validation.constraints.Null;
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
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @DeleteMapping("/{reservationId}")
    public ResponseEntity<Void> cancel(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(
                        Objects.requireNonNull(jwt.getSubject())
                );

        reservationService.cancel(
                reservationId,
                userId
        );

        return ResponseEntity
                .noContent()
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<List<ReservationResponse>> myReservations(
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(Objects.requireNonNull(jwt.getSubject()));

        return ResponseEntity.ok(
                reservationService.findMyReservations(userId)
        );
    }
}
