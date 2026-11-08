package cloud.cholewa.aircon.power;

import java.time.LocalDateTime;

//One reading of a plug (HAS-390): when, the power in W, the voltage, the current, the counter of its meter in Wh, and -
//for a plug with a relay - whether it is on (null for the meter of sanctum). What a plug did not send is null
public record PlugReading(PlugName plug, LocalDateTime at, double power, Double voltage, Double current, Double totalWh,
                          Boolean output) {
}
