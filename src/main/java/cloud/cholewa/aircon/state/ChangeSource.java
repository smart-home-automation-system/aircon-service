package cloud.cholewa.aircon.state;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

//Who made a change of a unit (HAS-381) - the source column of aircon_state_change, its words pinned by its CHECK. The
//first read of a unit is the start of its history; every change the service did not send is the remote's. The other
//senders come with their tasks (the commands of the dashboard, the rules, the windows, the empty house, the grid); the
//guard of the minimum in cooling since HAS-382; safety-service since HAS-467
@Getter
@RequiredArgsConstructor
public enum ChangeSource {
    FIRST_READ("first-read"),
    REMOTE("remote"),
    DASHBOARD("dashboard"),
    RULE("rule"),
    WINDOW("window"),
    EMPTY_HOUSE("empty-house"),
    GRID_OUTAGE("grid-outage"),
    //the guard of HAS-382: a target below 22 C in cooling written back to 22 C
    MINIMUM_RESTORED("minimum-restored"),
    //safety-service switching the units off at a fire alarm (HAS-467): holds the rule off as a person's change does
    SAFETY("safety");

    //the word of the table is the word of the API too (the runs of a unit, HAS-391)
    @JsonValue
    private final String value;

    //the word as the table writes it, or nothing for a word this version does not know
    public static Optional<ChangeSource> ofValue(final String value) {
        return Arrays.stream(values()).filter(source -> source.value.equals(value)).findFirst();
    }
}
