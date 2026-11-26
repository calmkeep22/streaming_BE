package com.example.streaming.Controller;

import com.example.streaming.Dto.*;
import com.example.streaming.Service.MediaAuthService;
import com.example.streaming.Service.PlaybackService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "media.enabled", havingValue = "true")
public class PlaybackController {
    private final PlaybackService playback;
    private final MediaAuthService auth;

    public PlaybackController(PlaybackService playback, MediaAuthService auth) {
        this.playback = playback;
        this.auth = auth;
    }

    @PostMapping("/events/{eventId}/seats/{seatId}/playback-tickets")
    public ResponseEntity<PlaybackTicketResponse> ticket(@PathVariable Long eventId, @PathVariable Long seatId,
                                                        @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                playback.issue(eventId, seatId, Long.valueOf(jwt.getSubject()), jwt.getExpiresAt()));
    }

    @PostMapping("/internal/media/auth")
    public ResponseEntity<MediaAuthResponse> authenticate(@Valid @RequestBody MediaAuthRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.authenticate(request));
    }
}
