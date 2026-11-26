package com.example.streaming.Dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// MediaMTX also sends ip/id/query/userAgent; none of those are trusted as authorization.
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaAuthRequest(@Size(max = 64) String user, @Size(max = 4096) String password,
                               @Size(max = 4096) String token, @NotNull @Size(max = 16) String action,
                               @NotNull @Size(max = 256) String path, @Size(max = 16) String protocol) {
}
