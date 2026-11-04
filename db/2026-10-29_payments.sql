-- 기존 MySQL 스키마에 한 번 수동 적용한다. 테스트·loadtest의 새 스키마에는 필요 없다.
-- 컬럼 타입은 PaymentEntity를 Hibernate가 MySQL에 생성할 때와 맞춘다.
CREATE TABLE payments (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    order_id             VARCHAR(100) NOT NULL,
    user_id              BIGINT       NOT NULL,
    seat_id              BIGINT       NOT NULL,
    reservation_id       BIGINT       NULL,
    amount               BIGINT       NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    status               ENUM('READY', 'PROCESSING', 'SUCCESS', 'FAILED', 'CANCELED', 'REFUND_PENDING', 'NEEDS_REVIEW', 'CANCEL_PENDING') NOT NULL,
    provider             VARCHAR(30)  NOT NULL,
    provider_payment_key VARCHAR(200) NULL,
    failure_code         VARCHAR(100) NULL,
    failure_message      VARCHAR(500) NULL,
    requested_at         DATETIME(6)  NOT NULL,
    processing_started_at DATETIME(6) NULL,
    approved_at          DATETIME(6)  NULL,
    failed_at            DATETIME(6)  NULL,
    canceled_at          DATETIME(6)  NULL,
    -- 결제 복구 작업의 재시도 횟수와 다음 시도 시각
    recovery_attempts    INT          NOT NULL DEFAULT 0,
    next_recovery_at     DATETIME(6)  NULL,
    version              BIGINT       NOT NULL,
    PRIMARY KEY (id),
    -- 복구 작업이 오래된 PROCESSING을 찾는 쿼리용
    INDEX idx_payment_status_processing_started_at (status, processing_started_at),
    CONSTRAINT uk_payment_order_id UNIQUE (order_id),
    CONSTRAINT uk_payment_provider_key UNIQUE (provider_payment_key),
    -- 예약 하나에 성공 결제는 하나만 연결된다 (@OneToOne). NULL은 중복을 허용한다.
    CONSTRAINT uk_payment_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_payment_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_payment_seat FOREIGN KEY (seat_id) REFERENCES seat_entity (id),
    CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservation_entity (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
