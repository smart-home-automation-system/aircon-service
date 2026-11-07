package cloud.cholewa.aircon.monitor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

//What the availability monitor makes of the units and the alerts already open (HAS-383) - pure, pinned by
//AvailabilityEvaluatorTest, by the rules of the monitor of light-service. A unit is out once it has not answered for
//offlineAfter, since the first read of the run that did not answer. Out and not announced, it is an alert; announced
//and still out, a reminder every reminderEvery; announced and answering now, an info - never a unit only not known after
//a restart, which is not back until a read answers. Announced and no longer watched (removed from the registry), it is
//forgotten without a word
public final class AvailabilityEvaluator {

    private AvailabilityEvaluator() {
    }

    /**
     * A watched unit: since when it does not answer (null while it answers or is not known) and whether its reachability
     * is known - after a restart a unit is not back until a read answered.
     */
    public record Watched(long id, String name, String room, LocalDateTime unreachableSince, boolean known) {
    }

    /** An alert the household had. */
    public record Alert(long id, String name, String room, LocalDateTime since, LocalDateTime lastAlertAt) {
    }

    /** What is due: new alerts, reminders, returns, and the alerts of units no longer watched. */
    public record Decision(List<Alert> alerts, List<Alert> reminders, List<Alert> returns, List<Alert> forgotten) {

        public boolean nothing() {
            return alerts.isEmpty() && reminders.isEmpty() && returns.isEmpty() && forgotten.isEmpty();
        }
    }

    public static Decision decide(final List<Watched> watched, final Map<Long, Alert> open, final LocalDateTime now,
                                  final MonitorProperties properties) {
        final List<Alert> alerts = watched.stream()
            .filter(unit -> out(unit, now, properties) && !open.containsKey(unit.id()))
            .map(unit -> new Alert(unit.id(), unit.name(), unit.room(), unit.unreachableSince(), now))
            .toList();
        final List<Alert> reminders = watched.stream()
            .filter(unit -> out(unit, now, properties))
            .flatMap(unit -> Optional.ofNullable(open.get(unit.id())).stream())
            .filter(alert -> !alert.lastAlertAt().plus(properties.reminderEvery()).isAfter(now))
            .toList();
        final List<Alert> returns = watched.stream()
            .filter(unit -> unit.known() && unit.unreachableSince() == null)
            .flatMap(unit -> Optional.ofNullable(open.get(unit.id())).stream())
            .toList();
        final Set<Long> ids = watched.stream().map(Watched::id).collect(Collectors.toSet());
        final List<Alert> forgotten = open.values().stream().filter(alert -> !ids.contains(alert.id())).toList();
        return new Decision(alerts, reminders, returns, forgotten);
    }

    private static boolean out(final Watched unit, final LocalDateTime now, final MonitorProperties properties) {
        return unit.unreachableSince() != null && !unit.unreachableSince().plus(properties.offlineAfter()).isAfter(now);
    }
}
