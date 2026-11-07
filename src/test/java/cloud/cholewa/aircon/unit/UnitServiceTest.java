package cloud.cholewa.aircon.unit;

import cloud.cholewa.aircon.api.model.UnitReply;
import cloud.cholewa.aircon.driver.GreeClient;
import cloud.cholewa.aircon.driver.GreeTimeoutException;
import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnitServiceTest {

    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");
    private static final AirConditioner BEDROOM = new AirConditioner(143, "Air conditioner", RoomName.BEDROOM,
        "192.0.2.240", "02005e0000a4");
    private static final UnitStatus COOLING = new UnitStatus(true, Mode.COOL, 22, 24, FanSpeed.AUTO, 0, 0, false, false,
        false, true, "hid");

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final GreeClient greeClient = mock(GreeClient.class);
    private final UnitService service = new UnitService(registry, greeClient);

    @Test
    void should_refuse_while_the_registry_is_not_known() {
        when(registry.current()).thenReturn(Optional.empty());

        service.units().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.REGISTRY_UNAVAILABLE)))
            .verify();
    }

    //every unit in the order of the registry; one that does not answer is unreachable, the others are not held up
    @Test
    void should_answer_every_unit_with_a_live_read() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, BEDROOM)));
        when(greeClient.status(OFFICE)).thenReturn(Mono.just(COOLING));
        when(greeClient.status(BEDROOM)).thenReturn(Mono.error(new GreeTimeoutException("192.0.2.240", 3)));

        service.units().as(StepVerifier::create)
            .expectNext(List.of(
                new UnitReply(140, "Air conditioner", RoomName.OFFICE, true, COOLING),
                new UnitReply(143, "Air conditioner", RoomName.BEDROOM, false, null)))
            .verifyComplete();
    }

    @Test
    void should_answer_a_registry_without_units() {
        when(registry.current()).thenReturn(Optional.of(List.of()));

        service.units().as(StepVerifier::create).expectNext(List.of()).verifyComplete();
    }
}
