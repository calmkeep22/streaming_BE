package com.example.streaming.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(createKey(seatId), userId.toString(), holdTtl);
        return Boolean.TRUE.equals(success);
    }

    // 선점한 사용자 id. 선점이 없거나 만료됐으면 null
    public String getHolder(Long seatId) {
        return redisTemplate.opsForValue().get(createKey(seatId));
    }

    public boolean isHeldBy(Long seatId, Long userId) {
        return userId.toString().equals(getHolder(seatId));
    }

    // 남은 선점 시간(초). 키가 없으면 -2, TTL이 없으면 -1 (Redis TTL 명령 규칙)
    public Long getRemainingSeconds(Long seatId) {
        return redisTemplate.getExpire(createKey(seatId), TimeUnit.SECONDS);
    }

    /**
     * 현재 값이 내 userId일 때만 삭제한다.
     * 그냥 DELETE 하면, 내 선점이 만료된 뒤 다른 사람이 잡은 선점을 늦게 도착한 내 요청이 지울 수 있다.
     * redisTemplate.compareAndDelete()는 Redis 8.4+의 DELEX 명령을 써서 7.x에서는 실패하므로,
     * 버전과 무관하게 원자적으로 동작하는 Lua 스크립트(GET 비교 + DEL)를 쓴다.
     */
    public boolean release(Long seatId, Long userId) {
        Long deleted = redisTemplate.execute(RELEASE_SCRIPT, List.of(createKey(seatId)), userId.toString());
        return Long.valueOf(1L).equals(deleted);
    }

    public Duration getHoldTtl() {
        return holdTtl;
    }

    public String createKey(Long seatId) {
        return KEY_PREFIX + seatId;
    }
}
