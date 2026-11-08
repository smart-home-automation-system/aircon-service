package cloud.cholewa.aircon.power;

import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PowerCycleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    static final PlugProperties PLUGS = new PlugProperties(
        new PlugProperties.Plug("192.0.2.44", PlugProperties.Meter.SWITCH, 0, List.of("office", "livia", "tobi", "bedroom")),
        new PlugProperties.Plug("192.0.2.175", PlugProperties.Meter.PM1, 0, List.of("sanctum")),
        20, Duration.ofSeconds(5));
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE, "192.0.2.59",
        "02005e0000a1");
    private static final AirConditioner SANCTUM = new AirConditioner(144, "Air conditioner", RoomName.SANCTUM,
        "192.0.2.60", "02005e0000a5");

    private final PlugMeter meter = mock(PlugMeter.class);
    private final PlugStore store = new PlugStore();
    private final PowerRepository repository = mock(PowerRepository.class);
    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final StateStore states = mock(StateStore.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final PowerCycle cycle = new PowerCycle(PLUGS, meter, store, repository, registry, states, publisher, CLOCK);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, SANCTUM)));
        when(states.of(anyLong())).thenReturn(Optional.empty());
        when(repository.insertCycle(any(), any(), any())).thenReturn(Mono.just(7L));
        when(repository.finishCycle(anyLong(), any(), any())).thenReturn(Mono.empty());
        when(meter.switchTo(any(), eq(false))).thenReturn(Mono.empty());
        when(meter.switchTo(any(), eq(true))).thenReturn(Mono.empty());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        indoorDraws(3.0);
    }

    @Test
    void should_allow_a_cycle_while_every_indoor_unit_is_off_and_the_plug_rests() {
        assertThat(cycle.refusal()).isEmpty();
    }

    //a unit known on - answering and on; a unit that does not answer is not known on, the one of sanctum is not fed
    @Test
    void should_refuse_while_an_indoor_unit_is_known_on() {
        when(states.of(140)).thenReturn(Optional.of(new UnitState(status(true), true, NOW, null)));
        assertThat(cycle.refusal()).contains("Air conditioner 140 is on");

        when(states.of(140)).thenReturn(Optional.of(new UnitState(status(true), false, NOW, NOW)));
        when(states.of(144)).thenReturn(Optional.of(new UnitState(status(true), true, NOW, null)));
        assertThat(cycle.refusal()).isEmpty();
    }

    @Test
    void should_refuse_while_the_plug_draws_above_the_standby_or_its_draw_is_not_known() {
        indoorDraws(20.5);
        assertThat(cycle.refusal()).contains("The indoor plug draws 20.5 W, above the standby of 20 W");

        store.answered(new PlugReading(PlugName.INDOOR, NOW.minusMinutes(3), 3.0, 230.0, 0.1, 100.0, true));
        assertThat(cycle.refusal()).contains("The draw of the indoor plug is not known");
    }

    @Test
    void should_answer_a_refusal_as_aircon_running_and_switch_nothing() {
        indoorDraws(600.0);

        cycle.start("Krzysztof").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.AIRCON_RUNNING)))
            .verify();
        verify(meter, never()).switchTo(any(), eq(false));
        verify(repository, never()).insertCycle(any(), any(), any());
    }

    //off, answered at once; on again after cycle-off-for, read back on - the row done; one cycle at a time
    @Test
    void should_switch_the_plug_off_and_on_again() {
        when(meter.read(eq(PlugName.INDOOR), any(), any()))
            .thenReturn(Mono.just(new PlugReading(PlugName.INDOOR, NOW, 0.0, 230.0, 0.0, 100.0, true)));

        cycle.start("Krzysztof").as(StepVerifier::create).verifyComplete();

        verify(repository).insertCycle(PlugName.INDOOR, NOW, "Krzysztof");
        verify(meter).switchTo(PLUGS.indoor(), false);
        cycle.start(null).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.POWER_CYCLE_IN_PROGRESS)))
            .verify();
        verify(meter, timeout(8000)).switchTo(PLUGS.indoor(), true);
        verify(repository, timeout(2000)).finishCycle(7, NOW, CycleResult.DONE);
        verify(publisher, never()).publishAlert(anyString());
    }

    //the off may have been carried out: the plug switched on before DEVICE_FAILED is answered
    @Test
    void should_switch_the_plug_on_when_its_off_fails() {
        when(meter.switchTo(any(), eq(false))).thenReturn(Mono.error(new IllegalStateException("no answer")));
        when(meter.read(eq(PlugName.INDOOR), any(), any()))
            .thenReturn(Mono.just(new PlugReading(PlugName.INDOOR, NOW, 0.0, 230.0, 0.0, 100.0, true)));

        cycle.start(null).as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.DEVICE_FAILED)))
            .verify();
        verify(meter).switchTo(PLUGS.indoor(), true);
        assertThat(cycle.refusal()).as("no cycle running").isEmpty();
    }

    //the plug stays off after every attempt: the row failed and an alert - the indoor units are without power
    @Test
    void should_alert_when_the_plug_stays_off() {
        when(meter.read(eq(PlugName.INDOOR), any(), any()))
            .thenReturn(Mono.just(new PlugReading(PlugName.INDOOR, NOW, 0.0, 230.0, 0.0, 100.0, false)));

        StepVerifier.withVirtualTime(() -> cycle.switchOnAgain(7))
            .thenAwait(Duration.ofSeconds(30))
            .verifyComplete();

        verify(meter, times(PowerCycle.ON_ATTEMPTS)).switchTo(PLUGS.indoor(), true);
        verify(repository).finishCycle(7, NOW, CycleResult.FAILED);
        verify(publisher).publishAlert(anyString());
    }

    //a cycle a restart cut off: the plug switched on, the row failed - once
    @Test
    void should_end_a_cycle_a_restart_cut_off() {
        when(repository.runningCycles()).thenReturn(Flux.just(5L));

        cycle.recoverInterrupted().as(StepVerifier::create).verifyComplete();
        cycle.recoverInterrupted().as(StepVerifier::create).verifyComplete();

        verify(meter).switchTo(PLUGS.indoor(), true);
        verify(repository).finishCycle(5, NOW, CycleResult.FAILED);
        verify(repository, times(1)).runningCycles();
    }

    private void indoorDraws(final double watts) {
        store.answered(new PlugReading(PlugName.INDOOR, NOW.minusSeconds(20), watts, 230.0, 0.1, 100.0, true));
    }

    private static UnitStatus status(final boolean power) {
        return new UnitStatus(power, Mode.COOL, 24, 25, FanSpeed.AUTO, 0, 0, false, false, false, true, null);
    }
}
