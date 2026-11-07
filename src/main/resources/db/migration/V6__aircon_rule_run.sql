-- Every automatic command (HAS-385): a row for each command the service sent on its own - the open window, the empty
-- house, a lost grid, and the rules of the tasks to come - with its source, the unit, the episode it acted on (when the
-- house emptied, when the grid was lost, when the window stopped the unit; null when not known), the command in the
-- words of the wire, and what it came to: sent (the unit took the bytes), failed (no answer - it may have taken it) or
-- refused (the rules of the commands refuse it). Kept 90 days
CREATE TABLE aircon_rule_run
(
    id      BIGSERIAL PRIMARY KEY,
    run_at  TIMESTAMP    NOT NULL,
    source  VARCHAR(20)  NOT NULL
        CONSTRAINT aircon_rule_run_source CHECK (source IN ('rule', 'window', 'empty-house', 'grid-outage')),
    unit_id BIGINT       NOT NULL,
    episode TIMESTAMP,
    command VARCHAR(200) NOT NULL,
    result  VARCHAR(10)  NOT NULL
        CONSTRAINT aircon_rule_run_result CHECK (result IN ('sent', 'failed', 'refused'))
);

CREATE INDEX aircon_rule_run_run_at ON aircon_rule_run (run_at);

-- The episode the empty house and a lost grid acted on last (HAS-385), one row a source: its key (when the last member
-- went, when the grid was lost; null when not known) and when it acted - written when an episode begins, whether a unit
-- was on or not, and read at a start, so that a restart never acts twice on one episode
CREATE TABLE aircon_automation_episode
(
    source   VARCHAR(20) PRIMARY KEY
        CONSTRAINT aircon_automation_episode_source CHECK (source IN ('empty-house', 'grid-outage')),
    episode  TIMESTAMP,
    acted_at TIMESTAMP NOT NULL
);
