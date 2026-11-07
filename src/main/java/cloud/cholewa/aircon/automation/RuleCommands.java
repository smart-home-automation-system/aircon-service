package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.command.CommandRules;
import cloud.cholewa.aircon.command.CommandService;
import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.state.ChangeSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

//An automatic command (HAS-385): sent through CommandService under its source, and logged in aircon_rule_run with what
//it came to - for every sender of the service itself (the window, the empty house, a lost grid, the rules to come).
//Completes and fails as the command does; a row that could not be written is a WARN and changes nothing
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleCommands {

    private final CommandService commandService;
    private final RuleRunRepository repository;
    private final Clock clock;

    public Mono<Void> send(final long unitId, final CommandRequest command, final ChangeSource source,
                           final LocalDateTime episode) {
        return Mono.defer(() -> commandService.send(unitId, command, source.getValue())
            .then(Mono.defer(() -> log(unitId, command, source, episode, RunResult.SENT)))
            .onErrorResume(failure -> log(unitId, command, source, episode, resultOf(failure))
                .then(Mono.error(failure))));
    }

    private static RunResult resultOf(final Throwable failure) {
        return failure instanceof AirconException refused && refused.getError() == AirconError.INVALID_COMMAND
            ? RunResult.REFUSED
            : RunResult.FAILED;
    }

    private Mono<Void> log(final long unitId, final CommandRequest command, final ChangeSource source,
                           final LocalDateTime episode, final RunResult result) {
        return Mono.defer(() -> repository.insert(new RuleRun(
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS), source, unitId,
                episode == null ? null : episode.truncatedTo(ChronoUnit.MICROS),
                CommandRules.wire(command).toString(), result)))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The {} command to air conditioner {} could not be logged: {}", source.getValue(), unitId,
                failure.toString())));
    }
}
