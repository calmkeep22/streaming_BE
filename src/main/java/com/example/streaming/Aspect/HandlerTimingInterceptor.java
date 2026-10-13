package com.example.streaming.Aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class HandlerTimingInterceptor implements HandlerInterceptor {

    private static final String TIMER_SAMPLE =
            "experiment.interceptor.timer.sample";
    private final MeterRegistry meterRegistry;
    private final Timer interceptorTimer;

    public HandlerTimingInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.interceptorTimer = Timer.builder("experiment.interceptor.duration")
                .description("MVC handler execution time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ){
        Timer.Sample sample = Timer.start(meterRegistry);
        request.setAttribute(
                TIMER_SAMPLE,
                sample
        );
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ){
        Timer.Sample sample = (Timer.Sample)request.getAttribute(
                TIMER_SAMPLE
        );
        if (sample != null){
            sample.stop(interceptorTimer);
        }
    }
}
