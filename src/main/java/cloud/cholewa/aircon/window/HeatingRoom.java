package cloud.cholewa.aircon.window;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

//A room of GET /home/heating/rooms of heating-service (HAS-384), only what this service reads of it: its name - the word
//of RoomName, kept as text so that a room this version of the SDK does not know fails nothing -, its window, since
//HAS-386 its temperature (the reading of the room and when heating-service received it, left out when it has none) and
//since HAS-387 the temperature the schedule of each heater asks for now (scheduledTemperature, left out while no schedule
//covers the heater - heating-service >= 1.21.0 keeps it for the radiator of sanctum it no longer drives). Every other
//field of the answer is left unread
public record HeatingRoom(String name, RoomWindow window, Reading temperature, List<Heater> heaters) {

    public HeatingRoom(final String name, final RoomWindow window) {
        this(name, window, null, null);
    }

    public HeatingRoom(final String name, final RoomWindow window, final Reading temperature) {
        this(name, window, temperature, null);
    }

    //what the schedules of the room ask for now - the most any of its heaters asks for; nothing while none covers the room
    public Optional<Double> scheduledTemperature() {
        return Optional.ofNullable(heaters).stream()
            .flatMap(List::stream)
            .filter(Objects::nonNull)
            .map(Heater::scheduledTemperature)
            .filter(Objects::nonNull)
            .max(Double::compare);
    }

    public record Reading(Double value, LocalDateTime updatedAt) {
    }

    public record Heater(Double scheduledTemperature) {
    }
}
