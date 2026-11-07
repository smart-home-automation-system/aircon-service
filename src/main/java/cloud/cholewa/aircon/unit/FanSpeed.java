package cloud.cholewa.aircon.unit;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

//The fan of a unit (HAS-380): the column WdSpd of the protocol - 0 auto, 1 to 5 from the lowest to the highest - and
//its name on the API
@Getter
@RequiredArgsConstructor
public enum FanSpeed {
    AUTO(0, "auto"),
    LOW(1, "low"),
    MEDIUM_LOW(2, "medium low"),
    MEDIUM(3, "medium"),
    MEDIUM_HIGH(4, "medium high"),
    HIGH(5, "high");

    private final int wire;
    @JsonValue
    private final String value;

    public static Optional<FanSpeed> ofWire(final int wire) {
        return Arrays.stream(values()).filter(speed -> speed.wire == wire).findFirst();
    }
}
