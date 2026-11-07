package cloud.cholewa.aircon.api;

import cloud.cholewa.aircon.api.model.AirconReply;
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
        null, new WindowReply("open", LocalDateTime.of(2026, 11, 7, 13, 10), true, LocalDateTime.of(2026, 11, 7, 14, 13)));

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UnitService unitService;

    @MockitoBean
    private CommandService commandService;

    @MockitoBean
    private AutomationService automationService;

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
                null, null, null, null, null, null, null, null, null))));

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
                              "restoreUntil": "2026-11-07T14:13:00"}},
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
}
