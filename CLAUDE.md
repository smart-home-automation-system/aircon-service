# aircon-service

The air conditioners of the house (epic HAS-376): five Gree indoor units over their local protocol -
state, commands and the rules of the house (open windows, 22 °C minimum in cooling, sanctum below
25 °C, the empty house, a lost grid, run hours). Scaffolded by HAS-380 with the units of the
registry and the driver; the state of every unit since HAS-381; the commands and the guard of 22 °C since HAS-382;
the availability monitor since HAS-383; the open windows since HAS-384; the empty house and a lost grid since HAS-385; the rule per unit since HAS-386; the frost
protection by schedule since HAS-387; the draw of the plugs and the power cycle since HAS-390; the run hours and the
filter reminder since HAS-391.

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
  every 5 min, k8s DNS), `heating-service` for the windows of the rooms (`GET /home/heating/rooms` every minute,
  >= 1.20.0, k8s DNS - HAS-384; the temperatures since HAS-386, what the schedules ask for since HAS-387 - >= 1.21.0 for
  sanctum), `presence-service` (`GET /home/presence/residents/presence`) and `energy-service`
  (`GET /home/energy/status`, >= 0.4.0) every minute for the empty house and the grid (HAS-385), the units themselves
  (UDP 7000) and the two plugs that feed them over HTTP (shelly-client, every 30 s - HAS-390).
- Uses libraries: `cholewa-commons` 1.13.0 (the pooled `ConnectionFactory`, the required
  properties, the scheduler pool and `ScheduledPass`, the error contract), `smart-home-sdk` 1.18.0
  (`DeviceKind.AIR_CONDITIONER`, `GreeBinding`), `shelly-client` 2.7.0 **with its beans on**
  (`Switch.GetStatus` / `Switch.Set` of the indoor plug, `PM1.GetStatus` of the meter of sanctum - HAS-390; pinned by
  `AirconServiceApplicationTest`).
- Database: own database `home-automation-aircon`, pool 2 - with it 22 of the 47 connections of the
  managed database are allotted to this project (25 with ristretto).

## Build & run

- Build + tests: `mvn verify` - **needs a running Docker** since HAS-381 (`AirconStateRepositoryTest`,
  `WindowHoldRepositoryTest`, `RuleRunRepositoryTest`, `RuleRepositoryTest`, `PowerRepositoryTest`, `FilterRepositoryTest` -
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
  the same way with 0.3.0. **`V5`** (HAS-384) creates `aircon_window_hold`; rehearsed the same way with 0.4.0 (0.4.0 starts over V5). **`V6`** (HAS-385)
  creates `aircon_rule_run`; rehearsed the same way with 0.5.0 (0.5.0 starts over V6). **`V7`** (HAS-386) creates
  `aircon_rule`; rehearsed the same way with 0.6.0 (0.6.0 starts over V7).
- **Scheduling is off under `test`** (`SchedulingConfig`, `@Profile("!test")`): the registry pass
  is wrapped in `ScheduledPass.bounded` (2 min, below its 5 min delay), the state pass too (25 s, below its 30 s),
  the availability monitor, the window pass, the automation and the rules (55 s, below their minute); the scheduler has the pool
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
- **The open windows** (HAS-384, `window/`): **heating-service judges the window, this service never does** - one rule of
  the windows (3 min) for the heaters and the units, no third copy of the sensors. `WindowHoldEvaluator` (pure) decides per
  unit every minute against its latest row of `aircon_window_hold` (`V5`): a window that holds stops a unit known to be on -
  the row first (what it was doing), then `power: false` through `CommandService` with `source=window`; **once per
  opening** (the `openSince` of heating-service is stored - a person who switches the unit on again with the window open is
  not fought; `openSince` is kept to the microsecond, as the column, or a stop read back would be a new opening); a
  command not taken is sent again next minute (`STOP_AGAIN`), never to a unit that does not answer (`reachable: false`). The windows `closed` within
  `aircon.window.restore-within` (PT1H) send the state back (`restoreOf`, `power: true`, the display left alone); later the
  stop `expired`. **A change by anybody but the window since the stop** (`StateStore.lastChange` - the remote, the
  dashboard, a rule; `window` and `first-read` are nobody) gives the restore up (`cancelled`); a restore the rules refuse is
  `refused`, one the unit did not answer is tried again. `unknown`, `no sensor`, a room heating-service does not answer and
  a failed read never stop and never set back (the stop waits, at most until `restore-within`). `WindowStates` holds what
  the reads answer as `window` - forgotten on a failed read. A stop that ended is deleted after 90 days (`KEPT`), with
  every pass. Known and left: with two windows open, the close of the older moves `openSince` and looks like a new
  opening (owner 2026-11-07). The DoD test was moved to the office: sanctum has no window
  sensor (heating-service answers `no sensor`, 2026-11-07).
- **The empty house and a lost grid** (HAS-385, `automation/`): `AutomationWatch` every minute reads presence-service and
  energy-service at once (10 s each; failed or slow is not known, **not known never acts**), `AutomationEvaluator` (pure)
  decides. Nobody at home for `aircon.empty-house.after` (PT10M) switches off the units on in `cool`, `dry`, `fan` or `auto`
  outside `exempt-rooms` (`sanctum`), `source=empty-house`; the grid `lost` switches off every unit on (heat and sanctum
  included), `source=grid-outage` - the grid first in a pass. **Once per episode**, by a key that survives a restart - the
  empty house by the `since` of the last member to go (presence-service; the first empty pass when one has none - known
  and left: that one moves with a restart, owner 2026-11-07), an outage by its `since` (energy-service). When an episode
  begins its units are taken - the units on then (`AutomationState.pending`) - and its key is stored in
  `aircon_automation_episode` (`V6`, one row a source, a unit on or not); every pass sends the command to those still on
  and answering until each took it (`AutomationEvaluator.pending`: on and answering - send, off - done, silent - wait; a
  refused one is done), so a pass cut by its bound or a unit that missed the datagrams goes on the next minute, and a
  unit switched on during the episode is never fought. The episode is restored before anything acts and forgotten when
  it ends; the units not yet taken are in memory only. Nothing is switched back on - and `WindowWatch` asks
  `AutomationState.holdsTheUnitsOff()` before a restore: while the house is empty or the grid lost the stop ends as
  `expired`. `GET /automation` answers the state of the last pass.
  **`PresenceClient`, `ResidentPresence` and `HouseOccupancy` are the fourth copy** of light, appliance and water - kept by
  the owner's choice (2026-11-07), not a review finding.
- **`RuleCommands`** (HAS-385): every command the service sends on its own - the window, the empty house, a lost grid, the
  rules to come - goes through it: `CommandService` under its source, then a row of `aircon_rule_run` (`V6`: source,
  unit, episode, the wire command, `sent` / `failed` / `refused`), kept 90 days. A row that fails is a WARN. The window
  logs its commands there too (the episode is the time of the stop).
- **The rule per unit** (HAS-386, `rule/`): `RuleRules` is **a pure class the dashboard copies** - the threshold 20-30 C
  by 0.5, the hysteresis 0.5-3 by 0.5 (default 1), the target a whole degree 22-30 and never above the threshold (so a
  threshold below 22 is refused while the target is 22) - repeated as the checks of `aircon_rule` (`V7`). `RuleEngine`
  every minute (90 s in, bounded 55 s) reads the table and judges each rule of a registered unit by `RuleEvaluator`
  (pure): **the room from heating-service only** (`RoomTemperatures`, written by the window pass - one read of
  heating-service a minute) **while not older than `aircon.rule.fresh-for` (PT3H)**, never the sensor of the unit (owner
  2026-11-07: it reads 2-7 C low in sanctum; the Plus H&T reports on a change and every two hours, so 30 min would
  discard it); above the threshold - cool at the target unless cooling, `source=rule`; below threshold - hysteresis - off,
  only while the unit's last change is the rule's and it is on. Nothing within `manual-hold` (PT30M) of a change by the
  remote or the dashboard, nothing for a unit not answering or not read, no cooling while the grid is lost or the window
  of its room holds (the off still goes). **The last change of every unit is restored from `aircon_state_change` at a
  start** (`AirconStateRepository.lastChanges`), so the rule knows after a restart which unit it switched on - and the
  window which unit a person changed. **Heat by schedule is a frost protection** (HAS-387, owner 2026-11-08: the
  comfort is switched on by hand): what the schedule of the room asks for now (`RoomSchedules`, written by the window pass
  from `heaters[].scheduledTemperature` - the most any heater asks for; a room of the read without a schedule is known as
  such, a failed read makes every room not known) against the same reading of the room as keep below. The room below the
  schedule by more than `FROST_BAND` (1 C) - power on in heat **without a target** (the unit keeps the 21 C of the owner),
  unless the unit heats already, whoever switched it on; the room at the schedule, or no schedule - off, only a unit the
  rule switched on **in heat** (keep below switches off only one **in cool**) and only after `heat-min-run` (PT30M) since
  that change; a unit the rule switched off rests `heat-min-off` (PT10M). The house switch of heating-service is not read
  (the first version of HAS-387, 0.8.0, read it and heated at the schedule from 16 C - replaced in 0.9.0). Keep below wins
  while the room is above its threshold. Not known never acts. A last change of `window` (the restore of a stop) leaves a unit the rule's, as `rule` does. Commands through `RuleCommands`;
  after `MAX_NOT_TAKEN` (3) commands in a row the unit did not take (`CommandOutcomes.lastCommandConfirmed`) the rule
  pauses for it with one WARN, until it asks for nothing or a command is taken (in memory). A target the rule lowers on a
  unit a person switched on makes the unit the rule's - it may switch it off later (keeping the room below, accepted).
- **The plugs that feed the units** (HAS-390, `power/`): not devices of the registry - `aircon.plugs.indoor` (the Plus
  Plug S of office, livia, tobi and bedroom, `switch` 0, the emergency switch of the owner) and `aircon.plugs.sanctum`
  (the PM Mini Gen3, `pm1` 0 - a meter, never switched), their addresses from the manifest (`plug-indoor-address`,
  `plug-sanctum-address`, required). `PowerWatch` reads both every 30 s (15 s in, bounded 25 s, one after the other):
  the reading in memory (`PlugStore` - a reading older than 2 min is not the plug now) and a row of
  `aircon_power_sample` (`V8`, every sample, 30 days); a plug that does not answer is one WARN and one INFO at its return,
  no alert (the units are watched by the monitor). Every hour at minute 15 it closes the 30 days before today into
  `aircon_power_day` (`ON CONFLICT DO NOTHING` - closed once, from 00:15; a day without a sample is not closed, never
  0 kWh) and deletes what is older than kept (samples 30 days, days 730, cycles 365). **The energy is the sum of the rises of
  `aenergy.total`** between the samples, the one before the range included, a fall (a reset) never counted (`ENERGY` of
  `PowerRepository`, PostgreSQL's `LAG`); fewer than two samples is not known. `compressorRunning` is the plug above
  `standby-watts` (20 W) - in `GET /power` and in the reads of every unit the plug feeds (the indoor plug feeds four, so
  `true` says one of them runs). `GET /power/samples` answers buckets of avg / min / max power by `HistoryRange` -
  **the fifth copy** (weather, water, heating, energy), kept by the owner's choice (2026-11-08), not a review finding;
  `INVALID_RANGE`, `NOT_FOUND_PLUG`.
- **The power cycle** (HAS-390, `PowerCycle`): by hand only - never after an alert (owner). Refused (`409
  AIRCON_RUNNING`) while an indoor unit is known on (answering and on - a unit not answering, the usual reason for a cycle,
  never blocks it), while the indoor plug draws above the standby, or while its draw is not known; `409
  POWER_CYCLE_IN_PROGRESS` while one runs (in memory). A row of `aircon_power_cycle` (`running`, who asked), `Switch.Set`
  off, `202`; then, outside the request and every bounded pass, `cycle-off-for` (PT15S) and on again - each attempt
  deferred, up to three 5 s apart, read back by `Switch.GetStatus` - `done`, or `failed` with an alert on the technical
  channel (the four indoor units are without power). An off not taken may have been carried out: the plug is switched on
  before `502 DEVICE_FAILED`. A row still `running` at a start is a cycle a restart cut off: the first pass of the plugs
  switches the plug on and fails the row. The table is separate from `aircon_rule_run` (the task named that one): a
  cycle is of a plug, not of a unit, and keeps who asked. **The first real cycle** (the owner from Bruno, 2026-11-08 12:38,
  every indoor unit off): `done` in 15.3 s; the four units **stayed off** after the power came back (a unit on cannot be
  cycled, so whether one resumes is not known); office, livia and tobi answered within 10 s of the plug, bedroom (143)
  only after about 3 min (one WARN of the state tracker, no alert - the monitor waits 5 min); the indoor plug drew
  24.9 W while the units booted (`compressorRunning` true and the next cycle refused for 1-2 min), then 18 W again -
  the standby of 20 W leaves about 2 W of margin.
- **The run hours and the filters** (HAS-391, `filter/`): the hours of a unit are **the time the history shows it on**
  (`aircon_state_change`: every change lasts until the next one, the last until now - `HOURS` of `FilterRepository`,
  PostgreSQL's `LEAD`), since its cleaning in `aircon_filter` (`V9`: `cleaned_at`, `reminded_hours`), or the whole
  history before a cleaning is recorded. They count what the last read showed: a unit cut from its power while on (a lost
  grid, the power cycle cannot cut one that is on) counts on until it is read again - accepted. `FilterWatch` every 10
  minutes (3 min in, bounded 5 min) counts each registered unit into `FilterStore` (the reads answer it without a
  statement) and names a filter due on Discord as an **info**: at `aircon.filter.remind-after-hours` (200) and every
  `remind-again-hours` (50) more (`FilterReminder`, pure - a pass that missed steps names the last one alone); the step
  is stored after the broker took it, so a restart never repeats one and a failed publish goes again. `POST
  /{id}/filter-cleaned` stores the cleaning (the step forgotten) and sets the reads to 0 at once. `GET /{id}/runs` answers
  the changes with their source by the rules of `HistoryRange` (moved to `history/`, shared with the power history);
  `ChangeSource` is serialized as its word since.
- **Tests use `mockwebserver3`** for the client of database-service, never the legacy
  `mockwebserver` (JUnit 4).
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
