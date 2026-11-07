-- The state of the air conditioners (HAS-381): what every unit reported at its last read, and the history of what was
-- changed on it. A unit is the id of its device in the registry of database-service - no foreign key, the registry is
-- another database. A value the unit did not report is NULL; mode and fan_speed are the words of the API (cool, auto,
-- medium-low ...), the temperatures whole degrees C as the unit gives them.

-- The current row of a unit: inserted at its first read, its values and changed_at moved only when one of them changed,
-- last_checked_at with every read that answered - a status table never grows with the polling. Restored at a start (the
-- unit then not known to be reachable until its first read)
CREATE TABLE aircon_state
(
    unit_id            BIGINT PRIMARY KEY,
    power              BOOLEAN,
    mode               VARCHAR(10),
    target_temperature INTEGER,
    room_temperature   INTEGER,
    fan_speed          VARCHAR(12),
    swing_vertical     INTEGER,
    swing_horizontal   INTEGER,
    quiet              BOOLEAN,
    turbo              BOOLEAN,
    sleep              BOOLEAN,
    display            BOOLEAN,
    changed_at         TIMESTAMP NOT NULL,
    last_checked_at    TIMESTAMP NOT NULL
);

-- A change of what a unit does - power, mode, target, fan - and who made it: the first read of a unit, the remote (any
-- change the service did not send), and the senders of the tasks to come (the dashboard, a rule, an open window, the
-- empty house, a lost grid). The run hours and the history of a unit are read from here
CREATE TABLE aircon_state_change
(
    id                 BIGSERIAL PRIMARY KEY,
    unit_id            BIGINT      NOT NULL,
    changed_at         TIMESTAMP   NOT NULL,
    power              BOOLEAN,
    mode               VARCHAR(10),
    target_temperature INTEGER,
    fan_speed          VARCHAR(12),
    source             VARCHAR(20) NOT NULL
        CONSTRAINT aircon_state_change_source
            CHECK (source IN ('first-read', 'remote', 'dashboard', 'rule', 'window', 'empty-house', 'grid-outage'))
);

CREATE INDEX aircon_state_change_unit_changed_at ON aircon_state_change (unit_id, changed_at);
