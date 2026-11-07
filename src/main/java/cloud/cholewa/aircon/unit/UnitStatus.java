package cloud.cholewa.aircon.unit;

//What a unit reports of itself (HAS-380), in the words of the house, apart from the wire: on or off, the mode, the
//target and the room temperature in degrees C, the fan, the position codes of both louvres (SwUpDn 0-11, SwingLfRig
//0-6, as the unit gives them), quiet, turbo, sleep and its display (the light of its panel), and its firmware. A field the unit
//did not report is null - never read as off; roomTemperature is null where the unit has no sensor (TemSen 0)
public record UnitStatus(
    Boolean power,
    Mode mode,
    Integer targetTemperature,
    Integer roomTemperature,
    FanSpeed fanSpeed,
    Integer swingVertical,
    Integer swingHorizontal,
    Boolean quiet,
    Boolean turbo,
    Boolean sleep,
    Boolean display,
    String firmware
) {
}
