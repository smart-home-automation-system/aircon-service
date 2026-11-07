package cloud.cholewa.aircon.api.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//The rule of a unit (HAS-386), as stored and answered: keepBelow - keep the room below threshold (C, by 0.5), switched
//off again below threshold - hysteresis, cooling at target (whole C, 22 at least); heatBySchedule - heat by the schedules
//of heating-service (HAS-387, not acted on yet); updatedAt - house time of the last write
public record AirconRule(long unitId, KeepBelow keepBelow, HeatBySchedule heatBySchedule, LocalDateTime updatedAt) {

    public record KeepBelow(boolean enabled, BigDecimal threshold, BigDecimal hysteresis, int target) {
    }

    public record HeatBySchedule(boolean enabled) {
    }
}
