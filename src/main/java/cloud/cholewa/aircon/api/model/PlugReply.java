package cloud.cholewa.aircon.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

//One plug that feeds the units (HAS-390): its name (indoor, sanctum), the rooms of the units it feeds, whether it has a
//relay - only the indoor plug can be power cycled, the meter of sanctum has none - and, from its last reading while it
//is the plug now (2 min), the power in W, the voltage, the current, whether its relay is on (the indoor plug alone) and
//whether it feeds a running compressor (above the standby). reachable - false while it does not answer, left out before
//the first read after a start; updatedAt - its last reading. The energy in kWh: today so far, and the last 7 and 30
//closed days before today. A field not known is left out, never 0
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlugReply(
    String plug,
    List<String> rooms,
    boolean relay,
    Double power,
    Double voltage,
    Double current,
    Boolean output,
    Boolean compressorRunning,
    Boolean reachable,
    LocalDateTime updatedAt,
    Double todayKwh,
    Double last7DaysKwh,
    Double last30DaysKwh
) {
}
