package cloud.cholewa.aircon.state;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

//Who made a change of a unit (HAS-381) - the source column of aircon_state_change, its words pinned by its CHECK. The
//first read of a unit is the start of its history; every change the service did not send is the remote's. The other
//senders come with their tasks (the commands of the dashboard, the rules, the windows, the empty house, the grid); the
//guard of the minimum in cooling since HAS-382
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
    MINIMUM_RESTORED("minimum-restored");

    private final String value;
}
