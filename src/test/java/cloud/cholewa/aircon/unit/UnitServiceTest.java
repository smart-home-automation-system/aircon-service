package cloud.cholewa.aircon.unit;

import cloud.cholewa.aircon.api.model.AirconReply;
import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnitServiceTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 7, 13, 15, 45);
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");
    private static final AirConditioner BEDROOM = new AirConditioner(143, "Air conditioner", RoomName.BEDROOM,
        "192.0.2.240", "02005e0000a4");
    private static final UnitStatus COOLING = new UnitStatus(true, Mode.COOL, 22, 24, FanSpeed.AUTO, 0, 0, false, false,
        false, true, "hid");
    private static final AirconReply OFFICE_COOLING = new AirconReply(140, "Air conditioner", RoomName.OFFICE, true,
        Mode.COOL, 22, 24, FanSpeed.AUTO, 0, 0, false, false, false, true, true, AT);

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final StateStore store = mock(StateStore.class);
    private final UnitService service = new UnitService(registry, store);

    @Test
    void should_refuse_while_the_registry_is_not_known() {
        when(registry.current()).thenReturn(Optional.empty());

        service.units().as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.REGISTRY_UNAVAILABLE)))
            .verify();
    }

    //every unit with its state; a unit not read yet with its id, name and room alone
    @Test
    void should_answer_every_unit_with_the_state_kept() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, BEDROOM)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, AT)));
        when(store.of(143)).thenReturn(Optional.empty());

        service.units().as(StepVerifier::create)
            .expectNext(List.of(OFFICE_COOLING, new AirconReply(143, "Air conditioner", RoomName.BEDROOM, null, null,
                null, null, null, null, null, null, null, null, null, null, null)))
            .verifyComplete();
    }

    //restored at a start: what it reported, reachable not known
    @Test
    void should_answer_a_restored_unit_without_reachable() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, null, AT)));

        service.unit(140).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.power()).isTrue();
                assertThat(reply.reachable()).isNull();
                assertThat(reply.updatedAt()).isEqualTo(AT);
            })
            .verifyComplete();
    }

    @Test
    void should_refuse_a_unit_that_is_not_registered() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));

        service.unit(999).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.NOT_FOUND_AIRCON)))
            .verify();
    }

    @Test
    void should_answer_the_units_of_a_room() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, BEDROOM)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, AT)));

        service.room("office").as(StepVerifier::create)
            .expectNext(List.of(OFFICE_COOLING))
            .verifyComplete();
    }

    //a room without a unit and a name that is no room are the same answer
    @Test
    void should_refuse_a_room_without_a_unit() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));

        service.room("kitchen").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.NOT_FOUND_ROOM)))
            .verify();
        service.room("attic").as(StepVerifier::create)
            .expectError(AirconException.class)
            .verify();
    }
}
