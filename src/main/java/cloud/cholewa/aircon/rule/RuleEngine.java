package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.automation.AutomationState;
import cloud.cholewa.aircon.automation.GridState;
import cloud.cholewa.aircon.automation.RuleCommands;
import cloud.cholewa.aircon.command.CommandOutcomes;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import cloud.cholewa.aircon.rule.RuleEvaluator.Action;
import cloud.cholewa.aircon.rule.RuleEvaluator.Heating;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.window.HeatingRoom;
import cloud.cholewa.aircon.window.RoomSchedules;
import cloud.cholewa.aircon.window.RoomTemperatures;
import cloud.cholewa.aircon.window.RoomWindow;
import cloud.cholewa.aircon.window.WindowStates;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

//The rules of the units (HAS-386): every minute the rules of aircon_rule against the room temperatures the window pass
//read from heating-service (RoomTemperatures - one read of heating-service a minute) and the state the tracker keeps,
//each judged by RuleEvaluator; a command through RuleCommands under the source rule (aircon_rule_run). One unit after the
//other, one that fails cuts neither the others nor the pass; a command not taken is judged again the next minute - but
//after MAX_NOT_TAKEN in a row the rule pauses for that unit (one WARN) until it asks for nothing or a command is taken.
//Since HAS-387 the frost protection: what the schedule of the room asks for (RoomSchedules, written by the same read of
//the window pass) against the same reading of the room - the house switch of heating-service is not read, the protection
//holds while the heating of the house is off too
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleEngine {

    static final Duration LIMIT = Duration.ofSeconds(55);
    static final int MAX_NOT_TAKEN = 3;

    private final RuleRepository repository;
    private final UnitRegistry unitRegistry;
    private final StateStore stateStore;
    private final RoomTemperatures roomTemperatures;
    private final RoomSchedules roomSchedules;
    private final WindowStates windowStates;
    private final AutomationState automationState;
    private final RuleCommands ruleCommands;
    private final CommandOutcomes commandOutcomes;
    private final RuleProperties properties;
    private final Clock clock;

    //per unit: the commands of the rule in a row the unit did not take, and the units the last command of the rule went to
    private final Map<Long, Integer> notTaken = new ConcurrentHashMap<>();
    private final Set<Long> sent = ConcurrentHashMap.newKeySet();

    //a minute and a half in: the registry, the units and the rooms of heating-service were read at least once
    @Scheduled(initialDelayString = "PT90S", fixedDelayString = "PT1M")
    Mono<Void> run() {
        return ScheduledPass.bounded("air conditioner rules", LIMIT, this::runOnce);
    }

    Mono<Void> runOnce() {
        return Mono.justOrEmpty(unitRegistry.current())
            .flatMap(units -> repository.findAll()
                .collectList()
                .flatMap(rules -> {
                    final LocalDateTime now = LocalDateTime.now(clock);
                    final Map<Long, AirConditioner> byId = units.stream()
                        .collect(Collectors.toMap(AirConditioner::id, Function.identity()));
                    final List<AirconRule> registered = rules.stream()
                        .filter(rule -> byId.containsKey(rule.unitId()))
                        .toList();
                    return Flux.fromIterable(registered)
                        .concatMap(rule -> judge(rule, byId.get(rule.unitId()), now))
                        .then();
                }))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error("The pass of the rules failed: {}",
                failure.toString())));
    }

    private Mono<Void> judge(final AirconRule rule, final AirConditioner unit, final LocalDateTime now) {
        return Mono.defer(() -> {
            final String room = Optional.ofNullable(unit.room()).map(RoomName::getValue).orElse(null);
            final Double temperature = roomTemperatures.of(room)
                .filter(reading -> !reading.updatedAt().isBefore(now.minus(properties.freshFor())))
                .map(HeatingRoom.Reading::value)
                .orElse(null);
            final boolean heldOff = automationState.grid().map(GridState::lost).orElse(false)
                || windowStates.of(room).map(RoomWindow::holds).orElse(false);
            final Heating heating = roomSchedules.of(room)
                .map(schedule -> new Heating(schedule.scheduledTemperature()))
                .orElse(null);
            final Action action = RuleEvaluator.decide(rule, stateStore.of(unit.id()).orElse(null), temperature, heating,
                stateStore.lastChange(unit.id()).orElse(null), heldOff, now, properties);
            return act(action, rule, unit, temperature, heating);
        }).onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
            "The rule of air conditioner {} could not be acted on: {}", unit.id(), failure.getMessage())));
    }

    //what the last command of the rule came to, counted before the next one: taken resets, not taken counts
    private boolean paused(final long unitId) {
        if (sent.remove(unitId)) {
            commandOutcomes.lastCommandConfirmed(unitId).ifPresent(taken -> {
                if (Boolean.TRUE.equals(taken)) {
                    notTaken.remove(unitId);
                } else if (notTaken.merge(unitId, 1, Integer::sum) == MAX_NOT_TAKEN) {
                    log.warn("Air conditioner {} did not take {} commands of its rule in a row, the rule pauses for it",
                        unitId, MAX_NOT_TAKEN);
                }
            });
        }
        return notTaken.getOrDefault(unitId, 0) >= MAX_NOT_TAKEN;
    }

    private Mono<Void> act(final Action action, final AirconRule rule, final AirConditioner unit,
                           final Double temperature, final Heating heating) {
        return Mono.just(action)
            .doOnNext(next -> forgetWhenSettled(next, unit.id()))
            .filter(next -> next != Action.NONE && !paused(unit.id()))
            .doOnNext(next -> sent.add(unit.id()))
            .flatMap(next -> command(next, rule, unit, temperature, heating));
    }

    //nothing asked of the unit: what its commands came to is over
    private void forgetWhenSettled(final Action action, final long unitId) {
        if (action == Action.NONE) {
            notTaken.remove(unitId);
            sent.remove(unitId);
        }
    }

    private Mono<Void> command(final Action action, final AirconRule rule, final AirConditioner unit,
                               final Double temperature, final Heating heating) {
        return switch (action) {
            case NONE -> Mono.empty();
            case COOL -> Mono.fromRunnable(() -> log.info("Air conditioner {}: {} C above {} C, cooling at {} C", unit.id(),
                    temperature, rule.keepBelow().threshold(), rule.keepBelow().target()))
                .then(Mono.defer(() -> ruleCommands.send(unit.id(), cool(rule), ChangeSource.RULE, null)));
            case OFF -> Mono.fromRunnable(() -> log.info("Air conditioner {}: {} C below {} C, switched off", unit.id(),
                    temperature, rule.keepBelow().threshold().subtract(rule.keepBelow().hysteresis())))
                .then(Mono.defer(() -> ruleCommands.send(unit.id(), off(), ChangeSource.RULE, null)));
            case HEAT -> Mono.fromRunnable(() -> log.info("Air conditioner {}: {} C, below the {} C of the schedule, heating",
                    unit.id(), temperature, heating.scheduledTemperature()))
                .then(Mono.defer(() -> ruleCommands.send(unit.id(), heat(), ChangeSource.RULE, null)));
            case STOP_HEATING -> Mono.fromRunnable(() -> log.info("Air conditioner {}: {}, switched off", unit.id(),
                    whyNotHeating(temperature, heating)))
                .then(Mono.defer(() -> ruleCommands.send(unit.id(), off(), ChangeSource.RULE, null)));
        };
    }

    static CommandRequest cool(final AirconRule rule) {
        return new CommandRequest(true, Mode.COOL, rule.keepBelow().target(), null, null, null, null, null, null, null);
    }

    //the heating switched on with the target the unit has (the owner keeps 21 C) - the room thermometer stops it
    static CommandRequest heat() {
        return new CommandRequest(true, Mode.HEAT, null, null, null, null, null, null, null, null);
    }

    private static String whyNotHeating(final Double temperature, final Heating heating) {
        return heating.scheduledTemperature() == null
            ? "no heating schedule covers the room now"
            : temperature + " C, the " + heating.scheduledTemperature() + " C of the schedule reached";
    }

    static CommandRequest off() {
        return new CommandRequest(false, null, null, null, null, null, null, null, null, null);
    }
}
