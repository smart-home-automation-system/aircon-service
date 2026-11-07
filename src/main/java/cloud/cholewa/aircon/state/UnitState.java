package cloud.cholewa.aircon.state;

import cloud.cholewa.aircon.unit.UnitStatus;

import java.time.LocalDateTime;

//What the service knows of a unit now (HAS-381): what it reported at its last read that answered, whether the last read
//answered (null - not known: restored at a start, not read since), when the last read that answered was, and since
//HAS-383 since when the unit does not answer (the first read of the run that did not answer; null while it answers or
//is not known - a restart counts anew)
public record UnitState(UnitStatus status, Boolean reachable, LocalDateTime updatedAt, LocalDateTime unreachableSince) {

    //what a read that answered makes of a unit
    static UnitState answering(final UnitStatus status, final LocalDateTime at) {
        return new UnitState(status, true, at, null);
    }

    //the same state, a read not answered at a time: what the unit reported before stays, marked unreachable - since the
    //first read that did not answer
    UnitState unreachable(final LocalDateTime at) {
        return new UnitState(status, false, updatedAt, Boolean.FALSE.equals(reachable) ? unreachableSince : at);
    }
}
