package cloud.cholewa.aircon.window;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//What the heating schedule of every room asks for now, as the last read of heating-service gave it (HAS-387), written by
//the window pass - the one read of heating-service a minute - and read by the rules. A room of that read is known: its
//temperature, or none while no schedule covers it; a room not in it, or every room after a read that failed, is not
//known - never "no schedule"
@Component
public class RoomSchedules {

    private final Map<String, Schedule> rooms = new ConcurrentHashMap<>();

    public Optional<Schedule> of(final String room) {
        return Optional.ofNullable(room).map(rooms::get);
    }

    void rooms(final Map<String, Schedule> read) {
        rooms.keySet().retainAll(read.keySet());
        rooms.putAll(read);
    }

    void unknown() {
        rooms.clear();
    }

    //scheduledTemperature null while no schedule covers the room
    public record Schedule(Double scheduledTemperature) {
    }
}
