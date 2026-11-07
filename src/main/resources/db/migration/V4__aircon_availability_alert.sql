-- The open alerts of the availability monitor (HAS-383), one row per unit the household was told about and not yet told
-- is back - kept here, not in memory, so that a restart neither repeats an alert nor forgets to announce a return (the
-- shape of light_availability_alert of light-service). unit_id is the registry id; since when it does not answer,
-- last_alert_at the alert or the last reminder
CREATE TABLE aircon_availability_alert
(
    unit_id       BIGINT PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    room          VARCHAR(50)  NOT NULL,
    since         TIMESTAMP    NOT NULL,
    last_alert_at TIMESTAMP    NOT NULL
);
