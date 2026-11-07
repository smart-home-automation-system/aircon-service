-- The stops of the units by an open window (HAS-384): a row for every unit the window of its room switched off, with
-- what it was doing then, so that the closed window sets it back as it was - kept here, not in memory, so that a restart
-- neither forgets to set a unit back nor stops it twice for one opening. unit_id is the registry id; window_open_since
-- the opening that held it (openSince of heating-service); held_since when the stop was decided; the state before in
-- the words of aircon_state. closed_at and outcome together once the stop ended: restored, expired (the window closed
-- after aircon.window.restore-within - the unit stays off), cancelled (the remote or the dashboard changed the unit while
-- it was held) or refused (the rules of the commands refuse what it was doing)
CREATE TABLE aircon_window_hold
(
    id                 BIGSERIAL PRIMARY KEY,
    unit_id            BIGINT    NOT NULL,
    window_open_since  TIMESTAMP,
    held_since         TIMESTAMP NOT NULL,
    mode               VARCHAR(10),
    target_temperature INTEGER,
    fan_speed          VARCHAR(12),
    swing_vertical     INTEGER,
    swing_horizontal   INTEGER,
    quiet              BOOLEAN,
    turbo              BOOLEAN,
    sleep              BOOLEAN,
    closed_at          TIMESTAMP,
    outcome            VARCHAR(10)
        CONSTRAINT aircon_window_hold_outcome CHECK (outcome IN ('restored', 'expired', 'cancelled', 'refused')),
    CONSTRAINT aircon_window_hold_closed CHECK ((closed_at IS NULL) = (outcome IS NULL))
);

-- one open stop per unit at most
CREATE UNIQUE INDEX aircon_window_hold_open ON aircon_window_hold (unit_id) WHERE closed_at IS NULL;

CREATE INDEX aircon_window_hold_unit ON aircon_window_hold (unit_id, id);
