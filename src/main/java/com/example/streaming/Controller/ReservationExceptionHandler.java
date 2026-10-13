package com.example.streaming.Controller;

import com.example.streaming.Exception.SeatUnavailableException;
import com.example.streaming.Exception.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 예약 예외를 4xx/503으로 매핑한다.
 * 매핑하지 않으면 예외가 Tomcat까지 전파돼 스택트레이스 ERROR 로그 + /error 재디스패치를 거쳐 500이 된다.
 * 실험 03에서 이 경로를 없애자 hot 좌석 부하의 처리량이 52 → 78 req/s로 올랐다.
 */
@RestControllerAdvice
public class ReservationExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    // 다른 사용자가 먼저 선점/예약한 정상적인 경쟁 실패 → 409
    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleConflict(SeatUnavailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    // 커넥션 풀 대기 제한(hikari.connection-timeout) 초과 → 트랜잭션 시작 실패. 서버가 바쁜 것이므로 503
    @ExceptionHandler(CannotCreateTransactionException.class)
    public ResponseEntity<Map<String, String>> handleBusy(CannotCreateTransactionException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "요청이 많아 잠시 후 다시 시도해 주세요."));
    }

    // 로그인 실패(이메일 없음/비밀번호 불일치). 매핑하지 않으면 Security의 기본 EntryPoint가 403으로 바꿔버린다
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthFailure(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }
}
