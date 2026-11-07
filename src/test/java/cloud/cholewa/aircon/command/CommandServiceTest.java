package cloud.cholewa.aircon.command;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.api.model.RoomCommandReply;
import cloud.cholewa.aircon.driver.GreeClient;
import cloud.cholewa.aircon.driver.GreeTimeoutException;
import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.StateTracker;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T13:20:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final AirConditioner OFFICE = new AirConditioner(140, "Air conditioner", RoomName.OFFICE,
        "192.0.2.59", "02005e0000a1");
    private static final AirConditioner LIVIA = new AirConditioner(141, "Air conditioner", RoomName.LIVIA,
        "192.0.2.56", "02005e0000a2");
    private static final UnitStatus OFF = new UnitStatus(false, Mode.COOL, 24, 22, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);
    private static final UnitStatus ON = new UnitStatus(true, Mode.COOL, 24, 22, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);
    private static final CommandRequest POWER_ON = new CommandRequest(true, null, null, null, null, null, null, null,
        null, null);

    private final UnitRegistry registry = mock(UnitRegistry.class);
    private final StateStore store = mock(StateStore.class);
    private final StateTracker tracker = mock(StateTracker.class);
    private final GreeClient greeClient = mock(GreeClient.class);
    private final CommandOutcomes outcomes = new CommandOutcomes();
    private final CommandService service = new CommandService(registry, store, tracker, greeClient, outcomes,
        new CommandProperties(Duration.ofMillis(10), Duration.ofSeconds(60)), CLOCK);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, LIVIA)));
        when(store.of(140)).thenReturn(Optional.of(new UnitState(OFF, true, LocalDateTime.now(CLOCK))));
        when(store.of(141)).thenReturn(Optional.of(new UnitState(OFF, true, LocalDateTime.now(CLOCK))));
    }

    //sent once, then read back: taken, it is confirmed, and the change of the read is the sender's
    @Test
    void should_send_a_command_and_confirm_it() {
        when(greeClient.command(any(), any())).thenReturn(Mono.empty());
        when(tracker.readNow(OFFICE)).thenReturn(Mono.just(ON));

        service.send(140, POWER_ON, "dashboard").as(StepVerifier::create).verifyComplete();

        verify(greeClient).command(OFFICE, Map.of("Pow", 1));
        waitForTheConfirmationOf(140);
        assertThat(outcomes.lastCommandConfirmed(140)).contains(true);
        assertThat(outcomes.takePending(140, LocalDateTime.now(CLOCK))).contains(ChangeSource.DASHBOARD);
    }

    //not taken: sent once more, and not a third time - the command is not confirmed
    @Test
    void should_send_an_unconfirmed_command_once_more_and_never_a_third_time() {
        when(greeClient.command(any(), any())).thenReturn(Mono.empty());
        when(tracker.readNow(OFFICE)).thenReturn(Mono.just(OFF));

        service.send(140, POWER_ON, "dashboard").as(StepVerifier::create).verifyComplete();

        waitForTheConfirmationOf(140);
        assertThat(outcomes.lastCommandConfirmed(140)).contains(false);
        verify(greeClient, times(2)).command(OFFICE, Map.of("Pow", 1));
        verify(tracker, times(2)).readNow(OFFICE);
    }

    @Test
    void should_take_the_source_of_an_automation() {
        when(greeClient.command(any(), any())).thenReturn(Mono.empty());
        when(tracker.readNow(OFFICE)).thenReturn(Mono.just(ON));

        service.send(140, POWER_ON, "window").as(StepVerifier::create).verifyComplete();

        assertThat(outcomes.takePending(140, LocalDateTime.now(CLOCK))).contains(ChangeSource.WINDOW);
    }

    //the source is who sends: never the remote, never the first read
    @Test
    void should_refuse_a_source_that_sends_nothing() {
        service.send(140, POWER_ON, "remote").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> {
                    assertThat(refused.getError()).isEqualTo(AirconError.INVALID_COMMAND);
                    assertThat(refused.getMessage())
                        .isEqualTo("The source of a command is dashboard, rule, window, empty-house or grid-outage");
                }))
            .verify();
        verify(greeClient, never()).command(any(), any());
    }

    //judged against what the unit has: 21 C in cooling is refused, nothing is sent
    @Test
    void should_refuse_what_the_rules_refuse() {
        service.send(140, new CommandRequest(null, null, 21, null, null, null, null, null, null, null), "dashboard")
            .as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> {
                    assertThat(refused.getError()).isEqualTo(AirconError.INVALID_COMMAND);
                    assertThat(refused.getMessage()).isEqualTo("Cooling never goes below 22 C, not 21");
                }))
            .verify();
        verify(greeClient, never()).command(any(), any());
    }

    @Test
    void should_refuse_a_unit_that_is_not_registered() {
        service.send(999, POWER_ON, "dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.NOT_FOUND_AIRCON)))
            .verify();
    }

    //no answer after every attempt of the driver: DEVICE_FAILED - it may have taken it all the same
    @Test
    void should_answer_device_failed_when_the_unit_does_not_answer() {
        when(greeClient.command(any(), any())).thenReturn(Mono.error(new GreeTimeoutException("192.0.2.59", 3)));

        service.send(140, POWER_ON, "dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> {
                    assertThat(refused.getError()).isEqualTo(AirconError.DEVICE_FAILED);
                    assertThat(refused.getMessage()).isEqualTo("Air conditioner 140 did not take the command");
                }))
            .verify();
        verify(tracker, never()).readNow(any());
    }

    //a room: every unit, one after the other, each with what it came to
    @Test
    void should_send_a_command_to_every_unit_of_a_room() {
        final AirConditioner officeTwo = new AirConditioner(145, "Air conditioner 2", RoomName.OFFICE, "192.0.2.60",
            "02005e0000a6");
        when(registry.current()).thenReturn(Optional.of(List.of(OFFICE, LIVIA, officeTwo)));
        when(store.of(145)).thenReturn(Optional.empty());
        when(greeClient.command(any(), any())).thenReturn(Mono.empty());
        when(greeClient.command(officeTwo, Map.of("Pow", 1)))
            .thenReturn(Mono.error(new GreeTimeoutException("192.0.2.60", 3)));
        when(tracker.readNow(any())).thenReturn(Mono.just(ON));

        service.sendToRoom("office", POWER_ON, "dashboard").as(StepVerifier::create)
            .expectNext(List.of(new RoomCommandReply(140, true, null), new RoomCommandReply(145, false, "DEVICE_FAILED")))
            .verifyComplete();
        verify(greeClient, never()).command(LIVIA, Map.of("Pow", 1));
    }

    @Test
    void should_refuse_a_room_without_a_unit() {
        service.sendToRoom("kitchen", POWER_ON, "dashboard").as(StepVerifier::create)
            .expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(AirconException.class,
                refused -> assertThat(refused.getError()).isEqualTo(AirconError.NOT_FOUND_ROOM)))
            .verify();
    }

    //the confirmation runs apart from the request: waited for, at most 2 s
    private void waitForTheConfirmationOf(final long unitId) {
        final long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (outcomes.lastCommandConfirmed(unitId).isEmpty() && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertThat(outcomes.lastCommandConfirmed(unitId)).as("the confirmation of the command").isPresent();
    }
}
