package com.example.streaming.Aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ServiceTimingAspect {
    private final MeterRegistry meterRegistry;
    private final Timer serviceTimer;


    public ServiceTimingAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.serviceTimer = Timer.builder("experiment.service.duration")
                .description("EventService.getSeat execution time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Around(
            "execution(* com.example.streaming.Service.EventService.getSeat(..))"
    )
    public Object measureGetSeat(
            ProceedingJoinPoint joinPoint
    )throws Throwable {

        Timer.Sample sample = Timer.start(meterRegistry);
        try{
            return joinPoint.proceed();
        }
        finally {
            sample.stop(serviceTimer);
        }
    }


}
