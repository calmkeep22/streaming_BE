-- Existing reservations require a successful payment; do not turn old unpaid rows into free tickets.
-- Apply before deploying free/paid admission code. Safe to retry after partial execution.
USE streaming;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'reservation_entity' AND column_name = 'admission_type'),
    'SELECT 1',
    'ALTER TABLE reservation_entity ADD COLUMN admission_type ENUM(''FREE'', ''PAID'') NOT NULL DEFAULT ''PAID''');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
