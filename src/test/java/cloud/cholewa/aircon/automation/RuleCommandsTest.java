package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.command.CommandService;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.state.ChangeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleCommandsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T19:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final LocalDateTime EPISODE = NOW.minusMinutes(12);
    private static final CommandRequest OFF = new CommandRequest(false, null, null, null, null, null, null, null, null,
        null);

    private final CommandService commandService = mock(CommandService.class);
    private final RuleRunRepository repository = mock(RuleRunRepository.class);
    private final RuleCommands commands = new RuleCommands(commandService, repository, CLOCK);

    @BeforeEach
    void setUp() {
        when(repository.insert(any())).thenReturn(Mono.empty());
    }

    @Test
    void should_send_under_its_source_and_log_it() {
        when(commandService.send(140, OFF, "empty-house")).thenReturn(Mono.empty());

        commands.send(140, OFF, ChangeSource.EMPTY_HOUSE, EPISODE).as(StepVerifier::create).verifyComplete();

        verify(repository).insert(new RuleRun(NOW, ChangeSource.EMPTY_HOUSE, 140, EPISODE, "{Pow=0}", RunResult.SENT));
    }

    @Test
    void should_log_a_command_the_unit_did_not_answer_and_fail() {
        when(commandService.send(140, OFF, "grid-outage"))
            .thenReturn(Mono.error(AirconException.deviceFailed("Air conditioner 140 did not take the command")));

        commands.send(140, OFF, ChangeSource.GRID_OUTAGE, null).as(StepVerifier::create)
            .expectError(AirconException.class).verify();

        verify(repository).insert(new RuleRun(NOW, ChangeSource.GRID_OUTAGE, 140, null, "{Pow=0}", RunResult.FAILED));
    }

    @Test
    void should_log_a_command_the_rules_refuse_and_fail() {
        when(commandService.send(140, OFF, "window"))
            .thenReturn(Mono.error(AirconException.invalidCommand("Cooling never goes below 22 C, not 18")));

        commands.send(140, OFF, ChangeSource.WINDOW, EPISODE).as(StepVerifier::create)
            .expectError(AirconException.class).verify();

        verify(repository).insert(new RuleRun(NOW, ChangeSource.WINDOW, 140, EPISODE, "{Pow=0}", RunResult.REFUSED));
    }

    //a row that could not be written changes nothing of the command
    @Test
    void should_complete_when_the_log_fails() {
        when(commandService.send(140, OFF, "empty-house")).thenReturn(Mono.empty());
        when(repository.insert(any())).thenReturn(Mono.error(new IllegalStateException("db")));

        commands.send(140, OFF, ChangeSource.EMPTY_HOUSE, EPISODE).as(StepVerifier::create).verifyComplete();
    }
}
