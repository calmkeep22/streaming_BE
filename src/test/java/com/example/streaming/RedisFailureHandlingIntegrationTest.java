package com.example.streaming;

import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Entity.UserEntity;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.ReservationRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Repository.UserRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 단일 Redis 장애 대응 회귀 테스트.
 * 장애 시 DB로 우회하지 않고 짧은 시간 안에 일관된 503을 반환하는지 검증한다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RedisFailureHandlingIntegrationTest {

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
    ReservationRepository reservationRepository;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("단일 Redis 중단 시 선점 요청은 빠르게 503으로 실패한다")
    void holdFailsFastWithServiceUnavailableWhenSingleRedisStops() throws Exception {
        UserEntity user = userRepository.save(
                new UserEntity("redis-failure@test.com", "password")
        );
        EventEntity event = eventRepository.save(
                new EventEntity("Redis 장애 실험", LocalDateTime.now().plusDays(1))
        );
        SeatEntity firstSeat = seatRepository.save(new SeatEntity(event, 1));
        SeatEntity secondSeat = seatRepository.save(new SeatEntity(event, 2));

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/holds", event.getId(), firstSeat.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isCreated());

        redis.stop();

        long startedAt = System.nanoTime();
        MvcResult result = mockMvc.perform(
                        post("/events/{eventId}/seats/{seatId}/holds", event.getId(), secondSeat.getId())
                                .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("좌석 선점 서비스를 일시적으로 사용할 수 없습니다."))
                .andReturn();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        System.out.printf("REDIS_FAILURE_IMPROVED elapsedMs=%d status=%d%n",
                elapsedMillis,
                result.getResponse().getStatus());

        assertThat(elapsedMillis)
                .as("Redis 장애 요청은 기존 1분 타임아웃보다 충분히 빠르게 끝나야 한다")
                .isLessThan(10_000L);
    }
}
