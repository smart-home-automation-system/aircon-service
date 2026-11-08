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
//reading that did not move is still the room. The frost protection (HAS-387): heatMinRun - how long a heating the rule
//switched on runs at least, heatMinOff - how long a unit the rule switched off rests before the rule heats again - the
//compressor is not cycled (owner 2026-11-08: 30 min of work)
@Validated
@ConfigurationProperties("aircon.rule")
public record RuleProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 12) Duration manualHold,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 12) Duration freshFor,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 3) Duration heatMinRun,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 3) Duration heatMinOff
) {
}
