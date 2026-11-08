package cloud.cholewa.aircon.filter;

import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//One change of a unit, a row of aircon_state_change (HAS-391): when (house time), what the unit did after it - power,
//mode, target, fan - and who made it (first-read, remote, dashboard, rule, window, empty-house, grid-outage,
//minimum-restored). A value the unit did not report is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnitRun(LocalDateTime at, Boolean power, Mode mode, Integer targetTemperature, FanSpeed fanSpeed,
                      ChangeSource source) {
}
