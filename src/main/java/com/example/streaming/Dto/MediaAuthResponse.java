package com.example.streaming.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaAuthResponse(Instant expireAt) {
}
