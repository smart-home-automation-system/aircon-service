package cloud.cholewa.aircon.filter;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//The filter of every unit as the last pass counted it (HAS-391), in memory: the reads of the units answer it without a
//statement each. Not known after a start until the first pass
@Component
public class FilterStore {

    private final Map<Long, FilterState> filters = new ConcurrentHashMap<>();

    public Optional<FilterState> of(final long unitId) {
        return Optional.ofNullable(filters.get(unitId));
    }

    void put(final long unitId, final FilterState state) {
        filters.put(unitId, state);
    }
}
