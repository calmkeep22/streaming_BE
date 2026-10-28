package com.example.streaming.Controller;


import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Service.ReservationService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    @ApiResponse(responseCode = "204", description = "예약 취소 완료", content = @Content)
    public ResponseEntity<Void> cancel(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(Objects.requireNonNull(jwt.getSubject()));
        reservationService.cancel(reservationId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<List<ReservationResponse>> myReservations(
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(Objects.requireNonNull(jwt.getSubject()));
        return ResponseEntity.ok(reservationService.findMyReservations(userId));
    }
}
