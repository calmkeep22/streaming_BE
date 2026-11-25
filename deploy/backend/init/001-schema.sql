-- Apply once to an empty managed MySQL database before the first deployment.
-- Schema changes after this require explicit migrations; never use ddl-auto=create.
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role ENUM('ADMIN', 'USER') NOT NULL,
    CONSTRAINT uk_user_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE event_entity (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    start_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE seat_entity (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT,
    seat_number INT,
    grade ENUM('FREE', 'STANDARD', 'PREMIUM', 'VIP') NOT NULL DEFAULT 'STANDARD',
    price BIGINT NOT NULL DEFAULT 50000,
    version BIGINT,
    CONSTRAINT fk_seat_event FOREIGN KEY (event_id) REFERENCES event_entity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_entity (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    seat_id BIGINT,
    status ENUM('RESERVED', 'CANCELED'),
    admission_type ENUM('FREE', 'PAID') NOT NULL DEFAULT 'PAID',
    active_seat_id BIGINT,
    canceled_at DATETIME(6),
    CONSTRAINT uk_reservation_active_seat UNIQUE (active_seat_id),
    CONSTRAINT fk_reservation_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_reservation_seat FOREIGN KEY (seat_id) REFERENCES seat_entity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    reservation_id BIGINT,
    amount BIGINT NOT NULL,
    seat_grade ENUM('FREE', 'STANDARD', 'PREMIUM', 'VIP') NOT NULL DEFAULT 'STANDARD',
    currency VARCHAR(3) NOT NULL,
    status ENUM('READY', 'PROCESSING', 'SUCCESS', 'FAILED', 'CANCELED',
                'REFUND_PENDING', 'NEEDS_REVIEW', 'CANCEL_PENDING') NOT NULL,
    provider VARCHAR(30) NOT NULL,
    provider_payment_key VARCHAR(200),
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    requested_at DATETIME(6) NOT NULL,
    processing_started_at DATETIME(6),
    approved_at DATETIME(6),
    failed_at DATETIME(6),
    canceled_at DATETIME(6),
    recovery_attempts INT NOT NULL DEFAULT 0,
    next_recovery_at DATETIME(6),
    version BIGINT,
    CONSTRAINT uk_payment_order_id UNIQUE (order_id),
    CONSTRAINT uk_payment_provider_key UNIQUE (provider_payment_key),
    CONSTRAINT uk_payment_reservation UNIQUE (reservation_id),
    INDEX idx_payment_status_processing_started_at (status, processing_started_at),
    CONSTRAINT fk_payment_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_payment_seat FOREIGN KEY (seat_id) REFERENCES seat_entity(id),
    CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservation_entity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
