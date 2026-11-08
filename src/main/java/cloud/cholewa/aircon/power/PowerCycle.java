package cloud.cholewa.aircon.power;

import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.commons.notification.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

//The power cycle of the indoor plug (HAS-390), run by hand only - never after an alert (owner): refused while an indoor
//unit is known to be on, while the plug draws above the standby or its draw is not known, and while a cycle runs. The
//plug is switched off - answered 202 once it took it - and, outside the request and outside every bounded pass, kept off
//for cycleOffFor and switched on again: up to ON_ATTEMPTS times ON_BACKOFF apart, read back, and when it stays off an
//alert on the technical channel - the four indoor units are without power then. A cycle a restart cut off (its row still
//running) is ended at the first pass of the plugs: the plug switched on, the row failed. Every cycle is a row of
//aircon_power_cycle with who asked
@Slf4j
@Component
@RequiredArgsConstructor
public class PowerCycle {

    static final int ON_ATTEMPTS = 3;
    static final Duration ON_BACKOFF = Duration.ofSeconds(5);
    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);

    private final PlugProperties properties;
    private final PlugMeter plugMeter;
    private final PlugStore plugStore;
    private final PowerRepository repository;
    private final UnitRegistry unitRegistry;
    private final StateStore stateStore;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean recovered = new AtomicBoolean();

    //why a cycle is refused now, or nothing when it is allowed
    public Optional<String> refusal() {
        return refusalOf(LocalDateTime.now(clock)).map(Throwable::getMessage);
    }

    private Optional<AirconException> refusalOf(final LocalDateTime now) {
        if (running.get()) {
            return Optional.of(AirconException.powerCycleInProgress());
        }
        final List<AirConditioner> on = indoorUnits().stream().filter(this::knownOn).toList();
        if (!on.isEmpty()) {
            return Optional.of(AirconException.airconRunning("Air conditioner " + on.getFirst().id() + " is on"));
        }
        final Optional<PlugReading> reading = plugStore.fresh(PlugName.INDOOR, now);
        if (reading.isEmpty()) {
            return Optional.of(AirconException.airconRunning("The draw of the indoor plug is not known"));
        }
        if (reading.get().power() > properties.standbyWatts()) {
            return Optional.of(AirconException.airconRunning("The indoor plug draws " + reading.get().power()
                + " W, above the standby of " + properties.standbyWatts() + " W"));
        }
        return Optional.empty();
    }

    public Mono<PowerCycleRun> lastRun() {
        return repository.lastCycle(PlugName.INDOOR);
    }

    public Mono<Void> start(final String requestedBy) {
        return Mono.defer(() -> Mono.justOrEmpty(refusalOf(LocalDateTime.now(clock))))
            .flatMap(Mono::<Void>error)
            .then(Mono.fromCallable(() -> running.compareAndSet(false, true))
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(AirconException::powerCycleInProgress))
                .then(Mono.defer(() -> switchOff(requestedBy))));
    }

    private Mono<Void> switchOff(final String requestedBy) {
        final PlugProperties.Plug plug = properties.indoor();
        final LocalDateTime at = LocalDateTime.now(clock);
        return repository.insertCycle(PlugName.INDOOR, at, requestedBy)
            .flatMap(id -> plugMeter.switchTo(plug, false)
                .doOnSuccess(done -> {
                    log.info("Power cycle {}: the indoor plug switched off by {}, on again in {}", id,
                        Optional.ofNullable(requestedBy).orElse("nobody named"), properties.cycleOffFor());
                    Mono.delay(properties.cycleOffFor())
                        .then(switchOnAgain(id))
                        .doFinally(signal -> running.set(false))
                        .subscribe(null, failure -> log.error("Power cycle {} ended badly: {}", id, failure.toString()));
                })
                .onErrorResume(failure -> offFailed(id, failure)))
            .doOnError(failure -> running.set(false));
    }

    //the off may have been carried out all the same: the plug is switched on again before the refusal is answered
    private Mono<Void> offFailed(final long id, final Throwable failure) {
        log.warn("Power cycle {}: the indoor plug could not be switched off: {}", id, failure.getMessage());
        return switchOnAgain(id)
            .then(Mono.error(AirconException.deviceFailed("The indoor plug could not be switched off")));
    }

    //switched on, up to ON_ATTEMPTS times, read back; the row ends done or failed - failed is an alert
    Mono<Void> switchOnAgain(final long id) {
        final PlugProperties.Plug plug = properties.indoor();
        //deferred: every attempt sends the command again, and nothing is sent before the delay of the cycle is over
        return Mono.defer(() -> plugMeter.switchTo(plug, true))
            .then(Mono.defer(() -> plugMeter.read(PlugName.INDOOR, plug, LocalDateTime.now(clock))))
            .filter(reading -> Boolean.TRUE.equals(reading.output()))
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("the indoor plug reads off")))
            .retryWhen(Retry.fixedDelay(ON_ATTEMPTS - 1, ON_BACKOFF))
            .flatMap(reading -> finish(id, CycleResult.DONE)
                .then(Mono.fromRunnable(() -> log.info("Power cycle {}: the indoor plug is on again", id))))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                    "Power cycle {}: the indoor plug stays off, the indoor units are without power: {}", id,
                    failure.getMessage()))
                .then(finish(id, CycleResult.FAILED))
                .then(alert()))
            .then();
    }

    //a cycle a restart cut off: its plug switched on, its row failed - once, at the first pass of the plugs
    public Mono<Void> recoverInterrupted() {
        return Mono.just(recovered)
            .filter(flag -> flag.compareAndSet(false, true))
            .flatMapMany(flag -> repository.runningCycles())
            .concatMap(id -> Mono.fromRunnable(() -> log.warn("Power cycle {} was cut off by a restart, the indoor plug"
                    + " is switched on", id))
                .then(plugMeter.switchTo(properties.indoor(), true)
                    .onErrorResume(failure -> alert()))
                .then(finish(id, CycleResult.FAILED)))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> {
                recovered.set(false);
                log.warn("The power cycles cut off by a restart could not be read, tried again: {}", failure.toString());
            }));
    }

    private Mono<Void> finish(final long id, final CycleResult result) {
        return Mono.defer(() -> repository.finishCycle(id, LocalDateTime.now(clock), result))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn("Power cycle {} could not be stored as {}: {}", id,
                result.getValue(), failure.toString())));
    }

    private Mono<Void> alert() {
        return notificationPublisher.publishAlert("The indoor plug of the air conditioners stayed off after a power "
                + "cycle - the four indoor units are without power, switch it on")
            .timeout(PUBLISH_TIMEOUT)
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                "The alert of the indoor plug not published: {}", failure.toString())));
    }

    private List<AirConditioner> indoorUnits() {
        return unitRegistry.current().orElse(List.of()).stream()
            .filter(unit -> unit.room() != null && properties.indoor().rooms().contains(unit.room().getValue()))
            .toList();
    }

    //on as the last read that answered says - a unit not answering is not known to be on
    private boolean knownOn(final AirConditioner unit) {
        return stateStore.of(unit.id())
            .filter(state -> Boolean.TRUE.equals(state.reachable()))
            .map(UnitState::status)
            .map(status -> Boolean.TRUE.equals(status.power()))
            .orElse(false);
    }
}
