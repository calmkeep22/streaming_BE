package com.example.streaming.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("media")
public record MediaProperties(String tokenSecret, Duration ticketTtl,
                              String publisherPasswordHash, String monitorPasswordHash) {
    public MediaProperties {
        ticketTtl = ticketTtl == null ? Duration.ofMinutes(2) : ticketTtl;
        if (ticketTtl.compareTo(Duration.ofSeconds(1)) < 0 || ticketTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Media ticket lifetime must be between 1 second and 5 minutes");
        }
        if (publisherPasswordHash == null || !publisherPasswordHash.matches("[a-f0-9]{64}")
                || monitorPasswordHash == null || !monitorPasswordHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Media machine credential hashes must be configured");
        }
    }
}
