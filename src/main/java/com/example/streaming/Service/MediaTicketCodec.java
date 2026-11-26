package com.example.streaming.Service;

import com.example.streaming.Config.MediaProperties;
import com.example.streaming.Dto.AdmissionResponse;
import com.example.streaming.Exception.MediaAuthenticationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "media.enabled", havingValue = "true")
public class MediaTicketCodec {
    public static final String ISSUER = "streaming-media";
    public static final String AUDIENCE = "mediamtx-read";
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public MediaTicketCodec(MediaProperties properties, @Value("${jwt.secret}") String loginSecret) {
        byte[] key = Base64.getDecoder().decode(properties.tokenSecret());
        if (key.length < 32 || MessageDigest.isEqual(key, Base64.getDecoder().decode(loginSecret))) {
            throw new IllegalArgumentException("Use an independent media signing key of at least 32 bytes");
        }
        var secret = new SecretKeySpec(key, "HmacSHA256");
        encoder = NimbusJwtEncoder.withSecretKey(secret).build();
        var verifier = NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
        verifier.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(ISSUER),
                new JwtClaimValidator<Instant>("exp", value -> value != null),
                new JwtClaimValidator<List<String>>("aud", value -> List.of(AUDIENCE).equals(value)),
                new JwtClaimValidator<String>("purpose", "media-read"::equals)));
        decoder = verifier;
    }

    public String issue(AdmissionResponse admission, Long userId, Instant now, Instant expiresAt) {
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(userId.toString()).id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(expiresAt)
                .claim("purpose", "media-read").claim("eventId", admission.eventId())
                .claim("seatId", admission.seatId()).claim("reservationId", admission.reservationId())
                .claim("grade", admission.grade().name()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public Jwt decode(String token) {
        if (token == null || token.isBlank()) throw new MediaAuthenticationException();
        try {
            return decoder.decode(token);
        } catch (JwtException | IllegalArgumentException e) {
            // Never include the token, claims, or decoder exception in logs/responses.
            throw new MediaAuthenticationException();
        }
    }
}
