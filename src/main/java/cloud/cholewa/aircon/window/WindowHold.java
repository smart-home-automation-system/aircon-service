package cloud.cholewa.aircon.window;

import cloud.cholewa.aircon.unit.UnitStatus;

import java.time.LocalDateTime;

//A stop of a unit by the open window of its room (HAS-384), a row of aircon_window_hold: the opening that held it (the
//openSince of heating-service, null when it named none), when the stop was decided, what the unit was doing then - the
//mode, the target, the fan, both louvres, quiet, turbo and sleep, the rest of UnitStatus left out - and, once it ended,
//when and how. An open hold has neither closedAt nor outcome
public record WindowHold(
    Long id,
    long unitId,
    LocalDateTime windowOpenSince,
    LocalDateTime heldSince,
    UnitStatus before,
    LocalDateTime closedAt,
    HoldOutcome outcome
) {

    public boolean open() {
        return closedAt == null;
    }
}
