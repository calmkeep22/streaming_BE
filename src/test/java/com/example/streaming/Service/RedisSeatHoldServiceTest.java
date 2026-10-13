package com.example.streaming.Service;

import com.example.streaming.RedisTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 로컬 Redis로 SET NX / TTL / compareAndDelete 동작을 확인한다. Redis가 없으면 건너뛴다.
 */
class RedisSeatHoldServiceTest {

    static final long SEAT_ID = 10L;
    static final Duration TTL = Duration.ofMinutes(5);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redisTemplate;

    RedisSeatHoldService holdService = new RedisSeatHoldService(redisTemplate, TTL);

    @BeforeAll
    static void connect() {
        assumeTrue(RedisTestSupport.isAvailable(), "로컬 Redis(localhost:6379)가 없어 건너뜁니다.");

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(RedisTestSupport.HOST, RedisTestSupport.PORT);
        config.setDatabase(RedisTestSupport.TEST_DATABASE);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(holdService.createKey(SEAT_ID));
    }

    @Test
    @DisplayName("빈 좌석은 선점에 성공하고, 키에 사용자 id가 TTL과 함께 저장된다")
    void hold_success() {
        assertThat(holdService.hold(SEAT_ID, 1L)).isTrue();

        assertThat(holdService.getHolder(SEAT_ID)).isEqualTo("1");
        assertThat(holdService.isHeldBy(SEAT_ID, 1L)).isTrue();
        assertThat(holdService.getRemainingSeconds(SEAT_ID)).isBetween(1L, TTL.toSeconds());
    }

    @Test
    @DisplayName("이미 선점된 좌석은 다른 사용자도, 본인도 다시 선점할 수 없고 기존 값이 유지된다")
    void hold_alreadyHeld() {
        holdService.hold(SEAT_ID, 1L);

        assertThat(holdService.hold(SEAT_ID, 2L)).isFalse();
        assertThat(holdService.hold(SEAT_ID, 1L)).isFalse();
        assertThat(holdService.getHolder(SEAT_ID)).isEqualTo("1");
        assertThat(holdService.isHeldBy(SEAT_ID, 2L)).isFalse();
    }

    @Test
    @DisplayName("선점이 없으면 holder는 null이고 남은 시간은 -2다")
    void noHold() {
        assertThat(holdService.getHolder(SEAT_ID)).isNull();
        assertThat(holdService.isHeldBy(SEAT_ID, 1L)).isFalse();
        assertThat(holdService.getRemainingSeconds(SEAT_ID)).isEqualTo(-2L);
    }

    @Test
    @DisplayName("본인 선점만 해제할 수 있고, 다른 사용자의 해제 요청은 무시된다")
    void release_onlyOwner() {
        holdService.hold(SEAT_ID, 1L);

        assertThat(holdService.release(SEAT_ID, 2L)).isFalse();
        assertThat(holdService.getHolder(SEAT_ID)).isEqualTo("1");

        assertThat(holdService.release(SEAT_ID, 1L)).isTrue();
        assertThat(holdService.getHolder(SEAT_ID)).isNull();
    }

    @Test
    @DisplayName("TTL이 지나면 선점이 풀리고, 늦게 도착한 이전 사용자의 해제 요청은 새 선점을 지우지 않는다")
    void expire_thenLateReleaseDoesNotDeleteNewHold() throws InterruptedException {
        RedisSeatHoldService shortHold = new RedisSeatHoldService(redisTemplate, Duration.ofMillis(500));
        shortHold.hold(SEAT_ID, 1L);

        Thread.sleep(800);

        assertThat(shortHold.getHolder(SEAT_ID)).isNull();
        assertThat(holdService.hold(SEAT_ID, 2L)).isTrue();

        assertThat(holdService.release(SEAT_ID, 1L)).isFalse();
        assertThat(holdService.getHolder(SEAT_ID)).isEqualTo("2");
    }
}
