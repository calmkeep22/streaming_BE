package com.example.streaming.Monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Redis 좌석 선점 호출의 결과와 지연 시간을 기록한다.
 * operation/result 값은 코드에 정의된 소수의 값만 사용해 시계열 카디널리티를 제한한다.
 */
@Component
public class RedisSeatHoldMetrics {

    private static final String OPERATION_METRIC = "seat.hold.redis.operations";
    private static final String DURATION_METRIC = "seat.hold.redis.duration";

    private final MeterRegistry meterRegistry;

    public RedisSeatHoldMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    public void record(String operation, String result, Timer.Sample sample) {
        Counter.builder(OPERATION_METRIC)
                .description("Redis seat hold operations by operation and result")
                .tags("operation", operation, "result", result)
                .register(meterRegistry)
                .increment();

        sample.stop(Timer.builder(DURATION_METRIC)
                .description("Redis seat hold operation latency")
                .tags("operation", operation, "result", result)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }
}
