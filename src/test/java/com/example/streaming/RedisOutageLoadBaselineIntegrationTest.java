package com.example.streaming;

import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.PaymentRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sentinel 적용 전 지속 장애 기준선.
 * 단일 Redis가 멈춘 동안 모든 신규 선점이 503이고, Redis가 돌아와야만 요청이 다시 성공함을 측정한다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RedisOutageLoadBaselineIntegrationTest {

    private static final int REQUESTS_PER_PHASE = 10;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.data.redis.connect-timeout", () -> "1s");
        registry.add("spring.data.redis.timeout", () -> "2s");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    ReservationRepository reservationRepository;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    UserRepository userRepository;

    private boolean redisPaused;

    @BeforeEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @AfterEach
    void resumeRedis() {
        if (redisPaused && redis.isRunning()) {
            redis.getDockerClient()
                    .unpauseContainerCmd(redis.getContainerId())
                    .exec();
            redisPaused = false;
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("단일 Redis 장애 중 선점 성공률은 0%이고 복구 후에야 다시 성공한다")
    void measureSingleRedisOutageAndRecovery() throws Exception {
        UserEntity user = userRepository.save(
                new UserEntity("redis-outage@test.com", "password")
        );
        EventEntity event = eventRepository.save(
                new EventEntity("Redis 지속 장애 실험", LocalDateTime.now().plusDays(1))
        );

        List<SeatEntity> seats = new ArrayList<>();
        for (int seatNumber = 1; seatNumber <= REQUESTS_PER_PHASE * 2 + 1; seatNumber++) {
            seats.add(new SeatEntity(event, seatNumber));
        }
        seats = seatRepository.saveAll(seats);

        List<RequestTarget> normalTargets = targets(event, user, seats.subList(0, REQUESTS_PER_PHASE));
        List<RequestTarget> outageTargets = targets(
                event,
                user,
                seats.subList(REQUESTS_PER_PHASE, REQUESTS_PER_PHASE * 2)
        );
        RequestTarget recoveryTarget = new RequestTarget(
                event.getId(),
                seats.get(REQUESTS_PER_PHASE * 2).getId(),
                user.getId()
        );

        List<RequestResult> normalResults = runConcurrently(normalTargets);

        redis.getDockerClient()
                .pauseContainerCmd(redis.getContainerId())
                .exec();
        redisPaused = true;

        List<RequestResult> outageResults = runConcurrently(outageTargets);

        long recoveryStartedAt = System.nanoTime();
        redis.getDockerClient()
                .unpauseContainerCmd(redis.getContainerId())
                .exec();
        redisPaused = false;

        RequestResult recoveryResult = waitForSuccessfulHold(recoveryTarget, 15_000L);
        long recoveryMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - recoveryStartedAt
        );

        PhaseSummary normal = summarize(normalResults);
        PhaseSummary outage = summarize(outageResults);

        System.out.printf(
                "REDIS_OUTAGE_BASELINE normal=%s outage=%s recoveryMs=%d recoveryStatus=%d%n",
                normal,
                outage,
                recoveryMillis,
                recoveryResult.status()
        );

        assertThat(normal.statusCounts()).containsEntry(201, REQUESTS_PER_PHASE);
        assertThat(outage.statusCounts()).containsEntry(503, REQUESTS_PER_PHASE);
        assertThat(outage.statusCounts().getOrDefault(201, 0)).isZero();
        assertThat(recoveryResult.status()).isEqualTo(201);
    }

    private List<RequestTarget> targets(
            EventEntity event,
            UserEntity user,
            List<SeatEntity> seats
    ) {
        return seats.stream()
                .map(seat -> new RequestTarget(event.getId(), seat.getId(), user.getId()))
                .toList();
    }

    private List<RequestResult> runConcurrently(List<RequestTarget> targets) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(targets.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(targets.size());
        List<RequestResult> results = Collections.synchronizedList(new ArrayList<>());

        for (RequestTarget target : targets) {
            executor.submit(() -> {
                try {
                    start.await();
                    results.add(performHold(target));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(new RequestResult(-1, 0L));
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        executor.shutdown();
        return List.copyOf(results);
    }

    private RequestResult waitForSuccessfulHold(RequestTarget target, long deadlineMillis)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        RequestResult latest = null;

        while (System.nanoTime() < deadline) {
            latest = performHold(target);
            if (latest.status() == 201) {
                return latest;
            }
            Thread.sleep(100L);
        }

        return latest == null ? new RequestResult(-1, deadlineMillis) : latest;
    }

    private RequestResult performHold(RequestTarget target) {
        long startedAt = System.nanoTime();
        int status;
        try {
            status = mockMvc.perform(
                            post("/events/{eventId}/seats/{seatId}/holds",
                                    target.eventId(), target.seatId())
                                    .with(jwt().jwt(token ->
                                            token.subject(target.userId().toString()))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        } catch (Exception e) {
            status = -1;
        }

        return new RequestResult(
                status,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        );
    }

    private PhaseSummary summarize(List<RequestResult> results) {
        Map<Integer, Integer> statusCounts = results.stream()
                .collect(Collectors.toMap(
                        RequestResult::status,
                        ignored -> 1,
                        Integer::sum
                ));
        List<Long> sortedLatencies = results.stream()
                .map(RequestResult::elapsedMillis)
                .sorted()
                .toList();

        return new PhaseSummary(
                statusCounts,
                percentile(sortedLatencies, 50),
                percentile(sortedLatencies, 95),
                sortedLatencies.isEmpty() ? 0L : sortedLatencies.getLast()
        );
    }

    private long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0,
                (int) Math.ceil(sorted.size() * percentile / 100.0) - 1);
        return sorted.get(index);
    }

    private record RequestTarget(Long eventId, Long seatId, Long userId) {
    }

    private record RequestResult(int status, long elapsedMillis) {
    }

    private record PhaseSummary(
            Map<Integer, Integer> statusCounts,
            long p50Millis,
            long p95Millis,
            long maxMillis
    ) {
    }
}
