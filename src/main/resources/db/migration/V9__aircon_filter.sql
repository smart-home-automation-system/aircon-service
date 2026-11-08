-- The filter of every unit (HAS-391): when it was last cleaned (null before the first cleaning is recorded - the hours
-- are then counted from the start of the history of the unit) and the number of hours of work the last reminder was
-- sent for (200, 250, 300 ... - null until the first one, reset by a cleaning), so that a restart never repeats one. The
-- hours themselves are read from aircon_state_change: the time the unit was on since the cleaning. A unit is the id of
-- its device in the registry - no foreign key, the registry is another database
CREATE TABLE aircon_filter
(
    unit_id        BIGINT PRIMARY KEY,
    cleaned_at     TIMESTAMP,
    reminded_hours DOUBLE PRECISION
);
