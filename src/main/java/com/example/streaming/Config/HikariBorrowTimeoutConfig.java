package com.example.streaming.Config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * [5-b] 풀에서 커넥션을 "빌릴 때"만 대기 제한을 건다.
 *
 * hikari.connection-timeout 을 1초로 두면 DB 최초 접속(로그인) 제한도 1초가 되어 기동이 실패하고,
 * HikariCP 7 은 풀 생성 시 이 값을 복사해 두므로 기동 후 MXBean 으로 바꿔도 반영되지 않는다.
 * 그래서 DataSource 를 감싸 HikariPool.getConnection(timeout) 을 직접 호출한다.
 * 시간 초과 시 SQLTransientConnectionException → CannotCreateTransactionException → 503.
 */
@Component
@ConditionalOnProperty(name = "loadtest.hikari.borrow-timeout-ms")
public class HikariBorrowTimeoutConfig implements BeanPostProcessor {

    private final long borrowTimeoutMs;

    public HikariBorrowTimeoutConfig(@Value("${loadtest.hikari.borrow-timeout-ms}") long borrowTimeoutMs) {
        this.borrowTimeoutMs = borrowTimeoutMs;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource hikari) {
            return new BorrowTimeoutDataSource(hikari, borrowTimeoutMs);
        }
        return bean;
    }

    static class BorrowTimeoutDataSource extends DelegatingDataSource {
        private final HikariDataSource hikari;
        private final long timeoutMs;

        BorrowTimeoutDataSource(HikariDataSource hikari, long timeoutMs) {
            super(hikari);
            this.hikari = hikari;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public Connection getConnection() throws SQLException {
            // 풀이 아직 없으면(최초 호출) 원래 경로로 풀을 만든다
            if (hikari.getHikariPoolMXBean() instanceof HikariPool pool) {
                return pool.getConnection(timeoutMs);
            }
            return hikari.getConnection();
        }
    }
}
