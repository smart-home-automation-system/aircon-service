# aircon-service

The air conditioners of the house (epic HAS-376): five Gree indoor units over their local protocol -
state, commands and the rules of the house (open windows, 22 °C minimum in cooling, sanctum below
25 °C, the empty house, a lost grid, run hours). Scaffolded by HAS-380 with the units of the
registry and the driver; the state of every unit since HAS-381; the commands and the guard of 22 °C since HAS-382;
the availability monitor since HAS-383; the features come with HAS-384 onwards.

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

- Build + tests: `mvn verify` - **needs a running Docker** since HAS-381 (`AirconStateRepositoryTest`,
  Testcontainers PostgreSQL).
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
- **The state of every unit** (HAS-381, `state/`): `StateTracker` reads every unit of the registry every 30 s,
  one after the other, the one read longest ago first (a pass is bounded at 25 s - an unreachable unit costs
  up to 9 s - and a cut pass reads the others first the next time). A read that answered is the state in memory
  (`StateStore`), the current row of `aircon_state` and, for a change of the power, the mode, the target or the
  fan, a row of `aircon_state_change` - `remote` while the service sends nothing, `first-read` for the first
  read of a unit (a word of its own, not in the list of the task: the start of the history of the run hours of
  HAS-391). A read that did not answer keeps what the unit reported, `reachable: false`; a WARN when a unit
  stops answering, an INFO when it answers again, never one a pass. At the start, before the first read, the
  rows of `aircon_state` are the state, `reachable` not known. A failed write is a WARN, the memory holds.
  **The reads of the API answer the state in memory** - nothing reads a unit for a request; a field not known is
  left out (`@JsonInclude(NON_NULL)` of `AirconReply`), never a default.
- **The commands** (HAS-382, `command/`): `CommandRules` is **a pure class the dashboard copies** - 16-30 °C, cooling
  never below 22 °C, turbo and quiet not in dry or fan, judged against the mode and the target the unit will have (what
  the command sets, else what it reported); a rule changed there is changed in the dashboard. `CommandService` sends a
  command once (the driver's own retries aside; `DEVICE_FAILED` when it got no answer - it may have taken it), answers
  `202`, then reads the unit `confirm-after` (2 s) later on its own (`StateTracker.readNow`, the subscription logs what
  it meets): taken - `lastCommandConfirmed: true`; not taken - sent once more and read again, never a third time before
  the next command. **The change a read shows within `expect-for` (60 s) of a command is the sender's**
  (`CommandOutcomes.expect` / `takePending`), so the history never writes a command of the dashboard as the remote's.
  The room command sends to every unit of the room one after the other and answers what each came to.
- **The guard of 22 °C** (HAS-382): a read that sees a unit **on**, cooling below 22 °C (the remote and the Gree app
  can set 16 °C) writes `SetTem` 22 back at once, in the same pass; the change the next read shows is written as
  `minimum-restored` (`V3`), `minimumRestoredAt` in the reads. A unit that is off is left alone (the remote cannot set
  its target then, and it cools nothing). The first real command through the driver was the owner's power-off of the
  office on 2026-11-07 (`Pow` 0, taken at once).
- **The words of the API** (HAS-381, the task): `fanSpeed` `medium-low` / `medium-high`, and `display` for the
  light of the panel (`Lig`) - 0.1.0 said `medium low` and `light`, read by nobody. The firmware is read but
  neither stored nor answered.
- **The socket API blocks**: `GreeTransport` runs every exchange on `boundedElastic`, never on an
  event loop; the lock per address is taken there too.
- **`database-password` and `rabbitmq-password` have no default**: `cholewa.required-properties` refuses the start
  before the first bean.
- **`V1` is an empty baseline** (only a comment): Flyway applies it and writes its history table,
  so the start has no `No migrations found` WARN and proves the grant. **`V2`** (HAS-381) creates the two
  tables of the state; rehearsed with the released 0.1.0 image on a throwaway PostgreSQL both ways (0.1.0 starts
  over V2 - Flyway ignores a future migration). **`V3`** (HAS-382) adds `minimum-restored` to the check of the history;
  rehearsed the same way (0.2.0 starts over V3). **`V4`** (HAS-383) creates `aircon_availability_alert`; rehearsed
  the same way with 0.3.0.
- **Scheduling is off under `test`** (`SchedulingConfig`, `@Profile("!test")`): the registry pass
  is wrapped in `ScheduledPass.bounded` (2 min, below its 5 min delay), the state pass too (25 s, below its 30 s);
  the scheduler has the pool
  of 10 of the library.
- **The broker** (HAS-383): one connection to `/notification` (user `notification`, `rabbitmq-password` in
  `cholewa.required-properties`), the publisher of `cholewa-commons` on `spring.rabbitmq.*` - the shape of
  appliance-service. The task named the seventh copy of `NotificationRabbitConfig`, but that class is a *second*
  connection beside a main one, and this service has no other: no copy was made.
- **The availability monitor** (HAS-383, `monitor/`): `AvailabilityEvaluator` (pure) judges every minute by whole
  passes - `unreachableSince` of `UnitState`, the first read of the run that did not answer - against
  `aircon_availability_alert` (`V4`): out after `offline-after` (5 min) is one alert a pass naming every unit, a
  reminder every `reminder-every` (1 h), an info when a unit answers again; a unit not known after a restart is never
  back, one removed from the registry is forgotten without a word. The table changes only after the broker took the
  message (confirms and returns of the publisher); a publish is cut off after 30 s and neither fails nor stalls the pass
  (bounded at 55 s). `unreachableSince` is in the reads.
- **Tests use `mockwebserver3`** for the client of database-service, never the legacy
  `mockwebserver` (JUnit 4).
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
