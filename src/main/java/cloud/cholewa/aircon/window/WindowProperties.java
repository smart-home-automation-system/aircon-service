package cloud.cholewa.aircon.window;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//An open window (HAS-384): how long after a unit was stopped by the window of its room it is still set back as it was
//when the window closes (a bare number is minutes); later it stays off
@Validated
@ConfigurationProperties("aircon.window")
public record WindowProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 5) @DurationMax(hours = 24) Duration restoreWithin
) {
}
