package cloud.cholewa.aircon.window;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

//How the stop of a unit by an open window ended (HAS-384) - the outcome column of aircon_window_hold, its words pinned
//by its CHECK: set back as it was, left off because the window closed too late, given up because the remote or the
//dashboard changed the unit while it was held, or refused because the rules of the commands refuse what it was doing
@Getter
@RequiredArgsConstructor
public enum HoldOutcome {
    RESTORED("restored"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    REFUSED("refused");

    private final String value;
}
