package cloud.cholewa.aircon.power;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

//The plugs that feed the units (HAS-390) - not devices of the registry: indoor (a Plus Plug S, switch 0, the four indoor
//units together) and sanctum (a PM Mini Gen3, pm1 0); each with its address (the manifest), the component its meter is
//read from, its channel and the rooms of the units it feeds (the words of the registry). standbyWatts - above it a plug
//feeds a running compressor; cycleOffFor - how long a power cycle keeps the indoor plug off (a bare number is seconds)
@Validated
@ConfigurationProperties("aircon.plugs")
public record PlugProperties(
    @NotNull @Valid Plug indoor,
    @NotNull @Valid Plug sanctum,
    @Min(1) @Max(500) int standbyWatts,
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 5) @DurationMax(minutes = 2) Duration cycleOffFor
) {

    public Plug of(final PlugName name) {
        return name == PlugName.INDOOR ? indoor : sanctum;
    }

    //meter switch: Switch.GetStatus of a plug with a relay; pm1: PM1.GetStatus of a meter without one
    public record Plug(@NotBlank String address, @NotNull Meter meter, @Min(0) @Max(3) int channel,
                       @NotEmpty List<String> rooms) {
    }

    public enum Meter { SWITCH, PM1 }
}
