package cloud.cholewa.aircon.window;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//What the reads of the API answer of the windows (HAS-384), in memory: the window of every room as the last read of
//heating-service gave it - forgotten when a read fails, so that a window not known is left out, never answered as
//closed - and until when each unit stopped by a window is set back when it closes. Written by the window pass only
@Component
public class WindowStates {

    private final Map<String, RoomWindow> rooms = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> restoreUntil = new ConcurrentHashMap<>();

    public Optional<RoomWindow> of(final String room) {
        return Optional.ofNullable(room).map(rooms::get);
    }

    public Optional<LocalDateTime> restoreUntil(final long unitId) {
        return Optional.ofNullable(restoreUntil.get(unitId));
    }

    void rooms(final Map<String, RoomWindow> read) {
        rooms.keySet().retainAll(read.keySet());
        rooms.putAll(read);
    }

    void unknown() {
        rooms.clear();
    }

    void held(final long unitId, final LocalDateTime until) {
        restoreUntil.put(unitId, until);
    }

    void released(final long unitId) {
        restoreUntil.remove(unitId);
    }
}
