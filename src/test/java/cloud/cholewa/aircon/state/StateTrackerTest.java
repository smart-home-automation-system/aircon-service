package cloud.cholewa.aircon.state;

import cloud.cholewa.aircon.driver.GreeClient;
import cloud.cholewa.aircon.driver.GreeTimeoutException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StateTrackerTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T12:15:00Z"), HOUSE);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");
    private static final AirConditioner BEDROOM = new AirConditioner(143, "Air conditioner", RoomName.BEDROOM,
        "192.0.2.240", "02005e0000a4");
    private static final UnitStatus OFF = new UnitStatus(false, Mode.COOL, 24, 22, FanSpeed.AUTO, 0, 0, false, false,
        false, true, "hid");
    private static final UnitStatus ON = new UnitStatus(true, Mode.COOL, 24, 22, FanSpeed.AUTO, 0, 0, false, false,
        false, true, "hid");

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final GreeClient greeClient = mock(GreeClient.class);
    private final AirconStateRepository repository = mock(AirconStateRepository.class);
    private final StateStore store = new StateStore();
    private final StateTracker tracker = new StateTracker(registry, greeClient, store, repository, CLOCK);

    @BeforeEach
    void setUp() {
        when(repository.currentStates()).thenReturn(Flux.empty());
        when(repository.recordState(anyLong(), any(), any())).thenReturn(Mono.empty());
        when(repository.recordChange(anyLong(), any(), any(), any())).thenReturn(Mono.empty());
    }

    //the first read of a unit is its state, its current row and the start of its history
    @Test
    void should_store_the_first_read_of_a_unit() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(greeClient.status(OFFICE)).thenReturn(Mono.just(OFF));

        tracker.track().as(StepVerifier::create).verifyComplete();

        assertThat(store.of(140)).contains(new UnitState(OFF, true, NOW));
        verify(repository).recordState(140, OFF, NOW);
        verify(repository).recordChange(140, OFF, ChangeSource.FIRST_READ, NOW);
    }

    //the same state again moves the current row only; what the remote changed is a change of the remote
    @Test
    void should_record_a_change_made_with_the_remote() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(greeClient.status(OFFICE)).thenReturn(Mono.just(OFF), Mono.just(OFF), Mono.just(ON));

        tracker.track().as(StepVerifier::create).verifyComplete();
        tracker.track().as(StepVerifier::create).verifyComplete();
        tracker.track().as(StepVerifier::create).verifyComplete();

        verify(repository, times(3)).recordState(anyLong(), any(), any());
        verify(repository).recordChange(140, OFF, ChangeSource.FIRST_READ, NOW);
        verify(repository).recordChange(140, ON, ChangeSource.REMOTE, NOW);
    }

    //a room temperature that moved is no change of what the unit does
    @Test
    void should_not_count_the_room_temperature_as_a_change() {
        final UnitStatus warmer = new UnitStatus(false, Mode.COOL, 24, 23, FanSpeed.AUTO, 0, 0, false, false, false,
            true, "hid");

        assertThat(StateTracker.changeOf(OFF, warmer)).isEmpty();
        assertThat(StateTracker.changeOf(OFF, ON)).contains(ChangeSource.REMOTE);
        assertThat(StateTracker.changeOf(null, OFF)).contains(ChangeSource.FIRST_READ);
    }

    //at a start the stored rows are the state - not known to be reachable - and a change after them is the remote's
    @Test
    void should_restore_the_stored_state_before_the_first_read() {
        when(repository.currentStates()).thenReturn(Flux.just(new StoredState(140, OFF, NOW.minusHours(1))));
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(greeClient.status(OFFICE)).thenReturn(Mono.error(new GreeTimeoutException("192.0.2.59", 3)),
            Mono.just(ON));

        tracker.restore().as(StepVerifier::create).verifyComplete();
        assertThat(store.of(140)).contains(new UnitState(OFF, null, NOW.minusHours(1)));

        tracker.track().as(StepVerifier::create).verifyComplete();
        assertThat(store.of(140)).contains(new UnitState(OFF, false, NOW.minusHours(1)));

        tracker.track().as(StepVerifier::create).verifyComplete();
        verify(repository).recordChange(140, ON, ChangeSource.REMOTE, NOW);
        verify(repository, times(1)).currentStates();
    }

    //a unit that stops answering keeps what it reported, unreachable; the others are read all the same
    @Test
    void should_keep_what_a_silent_unit_reported() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, BEDROOM)));
        when(greeClient.status(OFFICE)).thenReturn(Mono.just(OFF), Mono.error(new GreeTimeoutException("192.0.2.59", 3)));
        when(greeClient.status(BEDROOM)).thenReturn(Mono.just(ON));

        tracker.track().as(StepVerifier::create).verifyComplete();
        tracker.track().as(StepVerifier::create).verifyComplete();

        assertThat(store.of(140)).contains(new UnitState(OFF, false, NOW));
        assertThat(store.of(143)).contains(new UnitState(ON, true, NOW));
    }

    //a unit never read that does not answer is unreachable, and nothing else is known of it
    @Test
    void should_know_nothing_of_a_unit_never_read() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(greeClient.status(OFFICE)).thenReturn(Mono.error(new GreeTimeoutException("192.0.2.59", 3)));

        tracker.track().as(StepVerifier::create).verifyComplete();

        assertThat(store.of(140)).contains(new UnitState(null, false, null));
        verify(repository, never()).recordState(anyLong(), any(), any());
    }

    //the database down: the state in memory holds
    @Test
    void should_keep_the_state_when_the_database_fails() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)));
        when(greeClient.status(OFFICE)).thenReturn(Mono.just(OFF));
        when(repository.recordState(anyLong(), any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));

        tracker.track().as(StepVerifier::create).verifyComplete();

        assertThat(store.of(140)).contains(new UnitState(OFF, true, NOW));
    }

    //the unit read longest ago first: a pass cut by its bound reads the others first the next time
    @Test
    void should_read_the_unit_read_longest_ago_first() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE)), Optional.of(List.of(OFFICE, BEDROOM)));
        when(greeClient.status(any())).thenReturn(Mono.just(OFF));

        tracker.track().as(StepVerifier::create).verifyComplete();
        tracker.track().as(StepVerifier::create).verifyComplete();

        final InOrder order = inOrder(greeClient);
        order.verify(greeClient).status(OFFICE);
        order.verify(greeClient).status(BEDROOM);
        order.verify(greeClient).status(OFFICE);
    }

    @Test
    void should_read_nothing_while_the_registry_is_not_known() {
        when(registry.current()).thenReturn(Optional.empty());

        tracker.track().as(StepVerifier::create).verifyComplete();

        verify(greeClient, never()).status(any());
    }
}
