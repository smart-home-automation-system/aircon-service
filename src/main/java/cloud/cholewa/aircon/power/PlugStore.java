package cloud.cholewa.aircon.power;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//The last reading of each plug (HAS-390), in memory, and since when a plug does not answer. A reading older than
//FRESH_FOR - four passes of 30 s - is not the plug now: what it says is not known, never 0 W
@Component
public class PlugStore {

    static final Duration FRESH_FOR = Duration.ofMinutes(2);

    private final Map<PlugName, PlugReading> readings = new ConcurrentHashMap<>();
    private final Map<PlugName, LocalDateTime> failingSince = new ConcurrentHashMap<>();

    //true when the plug did not answer before - it answers again
    boolean answered(final PlugReading reading) {
        readings.put(reading.plug(), reading);
        return failingSince.remove(reading.plug()) != null;
    }

    //true when this is the first failure in a row
    boolean failed(final PlugName plug, final LocalDateTime at) {
        return failingSince.putIfAbsent(plug, at) == null;
    }

    public Optional<PlugReading> last(final PlugName plug) {
        return Optional.ofNullable(readings.get(plug));
    }

    //the reading of the plug while it is the plug now
    public Optional<PlugReading> fresh(final PlugName plug, final LocalDateTime now) {
        return last(plug).filter(reading -> !reading.at().isBefore(now.minus(FRESH_FOR)));
    }

    public Optional<LocalDateTime> failingSince(final PlugName plug) {
        return Optional.ofNullable(failingSince.get(plug));
    }
}
