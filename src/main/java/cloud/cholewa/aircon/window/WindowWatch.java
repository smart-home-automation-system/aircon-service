package cloud.cholewa.aircon.window;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.command.CommandService;
import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.aircon.window.WindowHoldEvaluator.Action;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.home.model.RoomName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

//An open window stops the unit of its room (HAS-384). Every minute the rooms of heating-service are read - its window
//rule, the one that holds the heaters - and every unit of the registry is judged by WindowHoldEvaluator against its last
//stop in aircon_window_hold: a unit on in a room whose window holds is switched off (source window), what it was doing
//kept in a new row first; the windows closed within restore-within set it back as it was (source window), later it
//stays off; a change of the remote or the dashboard while it is held gives the restore up. A room heating-service does
//not answer, a window unknown or no sensor never stops a unit; a failed read of heating-service stops nothing and sets
//nothing back (a WARN at the first failure, an INFO when it answers again). Every unit is judged apart: one that fails
//neither stops nor cuts the others
@Slf4j
@Component
@RequiredArgsConstructor
public class WindowWatch {

    //below the delay of a minute: five units, each a command of up to 9 s when it does not answer
    static final Duration LIMIT = Duration.ofSeconds(55);

    //how long a stop that ended is kept - the history of what the windows did
    static final Duration KEPT = Duration.ofDays(90);

    static final CommandRequest OFF = new CommandRequest(false, null, null, null, null, null, null, null, null, null);

    private final UnitRegistry unitRegistry;
    private final HeatingClient heatingClient;
    private final WindowHoldRepository repository;
    private final WindowStates windowStates;
    private final StateStore stateStore;
    private final CommandService commandService;
    private final WindowProperties properties;
    private final Clock clock;

    private final AtomicBoolean heatingFailing = new AtomicBoolean();

    //a minute in: the registry is read and every unit was read by the tracker at least once
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> watch() {
        return ScheduledPass.bounded("air conditioner windows", LIMIT, this::watchOnce);
    }

    Mono<Void> watchOnce() {
        //before the registry was read there is no unit to judge
        return Mono.justOrEmpty(unitRegistry.current())
            .flatMap(units -> windows()
                .flatMap(windows -> repository.latest()
                    .collectMap(WindowHold::unitId, Function.identity())
                    .flatMap(latest -> {
                        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
                        rememberOpen(latest);
                        return Flux.fromIterable(units)
                            .concatMap(unit -> judge(unit, windows, latest.get(unit.id()), now))
                            .then(Mono.defer(() -> forgetOld(now)));
                    })))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error("The window pass failed: {}",
                failure.toString())));
    }

    //the window of every room heating-service answers, by the word of the room; none known when it does not answer
    private Mono<Map<String, RoomWindow>> windows() {
        return heatingClient.getRooms()
            .map(WindowWatch::byRoom)
            .doOnNext(read -> {
                windowStates.rooms(read);
                if (heatingFailing.getAndSet(false)) {
                    log.info("heating-service answers again: the windows are known");
                }
            })
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                windowStates.unknown();
                if (!heatingFailing.getAndSet(true)) {
                    log.warn("The windows could not be read from heating-service, none stops or sets back a unit: {}",
                        failure.getMessage());
                }
                return Collections.<String, RoomWindow>emptyMap();
            }));
    }

    private static Map<String, RoomWindow> byRoom(final List<HeatingRoom> rooms) {
        return rooms.stream()
            .filter(room -> room != null && room.name() != null && room.window() != null)
            .collect(Collectors.toMap(HeatingRoom::name, room -> micros(room.window()), (first, second) -> first));
    }

    //openSince to the microsecond, as window_open_since keeps it - so that the opening of a stop read back is the same
    //opening (PostgreSQL rounds a finer time, and the stop would be taken for a new opening)
    static RoomWindow micros(final RoomWindow window) {
        return new RoomWindow(window.state(),
            Optional.ofNullable(window.openSince()).map(at -> at.truncatedTo(ChronoUnit.MICROS)).orElse(null),
            window.hold());
    }

    private void rememberOpen(final Map<Long, WindowHold> latest) {
        latest.values().forEach(hold -> {
            if (hold.open()) {
                windowStates.held(hold.unitId(), hold.heldSince().plus(properties.restoreWithin()));
            } else {
                windowStates.released(hold.unitId());
            }
        });
    }

    private Mono<Void> judge(final AirConditioner unit, final Map<String, RoomWindow> windows, final WindowHold latest,
                             final LocalDateTime now) {
        return Mono.defer(() -> {
            final RoomWindow window = Optional.ofNullable(unit.room()).map(RoomName::getValue).map(windows::get)
                .orElse(null);
            final Optional<UnitState> state = stateStore.of(unit.id());
            final UnitStatus status = state.map(UnitState::status).orElse(null);
            final Action action = WindowHoldEvaluator.decide(window,
                new WindowHoldEvaluator.Unit(status, state.map(UnitState::reachable).orElse(null)), latest,
                stateStore.lastChange(unit.id()).orElse(null), now, properties.restoreWithin());
            return act(action, unit, window, status, latest, now);
        }).onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
            "The window of air conditioner {} could not be acted on: {}", unit.id(), failure.toString())));
    }

    private Mono<Void> act(final Action action, final AirConditioner unit, final RoomWindow window,
                           final UnitStatus status, final WindowHold latest, final LocalDateTime now) {
        return switch (action) {
            case NONE -> Mono.empty();
            case HOLD -> hold(unit, window, status, now);
            case STOP_AGAIN -> Mono.fromRunnable(() -> log.info(
                    "Air conditioner {} is on while the window of {} holds it, switched off again", unit.id(),
                    unit.room().getValue()))
                .then(Mono.defer(() -> stop(unit)));
            case RESTORE -> restore(unit, latest, now);
            case EXPIRE -> close(latest, HoldOutcome.EXPIRED, now).doOnSuccess(done -> log.info(
                "Air conditioner {} stays off: the window of {} closed later than {} after the stop", unit.id(),
                unit.room().getValue(), properties.restoreWithin()));
            case CANCEL -> close(latest, HoldOutcome.CANCELLED, now).doOnSuccess(done -> log.info(
                "Air conditioner {} was changed by {} while the window held it: it is not set back", unit.id(),
                stateStore.lastChange(unit.id()).map(change -> change.source().getValue()).orElse("?")));
        };
    }

    //the stops that ended more than KEPT ago, deleted - a failure is a WARN, tried again next minute
    private Mono<Void> forgetOld(final LocalDateTime now) {
        return repository.deleteClosedBefore(now.minus(KEPT))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The old stops by a window could not be deleted: {}", failure.toString())));
    }

    //the row first, then the command: a stop whose command failed is sent again next minute, never forgotten
    private Mono<Void> hold(final AirConditioner unit, final RoomWindow window, final UnitStatus status,
                            final LocalDateTime now) {
        return repository.insert(unit.id(), window.openSince(), now, status)
            .doOnSuccess(done -> {
                windowStates.held(unit.id(), now.plus(properties.restoreWithin()));
                log.info("Air conditioner {} switched off: a window of {} open since {}", unit.id(),
                    unit.room().getValue(), window.openSince());
            })
            .then(Mono.defer(() -> stop(unit)));
    }

    private Mono<Void> stop(final AirConditioner unit) {
        return commandService.send(unit.id(), OFF, ChangeSource.WINDOW.getValue())
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "Air conditioner {} could not be switched off for the window, tried again next minute: {}", unit.id(),
                failure.getMessage())));
    }

    //a refused restore is closed - another attempt would be refused the same; one the unit did not answer is tried again
    //next minute, until restore-within
    private Mono<Void> restore(final AirConditioner unit, final WindowHold hold, final LocalDateTime now) {
        return commandService.send(unit.id(), restoreOf(hold.before()), ChangeSource.WINDOW.getValue())
            .then(Mono.defer(() -> close(hold, HoldOutcome.RESTORED, now)))
            .doOnSuccess(done -> log.info("Air conditioner {} set back as it was: the windows of {} closed", unit.id(),
                unit.room().getValue()))
            .onErrorResume(WindowWatch::refusedByTheRules, refused -> close(hold, HoldOutcome.REFUSED, now)
                .doOnSuccess(done -> log.warn("Air conditioner {} is not set back: {}", unit.id(), refused.getMessage())))
            .onErrorResume(AirconException.class, failed -> Mono.fromRunnable(() -> log.warn(
                "Air conditioner {} could not be set back, tried again next minute: {}", unit.id(), failed.getMessage())));
    }

    private static boolean refusedByTheRules(final Throwable failure) {
        return failure instanceof AirconException refused && refused.getError() == AirconError.INVALID_COMMAND;
    }

    private Mono<Void> close(final WindowHold hold, final HoldOutcome outcome, final LocalDateTime now) {
        return repository.close(Objects.requireNonNull(hold.id()), outcome, now)
            .doOnSuccess(done -> windowStates.released(hold.unitId()));
    }

    //what the unit was doing before the stop, switched on; the display is left as it is
    static CommandRequest restoreOf(final UnitStatus before) {
        return new CommandRequest(true, before.mode(), before.targetTemperature(), before.fanSpeed(),
            before.swingVertical(), before.swingHorizontal(), before.quiet(), before.turbo(), before.sleep(), null);
    }
}
