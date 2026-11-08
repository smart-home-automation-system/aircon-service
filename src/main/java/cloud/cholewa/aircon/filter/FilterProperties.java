package cloud.cholewa.aircon.filter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

//The filters of the units (HAS-391), in hours of work: remindAfterHours since the cleaning a filter is due - one info on
//Discord naming the unit - and remindAgainHours later it is named again, every time, until it is cleaned
@Validated
@ConfigurationProperties("aircon.filter")
public record FilterProperties(
    @Min(10) @Max(5000) int remindAfterHours,
    @Min(1) @Max(1000) int remindAgainHours
) {
}
