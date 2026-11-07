package cloud.cholewa.aircon.api;

import cloud.cholewa.aircon.api.model.UnitReply;
import cloud.cholewa.aircon.config.ExceptionHandlerConfig;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitService;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.Mockito.when;

//The JSON is the contract of the dashboard, so the body is written out whole
@WebFluxTest(AirconController.class)
@Import(ExceptionHandlerConfig.class)
class AirconControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UnitService unitService;

    //a unit that did not answer has status null, written out; a field the unit did not report is null too
    @Test
    void should_answer_every_unit_with_its_status() {
        when(unitService.units()).thenReturn(Mono.just(List.of(
            new UnitReply(140, "Air conditioner", RoomName.OFFICE, true, new UnitStatus(true, Mode.COOL, 22, 24,
                FanSpeed.MEDIUM_LOW, 0, 0, false, false, false, true, "362001065279+U-WB05RT13V1.45.bin")),
            new UnitReply(143, "Air conditioner", RoomName.HALL_UP, false, null))));

        webTestClient.get().uri("")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"id": 140, "name": "Air conditioner", "room": "office", "reachable": true,
                   "status": {"power": true, "mode": "cool", "targetTemperature": 22, "roomTemperature": 24,
                              "fanSpeed": "medium low", "swingVertical": 0, "swingHorizontal": 0, "quiet": false,
                              "turbo": false, "sleep": false, "light": true,
                              "firmware": "362001065279+U-WB05RT13V1.45.bin"}},
                  {"id": 143, "name": "Air conditioner", "room": "hall up", "reachable": false, "status": null}
                ]""", JsonCompareMode.STRICT);
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
