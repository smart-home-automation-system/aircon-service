package cloud.cholewa.aircon.api;

import cloud.cholewa.aircon.api.model.AirconReply;
import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.api.model.RuleBody;
import cloud.cholewa.aircon.rule.RuleService;
import cloud.cholewa.aircon.power.PowerService;
import cloud.cholewa.aircon.api.model.FilterReply;
import cloud.cholewa.aircon.filter.FilterService;
import cloud.cholewa.aircon.filter.UnitRun;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.api.model.PlugReply;
import cloud.cholewa.aircon.api.model.PowerCycleReply;
import cloud.cholewa.aircon.power.CycleResult;
import cloud.cholewa.aircon.power.PowerBucket;
import cloud.cholewa.aircon.power.PowerCycleRun;
import cloud.cholewa.aircon.api.model.AutomationReply;
import cloud.cholewa.aircon.automation.AutomationService;
import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.api.model.RoomCommandReply;
import cloud.cholewa.aircon.api.model.WindowReply;
import cloud.cholewa.aircon.command.CommandService;
import cloud.cholewa.aircon.config.ExceptionHandlerConfig;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitService;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

//The JSON is the contract of the dashboard, so the body is written out whole
@WebFluxTest(AirconController.class)
@Import(ExceptionHandlerConfig.class)
class AirconControllerTest {

    private static final AirconReply OFFICE = new AirconReply(140, "Air conditioner", RoomName.OFFICE, true, Mode.COOL,
        22, 24, FanSpeed.MEDIUM_LOW, 0, 2, false, false, false, true, true, null, LocalDateTime.of(2026, 11, 7, 13, 15, 45), true,
        null, new WindowReply("open", LocalDateTime.of(2026, 11, 7, 13, 10), true, LocalDateTime.of(2026, 11, 7, 14, 13)), true,
        new FilterReply(203.4, LocalDateTime.of(2026, 11, 1, 10, 0), true));

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UnitService unitService;

    @MockitoBean
    private CommandService commandService;

    @MockitoBean
    private AutomationService automationService;

    @MockitoBean
    private RuleService ruleService;

    @MockitoBean
    private PowerService powerService;

    @MockitoBean
    private FilterService filterService;

    private static final AirconRule SANCTUM_RULE = new AirconRule(144, new AirconRule.KeepBelow(true,
        new BigDecimal("25.0"), new BigDecimal("1.0"), 22), new AirconRule.HeatBySchedule(false),
        LocalDateTime.of(2026, 11, 7, 20, 0));

    //the rule of a unit (HAS-386), the contract of the editor of the dashboard
    @Test
    void should_answer_the_rules() {
        when(ruleService.rules()).thenReturn(Mono.just(List.of(SANCTUM_RULE)));

        webTestClient.get().uri("/rules")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"unitId": 144, "keepBelow": {"enabled": true, "threshold": 25.0, "hysteresis": 1.0, "target": 22},
                  "heatBySchedule": {"enabled": false}, "updatedAt": "2026-11-07T20:00:00"}]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_write_a_rule_whole() {
        final RuleBody body = new RuleBody(new RuleBody.KeepBelow(true, new BigDecimal("25"), null, 22), null);
        when(ruleService.save(144, body)).thenReturn(Mono.just(SANCTUM_RULE));

        webTestClient.put().uri("/144/rules")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"keepBelow\": {\"enabled\": true, \"threshold\": 25, \"target\": 22}}")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.keepBelow.hysteresis").isEqualTo(1.0);
    }

    @Test
    void should_refuse_a_rule_and_a_missing_one_by_its_code() {
        when(ruleService.save(any(Long.class), any())).thenReturn(Mono.error(AirconException.invalidRule("The target is not above the threshold")));
        when(ruleService.rule(140)).thenReturn(Mono.error(AirconException.ruleNotFound(140)));
        when(ruleService.delete(144)).thenReturn(Mono.empty());

        webTestClient.put().uri("/144/rules").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("INVALID_RULE");
        webTestClient.get().uri("/140/rules").exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_RULE");
        webTestClient.delete().uri("/144/rules").exchange().expectStatus().isNoContent();
    }

    //the empty house and the grid (HAS-385); a field not known is left out, the objects stay
    @Test
    void should_answer_the_automation() {
        when(automationService.automation()).thenReturn(Mono.just(new AutomationReply(
            new AutomationReply.EmptyHouse(true, LocalDateTime.of(2026, 11, 7, 18, 45), LocalDateTime.of(2026, 11, 7, 18, 55)),
            new AutomationReply.Grid(null, null))));

        webTestClient.get().uri("/automation")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"emptyHouse": {"empty": true, "since": "2026-11-07T18:45:00", "actedAt": "2026-11-07T18:55:00"},
                 "grid": {}}""", JsonCompareMode.STRICT);
    }

    //a field not known is left out - a unit not read yet is its id, name and room
    @Test
    void should_answer_every_unit_with_what_is_known_of_it() {
        when(unitService.units()).thenReturn(Mono.just(List.of(OFFICE,
            new AirconReply(143, "Air conditioner", RoomName.HALL_UP, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null))));

        webTestClient.get().uri("")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"id": 140, "name": "Air conditioner", "room": "office", "power": true, "mode": "cool",
                   "targetTemperature": 22, "roomTemperature": 24, "fanSpeed": "medium-low", "swingVertical": 0,
                   "swingHorizontal": 2, "quiet": false, "turbo": false, "sleep": false, "display": true,
                   "reachable": true, "updatedAt": "2026-11-07T13:15:45", "lastCommandConfirmed": true,
                   "window": {"state": "open", "openSince": "2026-11-07T13:10:00", "hold": true,
                              "restoreUntil": "2026-11-07T14:13:00"}, "compressorRunning": true,
                   "filter": {"hoursSince": 203.4, "cleanedAt": "2026-11-01T10:00:00", "due": true}},
                  {"id": 143, "name": "Air conditioner", "room": "hall up"}
                ]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_one_unit() {
        when(unitService.unit(140)).thenReturn(Mono.just(OFFICE));

        webTestClient.get().uri("/140")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.id").isEqualTo(140).jsonPath("$.fanSpeed").isEqualTo("medium-low");
    }

    @Test
    void should_answer_the_units_of_a_room_by_its_name() {
        when(unitService.room("hall up")).thenReturn(Mono.just(List.of(OFFICE)));

        webTestClient.get().uri("/rooms/{room}", "hall up")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$[0].id").isEqualTo(140);
    }

    @Test
    void should_refuse_a_unit_and_a_room_that_are_not_registered() {
        when(unitService.unit(999)).thenReturn(Mono.error(AirconException.airconNotFound(999)));
        when(unitService.room("kitchen")).thenReturn(Mono.error(AirconException.roomNotFound()));

        webTestClient.get().uri("/999").exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_AIRCON");
        webTestClient.get().uri("/rooms/kitchen").exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_ROOM");
    }

    //a command (HAS-382): the body in the words of the reads, the dashboard the source by default, 202
    @Test
    void should_accept_a_command() {
        final CommandRequest command = new CommandRequest(true, Mode.COOL, 23, FanSpeed.MEDIUM_HIGH, null, null, null,
            null, null, null);
        when(commandService.send(140, command, "dashboard")).thenReturn(Mono.empty());

        webTestClient.post().uri("/140")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"power": true, "mode": "cool", "targetTemperature": 23, "fanSpeed": "medium-high"}""")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().isEmpty();
    }

    @Test
    void should_refuse_a_command_the_rules_refuse() {
        when(commandService.send(eq(140L), any(), eq("window")))
            .thenReturn(Mono.error(AirconException.invalidCommand("Cooling never goes below 22 C, not 20")));

        webTestClient.post().uri("/140?source=window")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"targetTemperature": 20}""")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("INVALID_COMMAND")
            .jsonPath("$.errors[0].details").isEqualTo("Cooling never goes below 22 C, not 20");
    }

    @Test
    void should_answer_device_failed_as_a_bad_gateway() {
        when(commandService.send(eq(140L), any(), eq("dashboard")))
            .thenReturn(Mono.error(AirconException.deviceFailed("Air conditioner 140 did not take the command")));

        webTestClient.post().uri("/140")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"power": false}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("DEVICE_FAILED");
    }

    @Test
    void should_answer_a_command_of_a_room_unit_by_unit() {
        when(commandService.sendToRoom(eq("office"), any(), eq("dashboard")))
            .thenReturn(Mono.just(List.of(new RoomCommandReply(140, true, null),
                new RoomCommandReply(145, false, "DEVICE_FAILED"))));

        webTestClient.post().uri("/rooms/office")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"power": false}""")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().json("""
                [{"id": 140, "sent": true}, {"id": 145, "sent": false, "code": "DEVICE_FAILED"}]""",
                JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_while_the_registry_is_not_known() {
        when(unitService.units()).thenReturn(Mono.error(AirconException.registryUnavailable()));

        webTestClient.get().uri("")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("REGISTRY_UNAVAILABLE")
            .jsonPath("$.errors[0].message").isEqualTo("Device registry unavailable");
    }

    //HAS-390: the plugs - a field not known is left out; the meter of sanctum has no relay
    @Test
    void should_answer_the_plugs() {
        when(powerService.plugs()).thenReturn(Mono.just(List.of(
            new PlugReply("indoor", List.of("office", "livia", "tobi", "bedroom"), true, 612.4, 231.6, 2.87, true, true,
                true, LocalDateTime.of(2026, 11, 8, 11, 0, 5), 3.2, 21.5, 80.0),
            new PlugReply("sanctum", List.of("sanctum"), false, null, null, null, null, null, false,
                LocalDateTime.of(2026, 11, 8, 10, 50), null, null, null))));

        webTestClient.get().uri("/power")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"plug": "indoor", "rooms": ["office", "livia", "tobi", "bedroom"], "relay": true, "power": 612.4,
                  "voltage": 231.6, "current": 2.87, "output": true, "compressorRunning": true, "reachable": true,
                  "updatedAt": "2026-11-08T11:00:05", "todayKwh": 3.2, "last7DaysKwh": 21.5, "last30DaysKwh": 80.0},
                 {"plug": "sanctum", "rooms": ["sanctum"], "relay": false, "reachable": false,
                  "updatedAt": "2026-11-08T10:50:00"}]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_the_samples_of_a_plug() {
        final LocalDateTime from = LocalDateTime.of(2026, 11, 7, 0, 0);
        when(powerService.samples("indoor", from, from.plusDays(1)))
            .thenReturn(Mono.just(List.of(new PowerBucket(from, 500.0, 400.0, 600.0))));

        webTestClient.get().uri("/power/samples?plug=indoor&from=2026-11-07T00:00:00&to=2026-11-08T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"at": "2026-11-07T00:00:00", "avg": 500.0, "min": 400.0, "max": 600.0}]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_the_samples_of_an_unknown_plug() {
        when(powerService.samples(eq("garage"), any(), any())).thenReturn(Mono.error(AirconException.plugNotFound()));

        webTestClient.get().uri("/power/samples?plug=garage&from=2026-11-07T00:00:00&to=2026-11-08T00:00:00")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_PLUG");
    }

    @Test
    void should_answer_whether_a_power_cycle_is_allowed() {
        when(powerService.powerCycle()).thenReturn(Mono.just(new PowerCycleReply(false, "Air conditioner 140 is on",
            new PowerCycleRun(LocalDateTime.of(2026, 11, 8, 9, 0), LocalDateTime.of(2026, 11, 8, 9, 0, 16), "Krzysztof",
                CycleResult.DONE))));

        webTestClient.get().uri("/power-cycle")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                {"allowed": false, "reason": "Air conditioner 140 is on",
                 "lastRun": {"startedAt": "2026-11-08T09:00:00", "finishedAt": "2026-11-08T09:00:16",
                             "requestedBy": "Krzysztof", "result": "done"}}""", JsonCompareMode.STRICT);
    }

    @Test
    void should_start_a_power_cycle_or_refuse_it() {
        when(powerService.startPowerCycle("Krzysztof")).thenReturn(Mono.empty());
        webTestClient.post().uri("/power-cycle?member=Krzysztof").exchange().expectStatus().isAccepted();

        when(powerService.startPowerCycle(null))
            .thenReturn(Mono.error(AirconException.airconRunning("Air conditioner 140 is on")));
        webTestClient.post().uri("/power-cycle")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("AIRCON_RUNNING");
    }

    @Test
    void should_refuse_a_member_name_too_long() {
        webTestClient.post().uri("/power-cycle?member=" + "a".repeat(51))
            .exchange()
            .expectStatus().isBadRequest();
    }

    //HAS-391: a cleaning recorded, 204; a unit not registered 404
    @Test
    void should_record_a_cleaning_of_a_filter() {
        when(filterService.cleaned(140)).thenReturn(Mono.empty());
        webTestClient.post().uri("/140/filter-cleaned").exchange().expectStatus().isNoContent();

        when(filterService.cleaned(999)).thenReturn(Mono.error(AirconException.airconNotFound(999)));
        webTestClient.post().uri("/999/filter-cleaned")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_AIRCON");
    }

    //the changes of a unit in the words of the reads, the source in the words of the history
    @Test
    void should_answer_the_runs_of_a_unit() {
        final LocalDateTime from = LocalDateTime.of(2026, 11, 7, 0, 0);
        when(filterService.runs(140, from, from.plusDays(1))).thenReturn(Mono.just(List.of(
            new UnitRun(from.plusHours(8), true, Mode.HEAT, 21, FanSpeed.AUTO, ChangeSource.RULE),
            new UnitRun(from.plusHours(9), false, null, null, null, ChangeSource.EMPTY_HOUSE))));

        webTestClient.get().uri("/140/runs?from=2026-11-07T00:00:00&to=2026-11-08T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"at": "2026-11-07T08:00:00", "power": true, "mode": "heat", "targetTemperature": 21, "fanSpeed": "auto",
                  "source": "rule"},
                 {"at": "2026-11-07T09:00:00", "power": false, "source": "empty-house"}]""", JsonCompareMode.STRICT);
    }

    @Test
    void should_refuse_the_runs_of_a_range_too_long() {
        when(filterService.runs(eq(140L), any(), any()))
            .thenReturn(Mono.error(AirconException.invalidRange("The range must not be longer than 31 days")));

        webTestClient.get().uri("/140/runs?from=2026-09-01T00:00:00&to=2026-11-08T00:00:00")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("INVALID_RANGE");
    }
}
