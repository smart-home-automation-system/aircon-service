package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.api.model.RuleBody;
import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.registry.UnitRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

//The rules of the units through the API (HAS-386): read, written whole and removed; a write checked by RuleRules and
//only for a unit of the registry. The engine reads the table at every pass, so a write acts within the minute
@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleRepository repository;
    private final UnitRegistry unitRegistry;
    private final Clock clock;

    public Mono<List<AirconRule>> rules() {
        return repository.findAll().collectList();
    }

    public Mono<AirconRule> rule(final long unitId) {
        return repository.find(unitId)
            .switchIfEmpty(Mono.error(() -> AirconException.ruleNotFound(unitId)));
    }

    public Mono<AirconRule> save(final long unitId, final RuleBody body) {
        return registered(unitId)
            .then(Mono.defer(() -> Mono.justOrEmpty(RuleRules.problem(body))))
            .flatMap(reason -> Mono.<AirconRule>error(AirconException.invalidRule(reason)))
            .switchIfEmpty(Mono.fromSupplier(() -> RuleRules.of(unitId, body,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS))))
            .flatMap(rule -> repository.save(rule).thenReturn(rule));
    }

    public Mono<Void> delete(final long unitId) {
        return repository.delete(unitId)
            .filter(Boolean.TRUE::equals)
            .switchIfEmpty(Mono.error(() -> AirconException.ruleNotFound(unitId)))
            .then();
    }

    private Mono<Void> registered(final long unitId) {
        return Mono.justOrEmpty(unitRegistry.current())
            .switchIfEmpty(Mono.error(AirconException::registryUnavailable))
            .filter(units -> units.stream().anyMatch(unit -> unit.id() == unitId))
            .switchIfEmpty(Mono.error(() -> AirconException.airconNotFound(unitId)))
            .then();
    }
}
