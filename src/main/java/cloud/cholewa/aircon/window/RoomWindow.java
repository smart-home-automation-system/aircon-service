package cloud.cholewa.aircon.window;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//The windows and doors of a room as heating-service judges them (HAS-384, its RoomReply.Window of 1.20.0): state open,
//closed, no sensor or unknown, as its words; openSince while one is open; hold once one has stood open for its
//heating.window.hold-after (3 min). Read, never judged again here - one rule of the windows for the heaters and the units
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomWindow(String state, LocalDateTime openSince, Boolean hold) {

    static final String CLOSED = "closed";

    //the only window that holds a unit: one heating-service says holds
    public boolean holds() {
        return Boolean.TRUE.equals(hold);
    }

    //the only window that sets a stopped unit back: every window of the room closed - unknown, a missing answer or a
    //window open again (not held yet) never do
    public boolean closed() {
        return CLOSED.equals(state) && !holds();
    }
}
