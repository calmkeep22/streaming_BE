-- Run with an administrator before deploying seat-grade-pricing code.
-- Additive defaults keep the previous application and existing rows compatible.
-- Each column is checked separately so a partially completed migration can be retried.
USE streaming;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'seat_entity' AND column_name = 'grade'),
    'SELECT 1',
    'ALTER TABLE seat_entity ADD COLUMN grade ENUM(''FREE'', ''STANDARD'', ''PREMIUM'', ''VIP'') NOT NULL DEFAULT ''STANDARD''');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'seat_entity' AND column_name = 'price'),
    'SELECT 1',
    'ALTER TABLE seat_entity ADD COLUMN price BIGINT NOT NULL DEFAULT 50000');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'payments' AND column_name = 'seat_grade'),
    'SELECT 1',
    'ALTER TABLE payments ADD COLUMN seat_grade ENUM(''FREE'', ''STANDARD'', ''PREMIUM'', ''VIP'') NOT NULL DEFAULT ''STANDARD''');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
