package cloud.cholewa.aircon.monitor;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The availability monitor (HAS-383): how long a unit may go without an answer before the household is told (a bare
//number is minutes), and how often it is told again while the unit stays out
@Validated
@ConfigurationProperties("aircon.monitor")
public record MonitorProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration offlineAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {
}
