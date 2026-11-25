package com.example.streaming.Controller;

import com.example.streaming.Dto.AdmissionResponse;
import com.example.streaming.Dto.ReservationResponse;
import com.example.streaming.Service.AdmissionService;
import com.example.streaming.Service.ReservationService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
public class AdmissionController {
    private final ReservationService reservations;
    private final AdmissionService admissions;

    public AdmissionController(ReservationService reservations, AdmissionService admissions) {
        this.reservations = reservations;
        this.admissions = admissions;
    }

    @PostMapping("/events/{eventId}/seats/{seatId}/free-reservations")
    public ResponseEntity<ReservationResponse> reserveFree(
            @PathVariable Long eventId, @PathVariable Long seatId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(reservations.reserveFree(eventId, seatId, userId(jwt)));
    }

    @GetMapping("/events/{eventId}/seats/{seatId}/admission")
    public ResponseEntity<AdmissionResponse> getAdmission(
            @PathVariable Long eventId, @PathVariable Long seatId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(admissions.getAdmission(eventId, seatId, userId(jwt)));
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(Objects.requireNonNull(jwt.getSubject()));
    }
}
