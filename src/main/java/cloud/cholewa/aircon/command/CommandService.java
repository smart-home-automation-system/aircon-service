package cloud.cholewa.aircon.command;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.api.model.RoomCommandReply;
import cloud.cholewa.aircon.driver.GreeClient;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.StateTracker;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.commons.error.model.ErrorId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

//The commands of the air conditioners (HAS-382). A command is judged by CommandRules against what the unit will have
//after it, sent once (a unit that does not answer after the retries of the driver is DEVICE_FAILED - it may have taken
//it all the same), then confirmed by a read confirm-after later: taken, it is lastCommandConfirmed; not taken, it is sent
//once more and read again, and never a third time before the next command. The change the read shows is written in the
//history under the sender (source). Every command and its outcome is logged
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandService {

    //who may send a command: the dashboard, and the automations of the tasks to come
    static final Set<ChangeSource> SENDERS = Set.of(ChangeSource.DASHBOARD, ChangeSource.RULE, ChangeSource.WINDOW,
        ChangeSource.EMPTY_HOUSE, ChangeSource.GRID_OUTAGE);

    private final UnitRegistry unitRegistry;
    private final StateStore stateStore;
    private final StateTracker stateTracker;
    private final GreeClient greeClient;
    private final CommandOutcomes commandOutcomes;
    private final CommandProperties properties;
    private final Clock clock;

    //a command to one unit; completes once the unit took the bytes - the confirmation follows on its own
    public Mono<Void> send(final long id, final CommandRequest command, final String source) {
        return senderOf(source)
            .flatMap(sender -> registered()
                .flatMap(units -> Mono.justOrEmpty(units.stream().filter(unit -> unit.id() == id).findFirst()))
                .switchIfEmpty(Mono.error(() -> AirconException.airconNotFound(id)))
                .flatMap(unit -> sendTo(unit, command, sender)));
    }

    //the same command to every unit of a room, one after the other; answers what each came to
    public Mono<List<RoomCommandReply>> sendToRoom(final String room, final CommandRequest command, final String source) {
        return senderOf(source)
            .flatMap(sender -> registered()
                .map(units -> units.stream()
                    .filter(unit -> unit.room() != null && unit.room().getValue().equals(room))
                    .toList())
                .filter(units -> !units.isEmpty())
                .switchIfEmpty(Mono.error(AirconException::roomNotFound))
                .flatMapMany(Flux::fromIterable)
                .concatMap(unit -> sendTo(unit, command, sender)
                    .thenReturn(new RoomCommandReply(unit.id(), true, null))
                    .onErrorResume(AirconException.class, refused -> Mono.just(
                        new RoomCommandReply(unit.id(), false, ErrorId.codeOf(refused.getError())))))
                .collectList());
    }

    private Mono<Void> sendTo(final AirConditioner unit, final CommandRequest command, final ChangeSource sender) {
        return Mono.defer(() -> {
            final Map<String, Integer> wire = CommandRules.wire(command);
            return Mono.justOrEmpty(CommandRules.problem(command,
                    stateStore.of(unit.id()).map(UnitState::status).orElse(null)))
                .flatMap(reason -> Mono.<Void>error(AirconException.invalidCommand(reason)))
                .then(Mono.fromRunnable(() -> log.info("Command {} to air conditioner {} by {}", wire, unit.id(),
                    sender.getValue())))
                .then(Mono.defer(() -> deliver(unit, wire, sender)))
                .then(Mono.fromSupplier(() -> commandOutcomes.issue(unit.id())))
                .doOnNext(number -> confirmLater(unit, command, wire, sender, number))
                .then();
        });
    }

    private Mono<Void> deliver(final AirConditioner unit, final Map<String, Integer> wire, final ChangeSource sender) {
        return greeClient.command(unit, wire)
            .doOnSuccess(sent -> commandOutcomes.expect(unit.id(), sender,
                LocalDateTime.now(clock).plus(properties.expectFor())))
            .onErrorMap(failure -> !(failure instanceof AirconException), failure -> {
                log.warn("Command {} to air conditioner {} failed: {}", wire, unit.id(), failure.getMessage());
                return AirconException.deviceFailed("Air conditioner " + unit.id() + " did not take the command");
            });
    }

    //the read that confirms a command runs apart from the request that sent it; whatever it meets is logged
    private void confirmLater(final AirConditioner unit, final CommandRequest command, final Map<String, Integer> wire,
                              final ChangeSource sender, final long number) {
        confirm(unit, command, wire, sender, number, true)
            .subscribe(
                taken -> { },
                failure -> log.warn("The command to air conditioner {} could not be confirmed: {}", unit.id(),
                    failure.toString()));
    }

    private Mono<Boolean> confirm(final AirConditioner unit, final CommandRequest command,
                                  final Map<String, Integer> wire, final ChangeSource sender, final long number,
                                  final boolean first) {
        return Mono.delay(properties.confirmAfter())
            .then(stateTracker.readNow(unit))
            .map(read -> CommandRules.takenBy(command, read))
            .onErrorReturn(false)
            .flatMap(taken -> Boolean.TRUE.equals(taken) || !first
                ? Mono.just(taken).doOnNext(outcome -> concluded(unit, wire, number, outcome))
                : Mono.fromRunnable(() -> log.info("Command {} to air conditioner {} not taken, sent once more", wire,
                        unit.id()))
                    .then(Mono.defer(() -> deliver(unit, wire, sender)))
                    .then(Mono.defer(() -> confirm(unit, command, wire, sender, number, false)))
                    .onErrorResume(failure -> Mono.just(false).doOnNext(outcome -> concluded(unit, wire, number, false))));
    }

    private void concluded(final AirConditioner unit, final Map<String, Integer> wire, final long number,
                           final boolean taken) {
        commandOutcomes.confirmed(unit.id(), number, taken);
        if (taken) {
            log.info("Command {} to air conditioner {} confirmed", wire, unit.id());
        } else {
            log.warn("Command {} to air conditioner {} not taken after a second attempt", wire, unit.id());
        }
    }

    private Mono<ChangeSource> senderOf(final String source) {
        return Mono.justOrEmpty(Arrays.stream(ChangeSource.values())
                .filter(SENDERS::contains)
                .filter(sender -> sender.getValue().equals(source))
                .findFirst())
            .switchIfEmpty(Mono.error(() -> AirconException.invalidCommand(
                "The source of a command is dashboard, rule, window, empty-house or grid-outage")));
    }

    private Mono<List<AirConditioner>> registered() {
        return Mono.justOrEmpty(unitRegistry.current())
            .switchIfEmpty(Mono.error(AirconException::registryUnavailable));
    }
}
