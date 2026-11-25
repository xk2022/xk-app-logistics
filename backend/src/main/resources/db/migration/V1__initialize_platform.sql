-- M1 establishes Flyway history only. Business tables require a later reviewed migration.
-- An executable no-op records the baseline without introducing business data or entities.
SELECT 1;
