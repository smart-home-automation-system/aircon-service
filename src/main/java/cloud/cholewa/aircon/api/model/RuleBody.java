package cloud.cholewa.aircon.api.model;

import java.math.BigDecimal;

//The body of PUT /home/aircon/{id}/rules (HAS-386): the whole rule, every field checked by RuleRules - no @Valid, so a
//broken rule answers INVALID_RULE with its reason. keepBelow is required, its hysteresis defaults to 1; heatBySchedule
//left out is off
public record RuleBody(KeepBelow keepBelow, HeatBySchedule heatBySchedule) {

    public record KeepBelow(Boolean enabled, BigDecimal threshold, BigDecimal hysteresis, Integer target) {
    }

    public record HeatBySchedule(Boolean enabled) {
    }
}
