package cloud.cholewa.aircon.state;

import cloud.cholewa.aircon.unit.UnitStatus;

import java.time.LocalDateTime;

//What the service knows of a unit now (HAS-381): what it reported at its last read that answered, whether the last read
//answered (null - not known: restored at a start, not read since) and when the last read that answered was
public record UnitState(UnitStatus status, Boolean reachable, LocalDateTime updatedAt) {

    //the same state, the last read not answered: what the unit reported before stays, marked unreachable
    UnitState unreachable() {
        return new UnitState(status, false, updatedAt);
    }
}
