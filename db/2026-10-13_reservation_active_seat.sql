-- 취소된 좌석 재예약 허용: 좌석당 유니크를 seat_id → active_seat_id(RESERVED일 때만 값 있음)로 옮긴다
-- ddl-auto를 쓰지 않는 기존 DB에 한 번 실행한다. 테스트(create-drop)와 loadtest(create)는 엔티티로 스키마가 만들어지므로 필요 없다

-- 1. 컬럼 추가 (canceled_at이 이미 있으면 그 줄은 빼고 실행)
ALTER TABLE reservation_entity ADD COLUMN canceled_at DATETIME(6) NULL;
ALTER TABLE reservation_entity ADD COLUMN active_seat_id BIGINT NULL;

-- 2. 기존 확정 예약 채우기
UPDATE reservation_entity SET active_seat_id = seat_id WHERE status = 'RESERVED';

-- 3. 유니크 제약 교체
-- seat_id FK가 uk_reservation_seat 인덱스를 쓰고 있으면 DROP이 거부되므로 일반 인덱스를 먼저 만든다
CREATE INDEX idx_reservation_seat ON reservation_entity (seat_id);
ALTER TABLE reservation_entity DROP INDEX uk_reservation_seat;
ALTER TABLE reservation_entity ADD CONSTRAINT uk_reservation_active_seat UNIQUE (active_seat_id);
