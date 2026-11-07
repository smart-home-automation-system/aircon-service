package cloud.cholewa.aircon.window;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.command.CommandService;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.StateStore.Change;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WindowWatchTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T17:30:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final Duration WITHIN = Duration.ofHours(1);
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");
    private static final AirConditioner LIVIA = new AirConditioner(141, "Air conditioner", RoomName.LIVIA,
        "192.0.2.56", "02005e0000a2");
    private static final UnitStatus COOLING = new UnitStatus(true, Mode.COOL, 24, 26, FanSpeed.AUTO, 0, 2, false, true,
        false, true, "hid");
    private static final UnitStatus OFF = new UnitStatus(false, Mode.COOL, 24, 26, FanSpeed.AUTO, 0, 2, false, true,
        false, true, "hid");
    private static final LocalDateTime OPENED = NOW.minusMinutes(4);

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final HeatingClient heating = mock(HeatingClient.class);
    private final WindowHoldRepository repository = mock(WindowHoldRepository.class);
    private final WindowStates windowStates = new WindowStates();
    private final StateStore store = mock(StateStore.class);
    private final CommandService commands = mock(CommandService.class);
    private final WindowWatch watch = new WindowWatch(registry, heating, repository, windowStates, store, commands,
        new WindowProperties(WITHIN), CLOCK);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(repository.latest()).thenReturn(Flux.empty());
        when(repository.insert(anyLong(), any(), any(), any())).thenReturn(Mono.empty());
        when(repository.close(anyLong(), any(), any())).thenReturn(Mono.empty());
        when(repository.deleteClosedBefore(any())).thenReturn(Mono.empty());
        when(commands.send(anyLong(), any(), anyString())).thenReturn(Mono.empty());
    }

    //the stop kept first, then the unit switched off by the window
    @Test
    void should_stop_a_cooling_unit_whose_window_holds() {
        rooms(new HeatingRoom("office", new RoomWindow("open", OPENED, true)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        final var order = inOrder(repository, commands);
        order.verify(repository).insert(140, OPENED, NOW, COOLING);
        order.verify(commands).send(140, WindowWatch.OFF, "window");
        assertThat(windowStates.restoreUntil(140)).contains(NOW.plus(WITHIN));
        assertThat(windowStates.of("office")).contains(new RoomWindow("open", OPENED, true));
    }

    //a command that failed is tried again next minute - the stop stays
    @Test
    void should_keep_the_stop_when_the_unit_did_not_take_the_command() {
        rooms(new HeatingRoom("office", new RoomWindow("open", OPENED, true)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));
        when(commands.send(anyLong(), any(), anyString()))
            .thenReturn(Mono.error(AirconException.deviceFailed("Air conditioner 140 did not take the command")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(repository).insert(140, OPENED, NOW, COOLING);
        verify(repository, never()).close(anyLong(), any(), any());
    }

    //heating-service silent: nothing stops, nothing is set back, the windows are not known
    @Test
    void should_do_nothing_while_heating_service_does_not_answer() {
        when(heating.getRooms()).thenReturn(Mono.error(new HeatingCallException("heating-service answered 503")));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        when(repository.latest()).thenReturn(Flux.just(open(NOW.minusMinutes(10))));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), anyString());
        verify(repository, never()).close(anyLong(), any(), any());
        assertThat(windowStates.of("office")).isEmpty();
        assertThat(windowStates.restoreUntil(140)).contains(NOW.minusMinutes(10).plus(WITHIN));
    }

    @Test
    void should_set_back_a_unit_when_its_windows_close() {
        rooms(new HeatingRoom("office", new RoomWindow("closed", null, false)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        when(repository.latest()).thenReturn(Flux.just(open(NOW.minusMinutes(10))));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands).send(140, new CommandRequest(true, Mode.COOL, 24, FanSpeed.AUTO, 0, 2, false, true, false, null),
            "window");
        verify(repository).close(7L, HoldOutcome.RESTORED, NOW);
        assertThat(windowStates.restoreUntil(140)).isEmpty();
    }

    @Test
    void should_close_a_restore_the_rules_refuse() {
        rooms(new HeatingRoom("office", new RoomWindow("closed", null, false)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        when(repository.latest()).thenReturn(Flux.just(open(NOW.minusMinutes(10))));
        when(commands.send(anyLong(), any(), anyString()))
            .thenReturn(Mono.error(AirconException.invalidCommand("Cooling never goes below 22 C, not 18")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(repository).close(7L, HoldOutcome.REFUSED, NOW);
    }

    @Test
    void should_try_a_restore_again_when_the_unit_did_not_answer() {
        rooms(new HeatingRoom("office", new RoomWindow("closed", null, false)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, false, NOW, NOW.minusMinutes(1))));
        when(repository.latest()).thenReturn(Flux.just(open(NOW.minusMinutes(10))));
        when(commands.send(anyLong(), any(), anyString()))
            .thenReturn(Mono.error(AirconException.deviceFailed("Air conditioner 140 did not take the command")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).close(anyLong(), any(), any());
        assertThat(windowStates.restoreUntil(140)).isPresent();
    }

    @Test
    void should_give_up_the_restore_of_a_unit_changed_with_the_remote() {
        rooms(new HeatingRoom("office", new RoomWindow("closed", null, false)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));
        when(store.lastChange(140)).thenReturn(Optional.of(new Change(ChangeSource.REMOTE, NOW.minusMinutes(2))));
        when(repository.latest()).thenReturn(Flux.just(open(NOW.minusMinutes(10))));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(repository).close(7L, HoldOutcome.CANCELLED, NOW);
        verify(commands, never()).send(anyLong(), any(), anyString());
    }

    @Test
    void should_leave_a_unit_off_when_the_windows_close_too_late() {
        rooms(new HeatingRoom("office", new RoomWindow("closed", null, false)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, true, NOW, null)));
        when(repository.latest()).thenReturn(Flux.just(open(NOW.minusMinutes(61))));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(repository).close(7L, HoldOutcome.EXPIRED, NOW);
        verify(commands, never()).send(anyLong(), any(), anyString());
    }

    //a room heating-service does not answer is never held
    @Test
    void should_never_stop_a_unit_of_a_room_heating_service_does_not_know() {
        rooms(new HeatingRoom("bedroom", new RoomWindow("open", OPENED, true)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), anyString());
        verify(repository, never()).insert(anyLong(), any(), any(), any());
    }

    //one unit that fails neither stops nor cuts the others
    @Test
    void should_judge_every_unit_apart() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, LIVIA)));
        rooms(new HeatingRoom("office", new RoomWindow("open", OPENED, true)),
            new HeatingRoom("livia", new RoomWindow("open", OPENED, true)));
        when(store.of(anyLong())).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));
        when(repository.insert(eq(140L), any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("db")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(eq(140L), any(), anyString());
        verify(commands).send(141, WindowWatch.OFF, "window");
    }

    //an unreachable unit last known on is not sent the stop every minute
    @Test
    void should_not_stop_again_a_unit_that_does_not_answer() {
        rooms(new HeatingRoom("office", new RoomWindow("open", OPENED, true)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, false, NOW, NOW.minusMinutes(2))));
        when(repository.latest()).thenReturn(Flux.just(open(NOW.minusMinutes(5))));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(commands, never()).send(anyLong(), any(), anyString());
    }

    //the opening to the microsecond, as the column keeps it: read back it is the same opening
    @Test
    void should_keep_the_opening_to_the_microsecond() {
        final LocalDateTime fine = LocalDateTime.of(2026, 11, 7, 18, 26, 0, 123456789);
        rooms(new HeatingRoom("office", new RoomWindow("open", fine, true)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(COOLING, true, NOW, null)));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(repository).insert(140, LocalDateTime.of(2026, 11, 7, 18, 26, 0, 123456000), NOW, COOLING);
    }

    //the stops that ended more than 90 days ago are deleted with every pass, a failure changes nothing else
    @Test
    void should_forget_the_stops_that_ended_long_ago() {
        rooms(new HeatingRoom("office", new RoomWindow("closed", null, false)));
        when(repository.deleteClosedBefore(any())).thenReturn(Mono.error(new IllegalStateException("db")));

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(repository).deleteClosedBefore(NOW.minusDays(90));
    }

    @Test
    void should_wait_for_the_registry() {
        when(registry.current()).thenReturn(Optional.empty());

        watch.watchOnce().as(StepVerifier::create).verifyComplete();

        verify(heating, never()).getRooms();
    }

    @Test
    void should_restore_what_the_unit_was_doing_switched_on() {
        assertThat(WindowWatch.restoreOf(COOLING))
            .isEqualTo(new CommandRequest(true, Mode.COOL, 24, FanSpeed.AUTO, 0, 2, false, true, false, null));
    }

    private void rooms(final HeatingRoom... rooms) {
        when(heating.getRooms()).thenReturn(Mono.just(List.of(rooms)));
    }

    private static WindowHold open(final LocalDateTime heldSince) {
        return new WindowHold(7L, 140, OPENED, heldSince, COOLING, null, null);
    }
}
