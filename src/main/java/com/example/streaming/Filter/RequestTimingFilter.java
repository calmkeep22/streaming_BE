package com.example.streaming.Filter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RequestTimingFilter extends OncePerRequestFilter {

    private final MeterRegistry meterRegistry;
    private final Timer filterTimer;

    public RequestTimingFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.filterTimer = Timer.builder("experiment.filter.duration")
                .description("Entire HTTP request execution time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try{
            filterChain.doFilter(request, response);
        }finally {
            sample.stop(filterTimer);
        }
    }

    @Override
    protected boolean shouldNotFilter(
            HttpServletRequest request
    ){
        return !request.getRequestURI()
                .startsWith("/events/seats/");
    }
}
