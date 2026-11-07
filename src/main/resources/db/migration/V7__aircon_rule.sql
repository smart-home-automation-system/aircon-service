-- The rule of a unit (HAS-386), one row a unit: keep its room below a temperature - on or off, the threshold and the
-- hysteresis in degrees C by 0.5, the target it cools at in whole degrees - and heat by the schedules of heating-service
-- (the switch of the next task, HAS-387). Written by the administrator through the API, never seeded; the bounds are
-- those of RuleRules, repeated here so that no row can hold what the engine never decides by
CREATE TABLE aircon_rule
(
    unit_id            BIGINT PRIMARY KEY,
    keep_below_enabled BOOLEAN      NOT NULL,
    threshold          NUMERIC(3, 1) NOT NULL
        CONSTRAINT aircon_rule_threshold CHECK (threshold BETWEEN 20 AND 30 AND threshold * 2 = TRUNC(threshold * 2)),
    hysteresis         NUMERIC(2, 1) NOT NULL
        CONSTRAINT aircon_rule_hysteresis CHECK (hysteresis BETWEEN 0.5 AND 3 AND hysteresis * 2 = TRUNC(hysteresis * 2)),
    target             INTEGER      NOT NULL
        CONSTRAINT aircon_rule_target CHECK (target BETWEEN 22 AND 30),
    heat_by_schedule   BOOLEAN      NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    CONSTRAINT aircon_rule_target_below CHECK (target <= threshold)
);
