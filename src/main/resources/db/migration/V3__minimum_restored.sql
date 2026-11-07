-- The guard of the minimum in cooling (HAS-382): a target below 22 C set with the remote or the Gree app is written back to
-- 22 C, and the change the next read shows is written as minimum-restored - a sixth sender beside those of V2
ALTER TABLE aircon_state_change
    DROP CONSTRAINT aircon_state_change_source;

ALTER TABLE aircon_state_change
    ADD CONSTRAINT aircon_state_change_source
        CHECK (source IN ('first-read', 'remote', 'dashboard', 'rule', 'window', 'empty-house', 'grid-outage',
                          'minimum-restored'));
