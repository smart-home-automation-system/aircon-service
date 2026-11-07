package cloud.cholewa.aircon.rule;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The rules of the units (HAS-386), a bare number is minutes: manualHold - how long a change of a person (the remote,
//the dashboard) holds the rule off its unit; freshFor - how old a reading of the room may be and still decide. The
//thermometers of the house report on a change, a battery one at least every two hours (the Plus H&T of sanctum), so a
//reading that did not move is still the room
@Validated
@ConfigurationProperties("aircon.rule")
public record RuleProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 12) Duration manualHold,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 12) Duration freshFor
) {
}
