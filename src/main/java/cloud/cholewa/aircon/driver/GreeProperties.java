package cloud.cholewa.aircon.driver;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//The local protocol of the Gree units (HAS-380): the UDP port of the units, how long one request waits for its answer
//(a bare number is seconds), how many times a request whose answer did not come is sent again, and after how many
//failed requests in a row a unit is bound again. The defaults of the probe of 2026-11-04: Wi-Fi retransmissions of
//11-62 % lose a datagram now and then, a unit answers in well under a second
@Validated
@ConfigurationProperties("aircon.gree")
public record GreeProperties(
    @Min(1) @Max(65535) int port,
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(millis = 100) @DurationMax(seconds = 10) Duration timeout,
    @Min(0) @Max(5) int retries,
    @Min(1) @Max(10) int rebindAfter
) {
}
