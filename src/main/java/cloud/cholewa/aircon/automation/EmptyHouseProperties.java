package cloud.cholewa.aircon.automation;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

//The empty house (HAS-385): how long nobody must be at home before the cooling units are switched off (a bare number is
//minutes), and the rooms whose units it never touches - by the words of the registry (sanctum, the server room)
@Validated
@ConfigurationProperties("aircon.empty-house")
public record EmptyHouseProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 12) Duration after,
    @NotNull List<String> exemptRooms
) {
}
