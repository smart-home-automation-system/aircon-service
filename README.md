# aircon-service

[![CI](https://github.com/smart-home-automation-system/aircon-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/aircon-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_aircon-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_aircon-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_aircon-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_aircon-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/aircon-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/aircon-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/aircon-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.2-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_aircon-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_aircon-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_aircon-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_aircon-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/aircon-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/aircon-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/aircon-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/aircon-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/aircon-service?style=plastic)

---


# Description

The air conditioners of the house (epic HAS-376): five Gree indoor units, driven over their local
protocol on the home network - no Gree cloud. The service will track their state, take the
commands of the dashboard and keep the rules of the house (open windows, a minimum temperature in
cooling, the server room, the empty house, a lost grid).

**Today**: the units of the device registry (the five of the house since HAS-393), the driver of the Gree
protocol (HAS-380), the state of every unit, read every 30 s and stored (HAS-381), served by three reads, and the
commands with the minimum of 22 °C in cooling - in the API and against the remote (HAS-382), and the availability
monitor on the technical channel of Discord (HAS-383), and the open windows: a window open in the room stops the unit,
the closed window sets it back (HAS-384), the empty house and a lost grid switch the units off (HAS-385), and a rule per unit keeps its room below a
temperature (HAS-386) and heats it by the heating schedule of the room (HAS-387).
What comes next:

| Task | What |
|---|---|
| HAS-389 to HAS-391 | the power of the plugs, the run hours |

# The Gree protocol

JSON over UDP, port 7000, **unicast to the address of the registry** (a broadcast from the
cluster does not reach the VLAN of the units). Verified on all five units on 2026-11-04 and
again through this driver on 2026-11-07 (firmware `362001065279+U-WB05RT13V1.45.bin`):

- `{"t":"scan"}` is answered with the **V1** cipher (AES-128-ECB, PKCS7, a generic key) - used
  only to confirm that the address answers with the MAC of the registry;
- the **bind**, the **status** and the **commands** work only with **V2** (AES-128-GCM, a fixed
  nonce, the additional data `qualcomm-test`, the tag in the envelope): the bind under the
  generic key of V2, everything after it under the key of the unit from its `bindok`.

The generic keys are the published constants of the open-source clients, not secrets. **The key
of a unit lives in memory only** - bound at the start, bound again when an answer no longer opens
with it and after three failed requests in a row; never stored, never logged. Every request
waits 3 s for its answer and is sent again up to twice (`aircon.gree.*`).

# Run locally

- **The broker**: one connection, `/notification` (user `notification`) - the alerts of the availability monitor
  through `NotificationPublisher` of `cholewa-commons`. **`rabbitmq-password` is mandatory** (`cholewa.required-properties`);
  a local run publishes into the dev queue (`cholewa.notification.env: dev`).
- Build: `mvn verify` (JDK 21) - **needs a running Docker**: `AirconStateRepositoryTest`, `WindowHoldRepositoryTest` and
  `RuleRunRepositoryTest`, `RuleRepositoryTest` run their SQL against a PostgreSQL of Testcontainers. The driver is tested against a fake unit on a UDP port of the
  loopback, with a made-up MAC and key.
- Ports: local profile `6015` (management `8015`); in the deployed `home` profile the service
  listens on `6200` and Actuator on `8200` like every service in the cluster. The ingress routes
  only 6200, so Actuator is reachable inside the cluster only - where the Kubernetes probes hit
  `/actuator/health/{readiness,liveness}` and Prometheus scrapes `/actuator/prometheus`.
- **The database**: own database `home-automation-aircon`, `database.pool.max-size: 2` - this
  service's share of the 47 connections of the managed database (22 allotted with it). The
  connection properties (`database-host`, `database-port`, `database-name`, `database-user`,
  `database-password`) are bound to the `database.*` prefix of `cholewa-commons`; Flyway derives
  its JDBC URL from them (`--flyway-url=...` overrides). **`database-password` is mandatory**: the
  service refuses to start without it (`cholewa.required-properties`). Flyway runs in `home` /
  `local` and is off under `test`. **A local run with the real values is the production database.**
- A local run reads the registry of `database-service` on `localhost:6005` and binds the units it
  finds - a read, but a real one, of the units of the house; it reads the windows from `heating-service` on
  `localhost:6002`, who is at home from `presence-service` on `localhost:6009` and the grid from `energy-service` on
  `localhost:6014`, and **switches the real units** when a window holds, the house is empty or the grid is lost.

# API

Under the base path `/home/aircon`, routed by the gateway by path, every method (gateway 0.11.0).

| Method | Path | Answer |
|---|---|---|
| `GET` | `/home/aircon` | every air conditioner of the registry (the kind `air conditioner`, switched on) in its order, with its state as last read (every 30 s): `id`, `name`, `room`, `power`, `mode` (`auto`, `cool`, `dry`, `fan`, `heat`), `targetTemperature` and `roomTemperature` in °C, `fanSpeed` (`auto`, `low`, `medium-low`, `medium`, `medium-high`, `high`), `swingVertical` / `swingHorizontal` (the position codes of the unit), `quiet`, `turbo`, `sleep`, `display`, `reachable` (whether the last read answered), `unreachableSince` (since when it does not answer - HAS-383), `updatedAt` (house time of the last read that answered), `lastCommandConfirmed`, `minimumRestoredAt` (HAS-382) and `window` (HAS-384: `state` - `open`, `closed`, `no sensor`, `unknown` -, `openSince` and `hold` as heating-service last gave them for the room, left out while it does not answer; `restoreUntil` while the window holds the unit stopped). **A field not known is left out** - a unit not read yet answers `id`, `name` and `room` alone, `reachable` is left out after a start until the first read; a unit that stopped answering keeps what it reported, with `reachable: false`. `503 REGISTRY_UNAVAILABLE` until the registry was read once |
| `GET` | `/home/aircon/{id}` | one unit, the same fields; `404 NOT_FOUND_AIRCON` for an id no air conditioner of the registry has |
| `GET` | `/home/aircon/automation` | the empty house and the grid as the last pass of the automation read them (HAS-385): `{"emptyHouse": {"empty", "since", "actedAt"}, "grid": {"state", "actedAt"}}` - `empty` whether nobody is at home, `since` the last member to go, `state` `present` / `lost`, `actedAt` when the units were switched off for this empty house or outage; a field not known is left out (both objects empty before the first pass) |
| `GET` | `/home/aircon/rules` | the rule of every unit that has one (HAS-386): `[{"unitId", "keepBelow": {"enabled", "threshold", "hysteresis", "target"}, "heatBySchedule": {"enabled"}, "updatedAt"}]` |
| `GET` | `/home/aircon/{id}/rules` | the rule of one unit; `404 NOT_FOUND_RULE` for a unit without one |
| `PUT` | `/home/aircon/{id}/rules` | **writes the rule of a unit whole - it acts on the house within the minute**: `{"keepBelow": {"enabled", "threshold", "hysteresis", "target"}, "heatBySchedule": {"enabled"}}` - the threshold 20 to 30 °C by 0.5, the hysteresis 0.5 to 3 by 0.5 (1 when left out), the target a whole degree 22 to 30 and not above the threshold (`RuleRules`, copied by the dashboard); `heatBySchedule` left out is off. Answers the stored rule; `400 INVALID_RULE` with the reason, `404 NOT_FOUND_AIRCON`, `503 REGISTRY_UNAVAILABLE` |
| `DELETE` | `/home/aircon/{id}/rules` | removes the rule of a unit, `204`; `404 NOT_FOUND_RULE` |
| `GET` | `/home/aircon/rooms/{room}` | the units of a room by the name of the registry (`office`, `hall up`); `404 NOT_FOUND_ROOM` for a room without a unit, or a name that is no room |
| `POST` | `/home/aircon/{id}?source=` | **a command to the unit - a write to the house**: a JSON body of optional fields in the words of the reads (`power`, `mode`, `targetTemperature`, `fanSpeed`, `swingVertical`, `swingHorizontal`, `quiet`, `turbo`, `sleep`, `display`); `source` is `dashboard` (the default), `rule`, `window`, `empty-house` or `grid-outage`. `202` once the unit took it; read back 2 s later - not taken, sent once more and read again, never a third time - and told as `lastCommandConfirmed` in the reads; the change in the history under the source. `400 INVALID_COMMAND` with the reason (the rules below, an empty command, another source), `502 DEVICE_FAILED` when the unit did not answer (it may have taken it all the same), `404 NOT_FOUND_AIRCON`, `503 REGISTRY_UNAVAILABLE` |
| `POST` | `/home/aircon/rooms/{room}?source=` | the same body for every unit of the room, one after the other; `202` with what each came to - `[{"id", "sent", "code"}]` (`code` for one not sent: `INVALID_COMMAND`, `DEVICE_FAILED`); `404 NOT_FOUND_ROOM` |

The rules of a command (`CommandRules`, copied by the dashboard): **cooling never below 22 °C**, a target of 16 to 30 °C
in whole degrees, turbo and quiet not in `dry` or `fan` - judged against the mode and the target the unit will have.
**The guard against the remote and the Gree app**: a read that sees a unit cooling below 22 °C writes 22 °C back in the
same pass (the change written as `minimum-restored`, `minimumRestoredAt` in the reads); a unit that is off is left
alone - its target cools nothing.

# Database

`home-automation-aircon`, owned by `doadmin` like every database of the cluster: the user of the
services needs `GRANT USAGE, CREATE ON SCHEMA public` before the first start, or Flyway fails with
`permission denied for schema public`.

- `V1` is an empty baseline - its first run creates the history table of Flyway, which proves the grant.
- `V2` (HAS-381): `aircon_state`, the current row of every unit read (inserted at its first read, its values
  and `changed_at` moved only when one changed, `last_checked_at` with every read that answered - restored at a
  start), and `aircon_state_change`, a row for every change of the power, the mode, the target or the fan with
  who made it (`first-read`, `remote`, and the senders of the tasks to come - checked by the table). Rehearsed on
  2026-11-07 on a throwaway PostgreSQL: V2 over the database of 0.1.0, and 0.1.0 starts over V2 (rollback safe).
- `V3` (HAS-382): `minimum-restored`, the source of the guard, added to the check of `aircon_state_change`. Rehearsed the
  same way: over the rows of 0.2.0, and 0.2.0 starts over V3.
- `V4` (HAS-383): `aircon_availability_alert`, the open alerts of the availability monitor (a unit told out and not yet
  told back), so that a restart neither repeats an alert nor forgets a return. Rehearsed the same way with 0.3.0.
- `V5` (HAS-384): `aircon_window_hold`, a row for every unit an open window stopped, with what it was doing then - the
  mode, the target, the fan, the louvres, quiet, turbo, sleep - and how it ended (`restored`, `expired`, `cancelled`,
  `refused`); one open row per unit (a partial unique index); a stop that ended is kept 90 days. Rehearsed the same way
  with 0.4.0.
- `V6` (HAS-385): `aircon_rule_run`, a row for every command the service sent on its own (the window, the empty house, a
  lost grid, the rules to come): its source, the unit, the episode it acted on, the command and what it came to (`sent`,
  `failed`, `refused`); kept 90 days; and `aircon_automation_episode`, the episode the empty house and a lost grid acted
  on last, one row a source. Rehearsed the same way with 0.5.0.
- `V7` (HAS-386): `aircon_rule`, the rule of a unit, one row a unit, its bounds repeated as checks. Rehearsed the same
  way with 0.6.0.

# The availability monitor

Every minute (HAS-383): a unit that has not answered for `aircon.monitor.offline-after` (5 min, by whole passes of the
tracker) is **one alert a pass naming every unit out** on the technical channel of Discord (`alert`, no SMS), a reminder
every `reminder-every` (1 h) while out, and an info when it answers again. After a restart a unit is not back until a
read answered. A notification that fails changes nothing and is due again at the next pass.

# The open windows

Every minute (HAS-384) the rooms of `heating-service` (`GET /home/heating/rooms`, >= 1.20.0, k8s DNS) give the window of the
room of every unit - judged there by the rule that holds the heaters: a window or door open for 3 min holds. A unit
that is on in a room whose window holds is **switched off** (source `window`), what it was doing kept first in
`aircon_window_hold`; once per opening - a unit a person switched on again with the window open is left alone.
The windows closed within `aircon.window.restore-within` (1 h) of the stop **set it back as it was** (source
`window`); later the unit stays off. A change of the remote or the dashboard while it is held gives the restore up.
A room heating-service does not know, `no sensor`, `unknown` or heating-service not answering **never stops a
unit**, and never sets one back - the stop then waits, at most until `restore-within`. A command the unit did not
take is sent again the next minute; a unit that does not answer is sent nothing until it answers again.

# The empty house and a lost grid

Every minute (HAS-385) who is at home is read from `presence-service` (`GET /home/presence/residents/presence`) and the
grid from `energy-service` (`GET /home/energy/status`, >= 0.4.0), both over k8s DNS; a read that fails or is slow is not
known, and **what is not known never acts**.

- **Nobody at home** for `aircon.empty-house.after` (10 min, counted from the last member to go): every unit cooling
  (`cool`, `dry`, `fan`, `auto`) outside `aircon.empty-house.exempt-rooms` (`sanctum`, the server room) is switched off,
  source `empty-house`. Heating is left alone.
- **The grid lost**: every unit that is on is switched off, heating and sanctum included, source `grid-outage`.
- **Once per episode** - an empty house is known by when the last member went, an outage by when the grid was lost; the
  units on when it begins are the ones it switches off, each sent the command every minute until it took it while the
  episode lasts (one that does not answer waits); a unit switched on during it is left alone. A restart does not act
  twice (the episode is stored in `aircon_automation_episode` when it begins, a unit on or not); a unit not yet taken
  at a restart is not tried again. **Nothing is switched back on**: the return is the household's - and while the house
  is empty or the grid lost, a closed window does not set a stopped unit back either (its stop ends as `expired`).

# The rules of the units

Every minute (HAS-386) the rule of every unit that has one (`aircon_rule`, written by the administrator - none is seeded,
the one of sanctum included) is judged against the temperature of its room as heating-service gave it - read with the
windows, one call a minute - **never the sensor of the unit**, which reads 2 to 7 °C low in sanctum. A reading older
than `aircon.rule.fresh-for` (3 h - the battery thermometers report on a change and at least every two hours) decides
nothing.

- **Keep below**: the room above the threshold - the unit cools at the target (`source=rule`), unless it cools already;
  below the threshold minus the hysteresis - switched off, **only a unit the rule switched on**.
- **Never against a person**: within `aircon.rule.manual-hold` (30 min) of a change made with the remote or the dashboard
  the rule leaves the unit alone; a unit a person switched on is never switched off by it.
- Not while something else holds the unit off - the grid lost, the window of its room; independent of who is at home.
- **Heat by schedule** (HAS-387): every minute the switch of the heating of the house (`GET /home/heating` of
  heating-service, read only while a rule heats by schedule) and what the schedule of the room asks for now
  (`heaters[].scheduledTemperature` of `GET /home/heating/rooms`, read with the windows; heating-service >= 1.21.0 keeps
  it for the radiator of sanctum it no longer drives). The house on and a schedule asking for 16 °C or more (rounded half
  up to the whole degree of the unit) - the unit heats at it (`source=rule`), unless it heats at it already; no schedule
  now, one below 16 °C ("do not heat" - the base level of 9 °C of sanctum) or the house off - switched off, **only a unit
  the rule switched on to heat**. The unit holds its own target, so no reading of the room is needed; `inSchedule` of
  heating-service is not read. Not known (heating-service not answering) does nothing, neither the heating nor its off.
- **Keep below wins**: while it is on and the room is above its threshold, the heating asks for nothing; the heating
  never goes above the threshold minus the hysteresis (24 °C for sanctum), so it never heats the room into the cooling.
- A unit the window stopped and set back stays the rule's: its schedule ending or the room cooled switches it off.
- Every command is logged in `aircon_rule_run`.
