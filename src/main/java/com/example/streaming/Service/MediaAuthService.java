package com.example.streaming.Service;

import com.example.streaming.Config.MediaProperties;
import com.example.streaming.Dto.MediaAuthRequest;
import com.example.streaming.Dto.MediaAuthResponse;
import com.example.streaming.Exception.AdmissionDeniedException;
import com.example.streaming.Exception.MediaAuthenticationException;
import com.example.streaming.Repository.EventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@ConditionalOnProperty(name = "media.enabled", havingValue = "true")
public class MediaAuthService {
    private final MediaProperties properties;
    private final MediaTicketCodec tickets;
    private final AdmissionService admissions;
    private final EventRepository events;

    public MediaAuthService(MediaProperties properties, MediaTicketCodec tickets, AdmissionService admissions, EventRepository events) {
        this.properties = properties;
        this.tickets = tickets;
        this.admissions = admissions;
        this.events = events;
    }

    public MediaAuthResponse authenticate(MediaAuthRequest request) {
        if ("api".equals(request.action()) && "monitor".equals(request.user())
                && matches(request.password(), properties.monitorPasswordHash())) {
            return new MediaAuthResponse(null);
        }
        if ("publish".equals(request.action()) && "publisher".equals(request.user())
                && matches(request.password(), properties.publisherPasswordHash())
                && validPath(request.path()) && events.existsById(eventId(request.path()))) {
            return new MediaAuthResponse(null);
        }
        if (!"read".equals(request.action()) || !"webrtc".equals(request.protocol()) || !validPath(request.path())) {
            throw new MediaAuthenticationException();
        }
        Jwt ticket = tickets.decode(request.token());
        try {
            Long eventId = positive(ticket.getClaim("eventId"));
            Long seatId = positive(ticket.getClaim("seatId"));
            Long userId = positive(ticket.getSubject());
            if (!request.path().equals("events/" + eventId)) throw new MediaAuthenticationException();
            var admission = admissions.getAdmission(eventId, seatId, userId);
            if (!admission.reservationId().equals(positive(ticket.getClaim("reservationId")))
                    || !admission.grade().name().equals(ticket.getClaimAsString("grade"))) {
                throw new MediaAuthenticationException();
            }
            return new MediaAuthResponse(ticket.getExpiresAt());
        } catch (AdmissionDeniedException | IllegalArgumentException e) {
            throw new MediaAuthenticationException();
        }
    }

    private boolean validPath(String path) {
        if (path == null || !path.matches("events/[1-9][0-9]{0,18}")) return false;
        try { eventId(path); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private Long eventId(String path) { return positive(path.substring("events/".length())); }

    private Long positive(Object value) {
        long number = Long.parseLong(String.valueOf(value));
        if (number <= 0) throw new IllegalArgumentException("Invalid identifier");
        return number;
    }

    private boolean matches(String supplied, String expectedHash) {
        if (supplied == null || supplied.isBlank()) return false;
        try {
            return MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(supplied.getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().parseHex(expectedHash));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
