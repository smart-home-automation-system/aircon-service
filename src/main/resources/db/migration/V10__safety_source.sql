-- safety-service switching the units off at a fire alarm (HAS-467): one more sender of the history beside those of V2
-- and V3, and an automatic command of aircon_rule_run beside those of V6
ALTER TABLE aircon_state_change
    DROP CONSTRAINT aircon_state_change_source;

ALTER TABLE aircon_state_change
    ADD CONSTRAINT aircon_state_change_source
        CHECK (source IN ('first-read', 'remote', 'dashboard', 'rule', 'window', 'empty-house', 'grid-outage',
                          'minimum-restored', 'safety'));

ALTER TABLE aircon_rule_run
    DROP CONSTRAINT aircon_rule_run_source;

ALTER TABLE aircon_rule_run
    ADD CONSTRAINT aircon_rule_run_source
        CHECK (source IN ('rule', 'window', 'empty-house', 'grid-outage', 'safety'));
