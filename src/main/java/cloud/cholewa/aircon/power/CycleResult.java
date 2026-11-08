package cloud.cholewa.aircon.power;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

//What a power cycle came to (HAS-390) - the result column of aircon_power_cycle, its words pinned by its CHECK: the plug
//is off and will be switched on, it is on again, or it could not be switched off - or not back on
@Getter
@RequiredArgsConstructor
public enum CycleResult {
    RUNNING("running"),
    DONE("done"),
    FAILED("failed");

    @JsonValue
    private final String value;

    public static Optional<CycleResult> ofValue(final String value) {
        return Arrays.stream(values()).filter(result -> result.value.equals(value)).findFirst();
    }
}
