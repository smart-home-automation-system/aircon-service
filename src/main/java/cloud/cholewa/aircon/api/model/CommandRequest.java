package cloud.cholewa.aircon.api.model;

import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;

//A command to an air conditioner (HAS-382): every field optional, the ones given are set, the others left as the unit
//has them. The words of the reads: mode (auto, cool, dry, fan, heat), fanSpeed (auto, low, medium-low, medium,
//medium-high, high), targetTemperature in whole degrees C, the position codes of the louvres (0-11, 0-6)
public record CommandRequest(
    Boolean power,
    Mode mode,
    Integer targetTemperature,
    FanSpeed fanSpeed,
    Integer swingVertical,
    Integer swingHorizontal,
    Boolean quiet,
    Boolean turbo,
    Boolean sleep,
    Boolean display
) {
}
