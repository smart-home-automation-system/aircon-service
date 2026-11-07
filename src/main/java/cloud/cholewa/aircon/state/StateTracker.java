package cloud.cholewa.aircon.state;

import cloud.cholewa.aircon.command.CommandOutcomes;
import cloud.cholewa.aircon.command.CommandProperties;
import cloud.cholewa.aircon.command.CommandRules;
import cloud.cholewa.aircon.driver.GreeClient;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

//The state of every unit (HAS-381): each unit of the registry read every 30 s, one after the other, the one read longest
//ago first - a pass cut by its bound reads the others first the next time. A read that answered is the state in memory,
//the current row of aircon_state (moved only when a value changed) and, when the power, the mode, the target or the fan
//changed, a row of aircon_state_change - under the sender of a command the read is expected to show (HAS-382), else
//the remote's; the first read of a unit starts its history. A unit cooling below 22 C - set with the remote or the Gree
//app - is raised to 22 C within the same pass (the guard of HAS-382; the change then written as minimum-restored). A read that did not answer leaves what the unit reported before, marked unreachable - a WARN when
//a unit stops answering, an INFO when it answers again, never one a pass.
//At the start, before the first read, the rows of aircon_state are the state - not known to be reachable until read. A
//failed write to the database is a WARN: the state in memory holds, the next change is written whole
@Slf4j
@Component
@RequiredArgsConstructor
public class StateTracker {

    //below the delay of 30 s: five units, each up to three attempts of 3 s when it does not answer
    static final Duration LIMIT = Duration.ofSeconds(25);

    private final UnitRegistry unitRegistry;
    private final GreeClient greeClient;
    private final StateStore stateStore;
    private final AirconStateRepository repository;
    private final Clock clock;
    private final CommandOutcomes commandOutcomes;
    private final CommandProperties commandProperties;
    //the target of the minimum in cooling, in degrees C (TemUn 0)
    private static final Map<String, Integer> RAISE_TO_MINIMUM = Map.of("SetTem", CommandRules.COOLING_MIN, "TemUn", 0);

    private final AtomicBoolean restored = new AtomicBoolean();
    private final AtomicBoolean restoreFailedBefore = new AtomicBoolean();
    private final Map<Long, LocalDateTime> attempted = new ConcurrentHashMap<>();

    //the first pass after the read of the registry and the binds of the start (initial delay 0)
    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT30S")
    Mono<Void> track() {
        return ScheduledPass.bounded("air conditioner state", LIMIT, () -> restore()
            .thenMany(Flux.defer(() -> Flux.fromIterable(oldestReadFirst())))
            .concatMap(this::read)
            .then());
    }

    Mono<Void> restore() {
        return Mono.defer(() -> restored.get()
            ? Mono.<Void>empty()
            : repository.currentStates()
                .doOnNext(stateStore::restore)
                .count()
                .flatMap(count -> repository.lastChanges()
                    .doOnNext(change -> stateStore.restoreChange(change.unitId(), change.source(), change.at()))
                    .then(Mono.just(count)))
                .doOnNext(count -> {
                    restored.set(true);
                    log.info("The state of {} air conditioner(s) restored from the database", count);
                })
                .then()
                .onErrorResume(failure -> Mono.fromRunnable(() -> restoreFailed(failure))));
    }

    //a WARN at the first failure, not one every 30 s while the database is down
    private void restoreFailed(final Throwable failure) {
        if (!restoreFailedBefore.getAndSet(true)) {
            log.warn("The state of the air conditioners could not be restored, tried again at every pass: {}",
                failure.toString());
        }
    }

    private List<AirConditioner> oldestReadFirst() {
        return unitRegistry.current().orElse(List.of()).stream()
            .sorted(Comparator.comparing(unit -> attempted.getOrDefault(unit.id(), LocalDateTime.MIN)))
            .toList();
    }

    private Mono<Void> read(final AirConditioner unit) {
        return readNow(unit).then().onErrorResume(failure -> Mono.empty());
    }

    //a read of a unit now, out of the pass too - the confirmation of a command (HAS-382): its status recorded as every
    //read is; a unit that does not answer is unreachable, and the failure is passed on
    public Mono<UnitStatus> readNow(final AirConditioner unit) {
        return Mono.defer(() -> {
            final LocalDateTime at = LocalDateTime.now(clock);
            attempted.put(unit.id(), at);
            return greeClient.status(unit)
                .flatMap(status -> answered(unit, status, at).then(guard(unit, status)).thenReturn(status))
                .onErrorResume(failure -> Mono.fromRunnable(() -> unanswered(unit, failure, at)).then(Mono.error(failure)));
        });
    }

    private Mono<Void> answered(final AirConditioner unit, final UnitStatus status, final LocalDateTime at) {
        final Optional<UnitState> before = stateStore.answered(unit.id(), status, at);
        if (before.map(UnitState::reachable).filter(Boolean.FALSE::equals).isPresent()) {
            log.info("Air conditioner {} ({}) answers again", unit.id(), unit.mac());
        }
        final Optional<ChangeSource> change = changeOf(before.map(UnitState::status).orElse(null), status)
            .map(source -> source == ChangeSource.REMOTE
                ? commandOutcomes.takePending(unit.id(), at).orElse(ChangeSource.REMOTE)
                : source);
        change.ifPresent(source -> stateStore.changed(unit.id(), source, at));
        return repository.recordState(unit.id(), status, at)
            .then(Mono.justOrEmpty(change).flatMap(source -> repository.recordChange(unit.id(), status, source, at)))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The state of air conditioner {} could not be stored, it stays in memory: {}", unit.id(),
                failure.toString())));
    }

    //cooling below the minimum - the remote or the Gree app can set 16 C: the target written back to 22 C at once, the
    //change of the next read written as minimum-restored. Only while the unit is on: a target of a unit that is off
    //cools nothing, and the remote cannot set one then
    private Mono<Void> guard(final AirConditioner unit, final UnitStatus status) {
        return Mono.just(status)
            .filter(StateTracker::coolsBelowMinimum)
            .flatMap(below -> greeClient.command(unit, RAISE_TO_MINIMUM)
                .then(Mono.fromRunnable(() -> raised(unit, below))))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "Air conditioner {} cools at {} C and could not be raised to {} C: {}", unit.id(),
                status.targetTemperature(), CommandRules.COOLING_MIN, failure.getMessage())))
            .then();
    }

    private void raised(final AirConditioner unit, final UnitStatus below) {
        final LocalDateTime at = LocalDateTime.now(clock);
        commandOutcomes.expect(unit.id(), ChangeSource.MINIMUM_RESTORED, at.plus(commandProperties.expectFor()));
        commandOutcomes.minimumRestored(unit.id(), at);
        log.info("Air conditioner {} cooled at {} C, raised to the minimum of {} C", unit.id(),
            below.targetTemperature(), CommandRules.COOLING_MIN);
    }

    static boolean coolsBelowMinimum(final UnitStatus status) {
        return Boolean.TRUE.equals(status.power()) && status.mode() == Mode.COOL && status.targetTemperature() != null
            && status.targetTemperature() < CommandRules.COOLING_MIN;
    }

    private void unanswered(final AirConditioner unit, final Throwable failure, final LocalDateTime at) {
        final boolean wasAnswering = stateStore.of(unit.id()).map(UnitState::reachable).filter(Boolean.FALSE::equals)
            .isEmpty();
        stateStore.unanswered(unit.id(), at);
        if (wasAnswering) {
            log.warn("Air conditioner {} ({}) does not answer: {}", unit.id(), unit.mac(), failure.getMessage());
        }
    }

    //the first read of a unit starts its history; after it, a change of what the unit does is the remote's
    static Optional<ChangeSource> changeOf(final UnitStatus before, final UnitStatus now) {
        if (before == null) {
            return Optional.of(ChangeSource.FIRST_READ);
        }
        final boolean changed = !Objects.equals(before.power(), now.power())
            || before.mode() != now.mode()
            || !Objects.equals(before.targetTemperature(), now.targetTemperature())
            || before.fanSpeed() != now.fanSpeed();
        return changed ? Optional.of(ChangeSource.REMOTE) : Optional.empty();
    }
}
