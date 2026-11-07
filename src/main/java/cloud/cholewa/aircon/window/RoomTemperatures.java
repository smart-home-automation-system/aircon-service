package cloud.cholewa.aircon.window;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//The temperature of every room as the last read of heating-service gave it (HAS-386), written by the window pass - the
//one read of heating-service a minute - and read by the rules. Forgotten when a read fails, so a temperature not known
//is never the last one heard
@Component
public class RoomTemperatures {

    private final Map<String, HeatingRoom.Reading> rooms = new ConcurrentHashMap<>();

    public Optional<HeatingRoom.Reading> of(final String room) {
        return Optional.ofNullable(room).map(rooms::get);
    }

    void rooms(final Map<String, HeatingRoom.Reading> read) {
        rooms.keySet().retainAll(read.keySet());
        rooms.putAll(read);
    }

    void unknown() {
        rooms.clear();
    }
}
