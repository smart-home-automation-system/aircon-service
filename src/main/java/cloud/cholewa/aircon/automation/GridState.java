package cloud.cholewa.aircon.automation;

import java.time.LocalDateTime;

//The grid as energy-service says it (HAS-385, GridStateReply of GET /home/energy/status, energy-service >= 0.4.0): state
//present or lost, null while not known (never read as present); since - when it is so, the key of an outage that
//survives a restart
public record GridState(String state, LocalDateTime since) {

    static final String LOST = "lost";
    static final String PRESENT = "present";

    public static GridState unknown() {
        return new GridState(null, null);
    }

    public boolean lost() {
        return LOST.equals(state);
    }

    public boolean present() {
        return PRESENT.equals(state);
    }
}
