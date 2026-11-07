# aircon-service

The air conditioners of the house (epic HAS-376): five Gree indoor units over their local protocol -
state, commands and the rules of the house (open windows, 22 °C minimum in cooling, sanctum below
25 °C, the empty house, a lost grid, run hours). Scaffolded by HAS-380 with the units of the
registry and the driver; the features come with HAS-381 onwards.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review - the owner had Claude implement the scaffold and the driver (HAS-380) and reviews it in
IntelliJ.

## Role in the system

- Is called by: the dashboard through `api-gateway-service` (`/home/aircon`, `/home/aircon/**`,
  every method - gateway 0.11.0, HAS-379).
- Calls: `database-service` for the registry (`GET /home/device/registry?kind=air conditioner&enabled=true`
  every 5 min, k8s DNS) and the units themselves (UDP 7000). Planned: heating-service (the windows),
  presence-service (the empty house), energy-service (a lost grid), notification-service.
- Uses libraries: `cholewa-commons` 1.13.0 (the pooled `ConnectionFactory`, the required
  properties, the scheduler pool and `ScheduledPass`, the error contract), `smart-home-sdk` 1.18.0
  (`DeviceKind.AIR_CONDITIONER`, `GreeBinding`), `shelly-client` 2.6.0 **with its beans on**
  (for the plugs that feed the units, HAS-390; pinned by `AirconServiceApplicationTest`).
- Database: own database `home-automation-aircon`, pool 2 - with it 22 of the 47 connections of the
  managed database are allotted to this project (25 with ristretto).

## Build & run

- Build + tests: `mvn verify` - no Docker needed until the first test of PostgreSQL-only SQL.
- Local run: `home,local` Spring profiles, port `6015` (Actuator `8015`); in-cluster port `6200`,
  Actuator `8200`. Needs `database-password` and the other `database-*` values. **The real values
  are the production database** - a migration is rehearsed on a throwaway PostgreSQL, never by a
  local run. A local run also binds the real units the registry names.
- Spring Boot 4.1.2, logbook 4.2.0; own libraries on the latest releases, as the org rule asks of
  every service task.

## The Gree protocol - the facts the driver is built on

The probe of 2026-11-04 (PowerShell from the owner's PC, read-only: scan, bind, status) and the
driver itself on 2026-11-07 (`GreeClient` from the owner's PC, the same reads) on all five units,
firmware `362001065279+U-WB05RT13V1.45.bin`, `ver V3.4.M`, mid 10001:

- **Unicast UDP 7000 to the address of the registry.** A broadcast from the cluster (VLAN 20) does
  not reach the units (VLAN 30); the five addresses are DHCP reservations since 2026-11-04.
- **The scan is answered with V1** (AES-128-ECB, PKCS7, generic key `a3K8Bx%2r8Y7#xDh`) - used only
  to confirm that the address answers with the MAC of the registry; a unit that answers with another
  MAC is never bound nor commanded.
- **Bind, status and cmd work only with V2** (AES-128-GCM, generic key `{yxAHAY_Lm6pbC/<`, nonce
  `54 40 78 44 49 67 5a 51 6c 5e 63 13`, AAD `qualcomm-test`, the base64 pack and tag in the
  envelope `{cid:"app", i, pack, t:"pack", tcid:<mac>, uid:0, tag}`; `i` is 1 for the bind, 0 for
  the rest). A V1 bind is never answered. The keys are published constants, not secrets.
- **The key of a unit** (16 ASCII characters of its `bindok`) is kept in memory only: bound by the
  registry pass right after the read, bound again when an answer does not open with it and after
  `rebind-after` (3) failed requests in a row; never stored, never logged, never in a message
  (`GreeReply.toString` leaves it out, `GreeClientTest` checks every log line).
- **Wi-Fi loses datagrams** (retransmissions of 11-62 %): every request waits `timeout` (3 s) and
  is sent again up to `retries` (2) times, each attempt from a new socket - a late answer to an
  attempt given up on reaches a closed port. One request at a time per address.
- **Status columns**: `Pow`, `Mod` (0 auto, 1 cool, 2 dry, 3 fan, 4 heat), `SetTem`, `TemUn` (0 °C),
  `WdSpd` (0 auto, 1-5), `SwUpDn` (0-11), `SwingLfRig` (0-6), `Quiet` (2 on some firmware - read as
  on), `Tur`, `SwhSlp`, `Lig`, `TemSen` (the room temperature + 40; 0 is no sensor), `hid` (the
  firmware). The answer `{t:"dat", cols, dat}` comes by index; a value that is not the model fails
  the whole answer. A command is `{opt, p, t:"cmd"}`, answered `{t:"res", opt, val, r}`.
- **Tests never use recorded packets**: a `bindok` carries the key of the unit under a public key,
  so a capture of a real unit would publish its key. `FakeGreeUnit` speaks the protocol with a
  made-up MAC and key; the cipher vectors were sealed by Node.js, another implementation than the JDK.

## Specifics

- **One instance, never scaled**: the keys of the units live in memory, and the state and the rules
  will too, so the Deployment is `Recreate`.
- **`GET /home/aircon` reads every unit live** (HAS-380) - all at once, answered in the order of the
  registry; a unit not answered within 25 s is `reachable: false`, so the read stays below the 30 s
  of the gateway. The state tracking of HAS-381 replaces the live read.
- **The socket API blocks**: `GreeTransport` runs every exchange on `boundedElastic`, never on an
  event loop; the lock per address is taken there too.
- **`database-password` has no default**: `cholewa.required-properties` refuses the start before the
  first bean. It is the only secret of the service so far.
- **`V1` is an empty baseline** (only a comment): Flyway applies it and writes its history table,
  so the start has no `No migrations found` WARN and proves the grant.
- **Scheduling is off under `test`** (`SchedulingConfig`, `@Profile("!test")`): the registry pass
  is wrapped in `ScheduledPass.bounded` (2 min, below its 5 min delay); the scheduler has the pool
  of 10 of the library.
- **No broker yet**: the notifications come with the availability monitor (HAS-383), with their
  password in `cholewa.required-properties`.
- **Tests use `mockwebserver3`** for the client of database-service, never the legacy
  `mockwebserver` (JUnit 4).
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
