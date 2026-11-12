package com.example.streaming.Exception;

/**
 * 좌석 선점 저장소인 Redis에 연결할 수 없거나 명령이 제한 시간 안에 끝나지 않은 경우.
 * Redis 장애 시 DB로 우회하지 않고 fail-closed로 503을 반환한다.
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
