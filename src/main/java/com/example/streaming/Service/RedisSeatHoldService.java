package com.example.streaming.Service;

import com.example.streaming.Exception.RedisUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 좌석 임시 선점(HOLD)을 Redis로 처리한다.
 * seat:hold:{seatId} → "{userId}" 를 TTL과 함께 저장하고, SET NX로 한 명만 성공하게 한다.
 */
@Service
public class RedisSeatHoldService {

    private static final String KEY_PREFIX = "seat:hold:";

    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration holdTtl;

    public RedisSeatHoldService(StringRedisTemplate redisTemplate,
                                @Value("${reservation.hold-duration:PT5M}") Duration holdTtl) {
        this.redisTemplate = redisTemplate;
        this.holdTtl = holdTtl;
    }

    // SET key value NX EX ttl → 키가 없을 때만 저장(true), 이미 있으면 false
    public boolean hold(Long seatId, Long userId) {
        Boolean success = executeRedis(() -> redisTemplate.opsForValue()
                .setIfAbsent(createKey(seatId), userId.toString(), holdTtl));
        return Boolean.TRUE.equals(success);
    }

    // 선점한 사용자 id. 선점이 없거나 만료됐으면 null
    public String getHolder(Long seatId) {
        return executeRedis(() -> redisTemplate.opsForValue().get(createKey(seatId)));
    }

    // 여러 좌석의 선점 사용자 id를 MGET 한 번으로 읽는다. 결과는 seatIds 순서와 같고, 선점이 없으면 null
    public List<String> getHolders(List<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        List<String> holders = redisTemplate.opsForValue()
                .multiGet(seatIds.stream().map(this::createKey).toList());
        return holders != null ? holders : Collections.nCopies(seatIds.size(), null);
    }

    public boolean isHeldBy(Long seatId, Long userId) {
        return userId.toString().equals(getHolder(seatId));
    }

    // 남은 선점 시간(초). 키가 없으면 -2, TTL이 없으면 -1 (Redis TTL 명령 규칙)
    public Long getRemainingSeconds(Long seatId) {
        return executeRedis(() -> redisTemplate.getExpire(createKey(seatId), TimeUnit.SECONDS));
    }

    /**
     * 현재 값이 내 userId일 때만 삭제한다.
     * 그냥 DELETE 하면, 내 선점이 만료된 뒤 다른 사람이 잡은 선점을 늦게 도착한 내 요청이 지울 수 있다.
     * redisTemplate.compareAndDelete()는 Redis 8.4+의 DELEX 명령을 써서 7.x에서는 실패하므로,
     * 버전과 무관하게 원자적으로 동작하는 Lua 스크립트(GET 비교 + DEL)를 쓴다.
     */
    public boolean release(Long seatId, Long userId) {
        Long deleted = executeRedis(() ->
                redisTemplate.execute(RELEASE_SCRIPT, List.of(createKey(seatId)), userId.toString()));
        return Long.valueOf(1L).equals(deleted);
    }

    private <T> T executeRedis(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(
                    "좌석 선점 저장소를 일시적으로 사용할 수 없습니다.",
                    e
            );
        }
    }

    public Duration getHoldTtl() {
        return holdTtl;
    }

    public String createKey(Long seatId) {
        return KEY_PREFIX + seatId;
    }
}
