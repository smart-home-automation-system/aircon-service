package cloud.cholewa.aircon.state;

import cloud.cholewa.aircon.unit.UnitStatus;

import java.time.LocalDateTime;

//The current row of a unit in aircon_state (HAS-381): what it reported at its last read and when that read was
public record StoredState(long unitId, UnitStatus status, LocalDateTime lastCheckedAt) {
}
