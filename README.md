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
monitor on the technical channel of Discord (HAS-383).
What comes next:

| Task | What |
|---|---|
| HAS-384 to HAS-391 | the open windows, the empty house and a lost grid, the rules per unit, the power of the plugs, the run hours |

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
- Build: `mvn verify` (JDK 21) - **needs a running Docker**: `AirconStateRepositoryTest` runs the SQL of the
  state against a PostgreSQL of Testcontainers. The driver is tested against a fake unit on a UDP port of the
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
  finds - a read, but a real one, of the units of the house.

# API

Under the base path `/home/aircon`, routed by the gateway by path, every method (gateway 0.11.0).

| Method | Path | Answer |
|---|---|---|
| `GET` | `/home/aircon` | every air conditioner of the registry (the kind `air conditioner`, switched on) in its order, with its state as last read (every 30 s): `id`, `name`, `room`, `power`, `mode` (`auto`, `cool`, `dry`, `fan`, `heat`), `targetTemperature` and `roomTemperature` in °C, `fanSpeed` (`auto`, `low`, `medium-low`, `medium`, `medium-high`, `high`), `swingVertical` / `swingHorizontal` (the position codes of the unit), `quiet`, `turbo`, `sleep`, `display`, `reachable` (whether the last read answered), `unreachableSince` (since when it does not answer - HAS-383) and `updatedAt` (house time of the last read that answered). **A field not known is left out** - a unit not read yet answers `id`, `name` and `room` alone, `reachable` is left out after a start until the first read; a unit that stopped answering keeps what it reported, with `reachable: false`. `503 REGISTRY_UNAVAILABLE` until the registry was read once |
| `GET` | `/home/aircon/{id}` | one unit, the same fields; `404 NOT_FOUND_AIRCON` for an id no air conditioner of the registry has |
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

# The availability monitor

Every minute (HAS-383): a unit that has not answered for `aircon.monitor.offline-after` (5 min, by whole passes of the
tracker) is **one alert a pass naming every unit out** on the technical channel of Discord (`alert`, no SMS), a reminder
every `reminder-every` (1 h) while out, and an info when it answers again. After a restart a unit is not back until a
read answered. A notification that fails changes nothing and is due again at the next pass.
