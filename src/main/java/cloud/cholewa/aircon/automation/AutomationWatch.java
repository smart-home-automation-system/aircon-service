package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.automation.AutomationEvaluator.Episode;
import cloud.cholewa.aircon.automation.AutomationEvaluator.Pending;
import cloud.cholewa.aircon.error.AirconError;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.state.UnitState;
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
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

//The empty house and a lost grid (HAS-385). Every minute who is at home is read from presence-service and the grid from
//energy-service, both over k8s DNS and both at once; a read that fails or takes longer than READ_TIMEOUT is not known,
//and what is not known never acts. The grid lost: every unit that is on is switched off, heating included, source
//grid-outage; nobody at home for aircon.empty-house.after: every unit cooling (cool, dry, fan, auto) outside the exempt
//rooms (sanctum), source empty-house. An episode is acted on once - the units on when it begins are the ones it switches
//off, its key stored in aircon_automation_episode before the first command - and each of them is sent the command every
//pass until it took it, while the episode lasts (a pass cut by its bound, or a unit that did not answer, goes on the next
//minute). Nothing is switched back on: the return is the household's. Every command goes through RuleCommands
//(aircon_rule_run); a unit that fails cuts neither the others nor the pass
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationWatch {

    static final Duration LIMIT = Duration.ofSeconds(55);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    //how long an automatic command is logged
    static final Duration KEPT = Duration.ofDays(90);
    static final CommandRequest OFF = new CommandRequest(false, null, null, null, null, null, null, null, null, null);

    private final UnitRegistry unitRegistry;
    private final StateStore stateStore;
    private final PresenceClient presenceClient;
    private final EnergyClient energyClient;
    private final RuleCommands ruleCommands;
    private final RuleRunRepository ruleRuns;
    private final EpisodeRepository episodes;
    private final AutomationState state;
    private final EmptyHouseProperties properties;
    private final Clock clock;

    private final AtomicBoolean restored = new AtomicBoolean();

    //a minute in: the registry is read and every unit was read by the tracker at least once
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> watch() {
        return ScheduledPass.bounded("air conditioner automation", LIMIT, this::watchOnce);
    }

    Mono<Void> watchOnce() {
        return restore()
            .then(Mono.defer(() -> Mono.justOrEmpty(unitRegistry.current())))
            .flatMap(units -> Mono.zip(house(), grid())
                .flatMap(read -> {
                    final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
                    state.house(read.getT1(), now);
                    state.grid(read.getT2());
                    //the grid first: a house on its battery matters more than an empty one, if the bound cuts the pass
                    return outage(units, read.getT2(), now)
                        .then(Mono.defer(() -> emptyHouse(units, read.getT1(), now)))
                        .then(Mono.defer(() -> forgetOld(now)));
                }))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error("The automation pass failed: {}",
                failure.toString())));
    }

    //the episodes the empty house and a lost grid last acted on, once a start - tried again until it was read; nothing
    //acts before
    private Mono<Void> restore() {
        return Mono.just(restored)
            .filter(done -> !done.get())
            .flatMap(notYet -> episodes.all()
                .doOnNext(acted -> state.restore(acted.source(), acted.episode()))
                .then(Mono.fromRunnable(() -> notYet.set(true)))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The last episodes of the automation could not be read, nothing acts until they are: {}",
                    failure.toString()))))
            .then();
    }

    private Mono<HouseOccupancy> house() {
        return presenceClient.residents()
            .timeout(READ_TIMEOUT)
            .map(residents -> HouseOccupancy.judge(residents, LocalDateTime.now(clock)))
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("Who is at home could not be read, the empty house does not act: {}", failure.toString());
                return HouseOccupancy.unknown();
            }));
    }

    private Mono<GridState> grid() {
        return energyClient.grid()
            .timeout(READ_TIMEOUT)
            .onErrorResume(failure -> Mono.fromSupplier(() -> {
                log.warn("The grid could not be read, a lost grid does not act: {}", failure.toString());
                return GridState.unknown();
            }));
    }

    private Mono<Void> outage(final List<AirConditioner> units, final GridState grid, final LocalDateTime now) {
        return Mono.just(grid)
            .filter(read -> restored.get() && AutomationEvaluator.outageBegins(read,
                state.acted(ChangeSource.GRID_OUTAGE).orElse(null)))
            .flatMap(read -> begin(ChangeSource.GRID_OUTAGE, new Episode(read.since(), now),
                AutomationEvaluator.gridTargets(units, statuses(units))))
            .then(Mono.defer(() -> Mono.just(grid).filter(GridState::lost)
                .flatMap(lost -> switchOff(units, ChangeSource.GRID_OUTAGE))));
    }

    private Mono<Void> emptyHouse(final List<AirConditioner> units, final HouseOccupancy house, final LocalDateTime now) {
        return Mono.just(house)
            .filter(read -> restored.get() && AutomationEvaluator.emptyHouseBegins(read,
                state.firstSeenEmpty().orElse(null), state.acted(ChangeSource.EMPTY_HOUSE).orElse(null), now,
                properties.after()))
            .flatMap(read -> begin(ChangeSource.EMPTY_HOUSE,
                new Episode(AutomationEvaluator.emptyKey(read, state.firstSeenEmpty().orElse(null)), now),
                AutomationEvaluator.emptyHouseTargets(units, statuses(units), properties.exemptRooms())))
            .then(Mono.defer(() -> Mono.just(house).filter(HouseOccupancy::empty)
                .flatMap(empty -> switchOff(units, ChangeSource.EMPTY_HOUSE))));
    }

    //an episode begins: held in memory at once, its key stored - a failed write is a WARN, the memory holds
    private Mono<Void> begin(final ChangeSource source, final Episode episode, final List<AirConditioner> targets) {
        return Mono.fromRunnable(() -> {
                state.begins(source, episode, targets.stream().map(AirConditioner::id).toList());
                log.info("The {} since {} begins: {} air conditioner(s) to switch off", source.getValue(), episode.key(),
                    targets.size());
            })
            .then(episodes.save(source, episode))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The {} episode could not be stored, a restart may act on it again: {}", source.getValue(),
                failure.toString())));
    }

    //the units of the episode still on, one after the other: answering - sent the command; off - done; silent - next pass
    private Mono<Void> switchOff(final List<AirConditioner> units, final ChangeSource source) {
        final Set<Long> left = state.pending(source);
        final LocalDateTime episode = state.acted(source).map(Episode::key).orElse(null);
        return Flux.fromIterable(units)
            .filter(unit -> left.contains(unit.id()))
            .concatMap(unit -> Mono.defer(() -> {
                final Pending pending = AutomationEvaluator.pending(stateStore.of(unit.id()).orElse(null));
                if (pending == Pending.DONE) {
                    state.done(source, unit.id());
                }
                return Mono.just(pending)
                    .filter(Pending.SEND::equals)
                    .flatMap(send -> ruleCommands.send(unit.id(), OFF, source, episode)
                        .then(Mono.fromRunnable(() -> state.done(source, unit.id())))
                        .onErrorResume(AutomationWatch::refused, refused -> Mono.fromRunnable(() -> {
                            state.done(source, unit.id());
                            log.warn("Air conditioner {} is not switched off for the {}: {}", unit.id(),
                                source.getValue(), refused.getMessage());
                        }))
                        .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                            "Air conditioner {} could not be switched off for the {}, tried again next minute: {}",
                            unit.id(), source.getValue(), failure.getMessage()))));
            }))
            .then();
    }

    private static boolean refused(final Throwable failure) {
        return failure instanceof AirconException refused && refused.getError() == AirconError.INVALID_COMMAND;
    }

    private Map<Long, UnitStatus> statuses(final List<AirConditioner> units) {
        final Map<Long, UnitStatus> statuses = new HashMap<>();
        units.forEach(unit -> stateStore.of(unit.id()).map(UnitState::status)
            .ifPresent(status -> statuses.put(unit.id(), status)));
        return statuses;
    }

    private Mono<Void> forgetOld(final LocalDateTime now) {
        return ruleRuns.deleteBefore(now.minus(KEPT))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The old automatic commands could not be deleted: {}", failure.toString())));
    }
}
