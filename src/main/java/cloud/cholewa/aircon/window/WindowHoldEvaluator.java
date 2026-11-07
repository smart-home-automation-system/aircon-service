package cloud.cholewa.aircon.window;

import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore.Change;
import cloud.cholewa.aircon.unit.UnitStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

//What the window of its room asks of one unit this minute (HAS-384) - pure, judged against the last stop of the unit
//(aircon_window_hold, the latest row), the last read and the last change the reads saw:
//- no open stop: a window that holds stops a unit known to be on and answering - once per opening: the opening of a
//  stop given up on (cancelled) or ended is never held again; a unit off, not known to be on, or not answering is left
//  alone;
//- an open stop: a change of what the unit does made by anybody but the window since the stop (the remote, the
//  dashboard, a rule) gives it up; while the window still holds, a unit on again without such a change did not take the
//  stop - it is stopped again once it answers (a unit that does not answer is not sent a command every minute); the
//  windows of the room closed within restore-within of the stop set it back, later the stop expires and the unit stays
//  off; a window not known (heating-service silent, a sensor unknown, an opening not held yet) waits, but never beyond
//  restore-within
public final class WindowHoldEvaluator {

    public enum Action { NONE, HOLD, STOP_AGAIN, RESTORE, EXPIRE, CANCEL }

    //the changes that are not somebody changing the unit: the window's own commands, and the start of the history
    private static final Set<ChangeSource> NOT_A_PERSON = Set.of(ChangeSource.WINDOW, ChangeSource.FIRST_READ);

    private WindowHoldEvaluator() {
    }

    //what is known of a unit: what it last reported, and whether its last read answered (null - not read since a start)
    public record Unit(UnitStatus status, Boolean reachable) {

        //on, and not known to be out: a command to it can be taken
        boolean onAndAnswering() {
            return status != null && Boolean.TRUE.equals(status.power()) && !Boolean.FALSE.equals(reachable);
        }
    }

    //window, latest and lastChange are null when not known
    public static Action decide(final RoomWindow window, final Unit unit, final WindowHold latest,
                                final Change lastChange, final LocalDateTime now, final Duration restoreWithin) {
        if (latest != null && latest.open()) {
            return decideOpen(window, unit, latest, lastChange, now, restoreWithin);
        }
        final boolean handled = latest != null && window != null
            && Objects.equals(latest.windowOpenSince(), window.openSince());
        return window != null && window.holds() && unit.onAndAnswering() && !handled ? Action.HOLD : Action.NONE;
    }

    private static Action decideOpen(final RoomWindow window, final Unit unit, final WindowHold hold,
                                     final Change lastChange, final LocalDateTime now, final Duration restoreWithin) {
        if (changedByAPerson(lastChange, hold.heldSince())) {
            return Action.CANCEL;
        }
        if (window != null && window.holds()) {
            return unit.onAndAnswering() ? Action.STOP_AGAIN : Action.NONE;
        }
        final boolean inTime = !now.isAfter(hold.heldSince().plus(restoreWithin));
        if (!inTime) {
            return Action.EXPIRE;
        }
        return window != null && window.closed() ? Action.RESTORE : Action.NONE;
    }

    static boolean changedByAPerson(final Change lastChange, final LocalDateTime heldSince) {
        return lastChange != null && !NOT_A_PERSON.contains(lastChange.source()) && lastChange.at().isAfter(heldSince);
    }
}
