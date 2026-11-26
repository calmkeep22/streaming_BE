package com.example.streaming.Service;

import com.example.streaming.Config.MediaProperties;
import com.example.streaming.Dto.PlaybackTicketResponse;
import com.example.streaming.Exception.AdmissionDeniedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@ConditionalOnProperty(name = "media.enabled", havingValue = "true")
public class PlaybackService {
    private final AdmissionService admissions;
    private final MediaTicketCodec tickets;
    private final MediaProperties properties;

    public PlaybackService(AdmissionService admissions, MediaTicketCodec tickets, MediaProperties properties) {
        this.admissions = admissions;
        this.tickets = tickets;
        this.properties = properties;
    }

    public PlaybackTicketResponse issue(Long eventId, Long seatId, Long userId, Instant loginExpiresAt) {
        var admission = admissions.getAdmission(eventId, seatId, userId);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if (loginExpiresAt == null || !loginExpiresAt.isAfter(now)) throw new AdmissionDeniedException();
        Instant expiresAt = now.plus(properties.ticketTtl());
        if (expiresAt.isAfter(loginExpiresAt)) expiresAt = loginExpiresAt;
        String path = "events/" + eventId;
        return new PlaybackTicketResponse(tickets.issue(admission, userId, now, expiresAt), path,
                "/" + path + "/whep", expiresAt, admission.grade());
    }
}
