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

**Today it is HAS-380**: the scaffold, the units of the device registry and the driver of the Gree
protocol, with one endpoint - every unit with a live read of its status. What comes next:

| Task | What |
|---|---|
| HAS-381 | the state of every unit tracked, and the reads served from it |
| HAS-382 | the commands, with the minimum of 22 °C in cooling |
| HAS-383 to HAS-391 | the availability monitor, the open windows, the empty house and a lost grid, the rules per unit, the power of the plugs, the run hours |
| HAS-393 | the five units registered |

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

- Build: `mvn verify` (JDK 21) - no Docker needed until the first test of PostgreSQL-only SQL
  (Testcontainers, the dependencies are in the pom already). The driver is tested against a fake
  unit on a UDP port of the loopback, with a made-up MAC and key.
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
| `GET` | `/home/aircon` | every air conditioner of the registry (the kind `air conditioner`, switched on) in its order, each with one live read of its status: `id`, `name`, `room`, `reachable`, and `status` - `power`, `mode` (`auto`, `cool`, `dry`, `fan`, `heat`), `targetTemperature` and `roomTemperature` in °C, `fanSpeed` (`auto`, `low`, `medium low`, `medium`, `medium high`, `high`), `swingVertical` / `swingHorizontal` (the position codes of the unit), `quiet`, `turbo`, `sleep`, `light`, `firmware`. A field the unit did not report is `null`; a unit that did not answer is `reachable: false` with `status: null`. `503 REGISTRY_UNAVAILABLE` until the registry was read once |

# Database

`home-automation-aircon`, owned by `doadmin` like every database of the cluster: the user of the
services needs `GRANT USAGE, CREATE ON SCHEMA public` before the first start, or Flyway fails with
`permission denied for schema public`.

- `V1` is an empty baseline - the tables come with the tasks that store something. Its first run
  creates the history table of Flyway, which proves the grant.
