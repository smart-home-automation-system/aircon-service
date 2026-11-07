package cloud.cholewa.aircon.command;

import cloud.cholewa.aircon.state.ChangeSource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

//What the commands came to, per unit, in memory (HAS-382): whether the last command was taken (null while it is being
//confirmed, or before any), when the guard last raised a cooling target to the minimum, and who sent the command the
//next read is expected to show - so the change it makes is written in the history under its sender, never as the
//remote's. Every command of a unit has its number: only the confirmation of the last one tells what the unit did - a
//second command sent before the first was confirmed makes the confirmation of the first count for nothing
@Component
public class CommandOutcomes {

    private final Map<Long, Boolean> confirmed = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> minimumRestored = new ConcurrentHashMap<>();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> issued = new ConcurrentHashMap<>();

    private record Pending(ChangeSource source, LocalDateTime until) {
    }

    //a new command of a unit: its number, and nothing known of what it came to until its confirmation
    public long issue(final long unitId) {
        confirmed.remove(unitId);
        return issued.computeIfAbsent(unitId, ignored -> new AtomicLong()).incrementAndGet();
    }

    //a command was sent: the change of the next read until a time is its sender's
    public void expect(final long unitId, final ChangeSource source, final LocalDateTime until) {
        pending.put(unitId, new Pending(source, until));
    }

    //the sender of the change a read shows, when a command was sent and not yet seen; taken once
    public Optional<ChangeSource> takePending(final long unitId, final LocalDateTime at) {
        return Optional.ofNullable(pending.remove(unitId))
            .filter(expected -> !at.isAfter(expected.until()))
            .map(Pending::source);
    }

    //what the command of a number came to - kept only while it is the last command of the unit
    public void confirmed(final long unitId, final long number, final boolean taken) {
        Optional.ofNullable(issued.get(unitId))
            .filter(last -> last.get() == number)
            .ifPresent(last -> confirmed.put(unitId, taken));
    }

    public Optional<Boolean> lastCommandConfirmed(final long unitId) {
        return Optional.ofNullable(confirmed.get(unitId));
    }

    public void minimumRestored(final long unitId, final LocalDateTime at) {
        minimumRestored.put(unitId, at);
    }

    public Optional<LocalDateTime> minimumRestoredAt(final long unitId) {
        return Optional.ofNullable(minimumRestored.get(unitId));
    }
}
