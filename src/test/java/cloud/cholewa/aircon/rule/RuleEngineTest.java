package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.automation.AutomationState;
import cloud.cholewa.aircon.automation.GridState;
import cloud.cholewa.aircon.automation.RuleCommands;
import cloud.cholewa.aircon.command.CommandOutcomes;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.aircon.window.HeatingRoom;
import cloud.cholewa.aircon.window.RoomTemperatures;
import cloud.cholewa.aircon.window.RoomWindow;
import cloud.cholewa.aircon.window.WindowStates;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-15T13:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final AirConditioner SANCTUM = new AirConditioner(144, "Air conditioner", RoomName.SANCTUM,
        "192.0.2.60", "02005e0000a5");
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");
    private static final AirconRule RULE = new AirconRule(144, new AirconRule.KeepBelow(true, new BigDecimal("25.0"),
        new BigDecimal("1.0"), 22), new AirconRule.HeatBySchedule(false), NOW.minusDays(1));
    private static final UnitStatus OFF = new UnitStatus(false, Mode.HEAT, 21, 15, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);
    private static final CommandRequest COOL = new CommandRequest(true, Mode.COOL, 22, null, null, null, null, null, null,
        null);

    private final RuleRepository repository = mock(RuleRepository.class);
    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final StateStore store = mock(StateStore.class);
    private final RoomTemperatures temperatures = mock(RoomTemperatures.class);
    private final WindowStates windows = mock(WindowStates.class);
    private final AutomationState automation = mock(AutomationState.class);
    private final RuleCommands commands = mock(RuleCommands.class);
    private final CommandOutcomes outcomes = new CommandOutcomes();
    private final RuleEngine engine = new RuleEngine(repository, registry, store, temperatures, windows, automation,
        commands, outcomes, new RuleProperties(Duration.ofMinutes(30), Duration.ofHours(3)), CLOCK);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, SANCTUM)));
        when(repository.findAll()).thenReturn(Flux.just(RULE));
        when(store.of(144)).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        when(commands.send(anyLong(), any(), any(), any())).thenReturn(Mono.empty());
    }

    //the Plus H&T of sanctum reported two hours ago: still the room
    @Test
    void should_cool_sanctum_above_25_by_a_reading_of_two_hours_ago() {
        room(25.5, NOW.minusHours(2));

        engine.runOnce().as(StepVerifier::create).verifyComplete();

        verify(commands).send(144, COOL, ChangeSource.RULE, null);
    }

    @Test
    void should_not_decide_by_a_reading_older_than_three_hours() {
        room(28.0, NOW.minusHours(3).minusMinutes(1));

        engine.runOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), any(), any());
    }

    @Test
    void should_not_cool_while_the_grid_is_lost_or_the_window_holds() {
        room(28.0, NOW.minusMinutes(5));
        when(automation.grid()).thenReturn(Optional.of(new GridState("lost", NOW.minusMinutes(3))));

        engine.runOnce().as(StepVerifier::create).verifyComplete();

        when(automation.grid()).thenReturn(Optional.empty());
        when(windows.of("sanctum")).thenReturn(Optional.of(new RoomWindow("open", NOW.minusMinutes(5), true)));
        engine.runOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), any(), any());
    }

    @Test
    void should_switch_off_what_it_switched_on_below_the_hysteresis() {
        room(23.5, NOW.minusMinutes(5));
        when(store.of(144)).thenReturn(Optional.of(new UnitState(new UnitStatus(true, Mode.COOL, 22, 15, FanSpeed.AUTO,
            0, 0, false, false, false, true, null), true, NOW, null)));
        when(store.lastChange(144)).thenReturn(Optional.of(new StateStore.Change(ChangeSource.RULE, NOW.minusHours(1))));

        engine.runOnce().as(StepVerifier::create).verifyComplete();

        verify(commands).send(144, RuleEngine.off(), ChangeSource.RULE, null);
    }

    //a rule of a unit no longer registered is left alone; one that fails cuts nothing
    @Test
    void should_judge_every_rule_apart() {
        when(repository.findAll()).thenReturn(Flux.just(
            new AirconRule(999, RULE.keepBelow(), RULE.heatBySchedule(), NOW),
            new AirconRule(140, RULE.keepBelow(), RULE.heatBySchedule(), NOW), RULE));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        when(temperatures.of(any())).thenReturn(Optional.of(new HeatingRoom.Reading(27.0, NOW.minusMinutes(5))));
        when(commands.send(eq(140L), any(), any(), any()))
            .thenReturn(Mono.error(AirconException.deviceFailed("Air conditioner 140 did not take the command")));

        engine.runOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(eq(999L), any(), any(), any());
        verify(commands).send(144, COOL, ChangeSource.RULE, null);
    }

    //three commands in a row not taken: the rule pauses for the unit until a command is taken or it asks for nothing
    @Test
    void should_pause_for_a_unit_that_does_not_take_its_commands() {
        room(27.0, NOW.minusMinutes(5));
        for (int pass = 0; pass < 5; pass++) {
            engine.runOnce().as(StepVerifier::create).verifyComplete();
            outcomes.confirmed(144, outcomes.issue(144), false);
        }

        verify(commands, times(3)).send(144, COOL, ChangeSource.RULE, null);

        room(24.5, NOW.minusMinutes(1));
        engine.runOnce().as(StepVerifier::create).verifyComplete();
        room(27.0, NOW.minusMinutes(1));
        engine.runOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, times(4)).send(144, COOL, ChangeSource.RULE, null);
    }

    @Test
    void should_wait_for_the_registry() {
        when(registry.current()).thenReturn(Optional.empty());

        engine.runOnce().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).findAll();
    }

    private void room(final double value, final LocalDateTime at) {
        when(temperatures.of("sanctum")).thenReturn(Optional.of(new HeatingRoom.Reading(value, at)));
    }
}
