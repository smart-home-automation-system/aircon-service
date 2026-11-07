package cloud.cholewa.aircon.registry;

import cloud.cholewa.aircon.driver.GreeCallException;
import cloud.cholewa.aircon.driver.GreeClient;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.GreeBinding;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnitRegistryTest {

    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final GreeClient greeClient = mock(GreeClient.class);
    private final UnitRegistry registry = new UnitRegistry(client, greeClient);

    @Test
    void should_know_nothing_before_the_first_read() {
        assertThat(registry.current()).isEmpty();
    }

    //read, then every unit not bound yet is bound - the first request of the API finds its key
    @Test
    void should_know_the_units_of_the_registry_and_bind_them() {
        when(client.getAirConditioners()).thenReturn(Mono.just(List.of(office())));
        when(greeClient.bind(any())).thenReturn(Mono.empty());

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).contains(List.of(OFFICE));
        verify(greeClient).bind(OFFICE);
    }

    @Test
    void should_not_bind_a_unit_bound_before() {
        when(client.getAirConditioners()).thenReturn(Mono.just(List.of(office())));
        when(greeClient.isBound(OFFICE)).thenReturn(true);

        registry.refresh().as(StepVerifier::create).verifyComplete();

        verify(greeClient, never()).bind(any());
    }

    //a unit that does not bind stays in the registry - it is bound again at its first request
    @Test
    void should_keep_a_unit_that_did_not_bind() {
        when(client.getAirConditioners()).thenReturn(Mono.just(List.of(office())));
        when(greeClient.bind(any())).thenReturn(Mono.error(new GreeCallException("no answer")));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).contains(List.of(OFFICE));
    }

    //a switched-off device is left out; one without a whole binding is not understood
    @Test
    void should_leave_out_what_is_switched_off_or_not_understood() {
        final SmartDevice off = device(141L, false, GreeBinding.builder().address("192.0.2.61").mac("02005e0000a2").build());
        final SmartDevice noBinding = device(142L, true, GreeBinding.builder().address("192.0.2.60").build());
        when(client.getAirConditioners()).thenReturn(Mono.just(List.of(office(), off, noBinding)));
        when(greeClient.bind(any())).thenReturn(Mono.empty());

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).contains(List.of(OFFICE));
    }

    //a failed read keeps what was read before
    @Test
    void should_keep_the_units_when_a_read_fails() {
        when(client.getAirConditioners()).thenReturn(Mono.just(List.of(office())),
            Mono.error(new RegistryCallException("database-service answered 500")));
        when(greeClient.bind(any())).thenReturn(Mono.empty());

        registry.refresh().as(StepVerifier::create).verifyComplete();
        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).contains(List.of(OFFICE));
    }

    @Test
    void should_stay_not_known_when_the_first_read_fails() {
        when(client.getAirConditioners()).thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).isEmpty();
    }

    private static SmartDevice office() {
        return device(140L, true, GreeBinding.builder().address("192.0.2.59").mac("02005e0000a1").build());
    }

    private static SmartDevice device(final long id, final boolean enabled, final GreeBinding binding) {
        return SmartDevice.builder()
            .id(id)
            .name("Air conditioner")
            .room(RoomName.OFFICE)
            .kind(DeviceKind.AIR_CONDITIONER)
            .enabled(enabled)
            .vendor(SmartVendor.GREE)
            .gree(binding)
            .build();
    }
}
