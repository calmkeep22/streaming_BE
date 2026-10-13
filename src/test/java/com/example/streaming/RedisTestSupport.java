package com.example.streaming;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 로컬 Redis(localhost:6379)가 떠 있는지 확인한다.
 * Redis가 필요한 테스트는 Assumptions.assumeTrue(RedisTestSupport.isAvailable())로 Redis가 없으면 건너뛴다.
 */
public final class RedisTestSupport {

    public static final String HOST = System.getenv().getOrDefault("REDIS_TEST_HOST", "localhost");
    public static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379"));
    public static final int TEST_DATABASE = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_DATABASE", "14"));

    private RedisTestSupport() {
    }

    public static boolean isAvailable() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(HOST, PORT);
        config.setDatabase(TEST_DATABASE);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(1)).shutdownTimeout(Duration.ZERO).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, client);
        try {
            factory.afterPropertiesSet();
            factory.start();
            try (var connection = factory.getConnection()) {
                return "PONG".equals(connection.ping());
            }
        } catch (RuntimeException e) {
            return false;
        } finally {
            factory.destroy();
        }
    }

    public static void requireAvailable() {
        boolean available = isAvailable();
        String message = "테스트 Redis 연결 실패: " + HOST + ":" + PORT;
        if (Boolean.parseBoolean(System.getenv("REQUIRE_REDIS_TESTS"))) {
            assertTrue(available, message);
        } else {
            assumeTrue(available, message);
        }
    }
}
