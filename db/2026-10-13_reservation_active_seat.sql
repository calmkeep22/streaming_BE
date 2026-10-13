-- 기존 MySQL 스키마에 한 번 수동 적용한다. 테스트·loadtest의 새 스키마에는 필요 없다.
-- canceled_at이 이미 있으면 해당 ADD COLUMN은 제외한다.
ALTER TABLE reservation_entity ADD COLUMN canceled_at DATETIME(6) NULL;
ALTER TABLE reservation_entity ADD COLUMN active_seat_id BIGINT NULL;

-- Hibernate가 생성한 MySQL ENUM에도 취소 상태를 추가한다.
ALTER TABLE reservation_entity MODIFY COLUMN status ENUM('RESERVED', 'CANCELED') NULL;

UPDATE reservation_entity SET active_seat_id = seat_id WHERE status = 'RESERVED';

-- 새 유니크 제약을 먼저 만든 뒤 기존 제약을 제거한다.
ALTER TABLE reservation_entity ADD CONSTRAINT uk_reservation_active_seat UNIQUE (active_seat_id);
-- seat_id FK가 기존 유니크 인덱스를 사용하므로 일반 인덱스를 먼저 만든다.
CREATE INDEX idx_reservation_seat ON reservation_entity (seat_id);
ALTER TABLE reservation_entity DROP INDEX uk_reservation_seat;
