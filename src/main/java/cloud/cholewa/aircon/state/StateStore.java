package cloud.cholewa.aircon.state;

import cloud.cholewa.aircon.unit.UnitStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//The state of every unit in memory (HAS-381), by the id of its device - what the reads of the API answer. The one
//instance of the service is its only writer (Recreate, never scaled)
@Component
public class StateStore {

    private final Map<Long, UnitState> states = new ConcurrentHashMap<>();

    public Optional<UnitState> of(final long unitId) {
        return Optional.ofNullable(states.get(unitId));
    }

    //a read that answered; answers what was known before it
    Optional<UnitState> answered(final long unitId, final UnitStatus status, final LocalDateTime at) {
        return Optional.ofNullable(states.put(unitId, UnitState.answering(status, at)));
    }

    //a read that did not answer at a time: what the unit reported before stays, marked unreachable since the first such
    //read; a unit never read stays unknown but unreachable
    void unanswered(final long unitId, final LocalDateTime at) {
        states.compute(unitId, (id, known) -> known == null
            ? new UnitState(null, false, null, at)
            : known.unreachable(at));
    }

    //the rows of aircon_state at a start: what each unit reported, not known to be reachable until its first read; a unit
    //already read in this run keeps what it reported now
    void restore(final StoredState stored) {
        states.putIfAbsent(stored.unitId(), new UnitState(stored.status(), null, stored.lastCheckedAt(), null));
    }
}
