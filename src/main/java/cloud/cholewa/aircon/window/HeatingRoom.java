package cloud.cholewa.aircon.window;

import java.time.LocalDateTime;

//A room of GET /home/heating/rooms of heating-service (HAS-384), only what this service reads of it: its name - the word
//of RoomName, kept as text so that a room this version of the SDK does not know fails nothing -, its window and since
//HAS-386 its temperature (the reading of the room and when heating-service received it, left out when it has none).
//Every other field of the answer is left unread
public record HeatingRoom(String name, RoomWindow window, Reading temperature) {

    public HeatingRoom(final String name, final RoomWindow window) {
        this(name, window, null);
    }

    public record Reading(Double value, LocalDateTime updatedAt) {
    }
}
