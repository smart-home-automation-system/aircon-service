-- The draw of the plugs that feed the units (HAS-390): every reading of a plug, every 30 s while it answers - the power
-- in W, the voltage, the current, the counter of its meter in Wh (aenergy.total) and, for a plug with a relay, whether
-- it is on. Kept 30 days
CREATE TABLE aircon_power_sample
(
    id         BIGSERIAL PRIMARY KEY,
    plug       VARCHAR(20)      NOT NULL
        CONSTRAINT aircon_power_sample_plug CHECK (plug IN ('indoor', 'sanctum')),
    sampled_at TIMESTAMP        NOT NULL,
    power      DOUBLE PRECISION NOT NULL,
    voltage    DOUBLE PRECISION,
    current    DOUBLE PRECISION,
    total_wh   DOUBLE PRECISION,
    output     BOOLEAN
);

CREATE INDEX aircon_power_sample_plug_at ON aircon_power_sample (plug, sampled_at);

-- The energy of a plug per day of the house, closed from 00:15 of the next day: the rises of its counter between the
-- samples of the day (the one before the day included), a fall of the counter - a reset - never counted. Kept 730 days
CREATE TABLE aircon_power_day
(
    plug   VARCHAR(20)      NOT NULL
        CONSTRAINT aircon_power_day_plug CHECK (plug IN ('indoor', 'sanctum')),
    day    DATE             NOT NULL,
    kwh    DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (plug, day)
);

-- The power cycles of the indoor plug (HAS-390), run by hand: when it started and ended, who asked (the member the
-- dashboard names, null when nobody was named) and what it came to - running while the plug is off, done once it is on
-- again, failed when the plug could not be switched off, or not back on (an alert). A row still running at a start is
-- a cycle a restart cut off: the plug is switched on and the row failed. Kept 365 days
CREATE TABLE aircon_power_cycle
(
    id           BIGSERIAL PRIMARY KEY,
    plug         VARCHAR(20) NOT NULL
        CONSTRAINT aircon_power_cycle_plug CHECK (plug IN ('indoor', 'sanctum')),
    started_at   TIMESTAMP   NOT NULL,
    finished_at  TIMESTAMP,
    requested_by VARCHAR(50),
    result       VARCHAR(10) NOT NULL
        CONSTRAINT aircon_power_cycle_result CHECK (result IN ('running', 'done', 'failed'))
);

CREATE INDEX aircon_power_cycle_started_at ON aircon_power_cycle (started_at);
