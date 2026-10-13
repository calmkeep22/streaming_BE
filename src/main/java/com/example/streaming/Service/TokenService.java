package com.example.streaming.Service;

import com.example.streaming.Entity.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final long accessTokenExpiration;

    public TokenService(
            JwtEncoder jwtEncoder,
            @Value("${jwt.access-token-expiration}")
            long accessTokenExpiration
    ) {
        this.jwtEncoder = jwtEncoder;
        this.accessTokenExpiration = accessTokenExpiration;
    }

    public String createAccessToken(
            UserEntity user
    ) {

        Instant now = Instant.now();

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject(
                                user.getId().toString()
                        )
                        .issuedAt(now)
                        .expiresAt(
                                now.plusSeconds(
                                        accessTokenExpiration
                                )
                        )
                        .claim(
                                "email",
                                user.getEmail()
                        )
                        .claim(
                                "role",
                                user.getRole().name()
                        )
                        .build();

        JwtEncoderParameters parameters =
                JwtEncoderParameters.from(claims);

        return jwtEncoder
                .encode(parameters)
                .getTokenValue();
    }
}