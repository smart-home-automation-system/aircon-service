package cloud.cholewa.aircon.command;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The commands (HAS-382): how long after a command its effect is read back (a bare number is seconds), and how long the
//change a read shows is taken for the change the command made - after that it is the remote's
@Validated
@ConfigurationProperties("aircon.command")
public record CommandProperties(
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(millis = 10) @DurationMax(seconds = 30) Duration confirmAfter,
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 5) @DurationMax(minutes = 10) Duration expectFor
) {
}
