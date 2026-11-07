package cloud.cholewa.aircon.unit;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

//The mode of a unit (HAS-380): the column Mod of the protocol - 0 auto, 1 cool, 2 dry, 3 fan, 4 heat - and its name on
//the API
@Getter
@RequiredArgsConstructor
public enum Mode {
    AUTO(0, "auto"),
    COOL(1, "cool"),
    DRY(2, "dry"),
    FAN(3, "fan"),
    HEAT(4, "heat");

    private final int wire;
    @JsonValue
    private final String value;

    //the value as the API and the table write it, or nothing for a word this version does not know
    public static Optional<Mode> ofValue(final String value) {
        return Arrays.stream(values()).filter(mode -> mode.value.equals(value)).findFirst();
    }

    public static Optional<Mode> ofWire(final int wire) {
        return Arrays.stream(values()).filter(mode -> mode.wire == wire).findFirst();
    }
}
