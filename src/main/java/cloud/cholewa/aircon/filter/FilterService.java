package cloud.cholewa.aircon.filter;

import cloud.cholewa.aircon.error.AirconException;
import cloud.cholewa.aircon.history.HistoryRange;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.registry.UnitRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

//The filters and the runs of a unit (HAS-391): a cleaning recorded - the hours start again at 0 and the reminders over -,
//and the changes of a unit in a range by the rules of HistoryRange
@Service
@RequiredArgsConstructor
public class FilterService {

    private final UnitRegistry unitRegistry;
    private final FilterRepository repository;
    private final FilterStore store;
    private final Clock clock;

    public Mono<Void> cleaned(final long id) {
        return registered(id)
            .flatMap(unit -> Mono.defer(() -> {
                final LocalDateTime now = LocalDateTime.now(clock);
                return repository.cleaned(unit.id(), now)
                    .then(Mono.fromRunnable(() -> store.put(unit.id(), new FilterState(0, now, false))));
            }));
    }

    public Mono<List<UnitRun>> runs(final long id, final LocalDateTime from, final LocalDateTime to) {
        return registered(id)
            .flatMap(unit -> Mono.justOrEmpty(HistoryRange.violation(from, to))
                .flatMap(Mono::<AirConditioner>error)
                .defaultIfEmpty(unit))
            .flatMap(unit -> repository.runs(unit.id(), from, to).collectList());
    }

    private Mono<AirConditioner> registered(final long id) {
        return Mono.justOrEmpty(unitRegistry.current())
            .switchIfEmpty(Mono.error(AirconException::registryUnavailable))
            .flatMap(units -> Mono.justOrEmpty(units.stream().filter(unit -> unit.id() == id).findFirst()))
            .switchIfEmpty(Mono.error(() -> AirconException.airconNotFound(id)));
    }
}
