package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore.Change;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.Mode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

//What the rule of a unit asks of it this minute (HAS-386) - pure. Keep below: the room above the threshold - cool at the
//target, unless the unit cools already at a target not above the threshold (one cooling at 26 C never keeps 25);
//below the threshold minus the hysteresis - off, but only a unit the rule switched on (its last change the rule's,
//still on - a target the rule lowered makes the unit the rule's). Never: without a reading of the room (heating-service,
//not older than freshFor - the own sensor of a unit reads low, owner 2026-11-07), for a unit not answering or not read, within
//manualHold of a change a person made (the remote, the dashboard), nor - for the cooling - while something else holds the
//unit off (the grid lost, the window of its room). Independent of who is at home
public final class RuleEvaluator {

    public enum Action { NONE, COOL, OFF }

    //the changes a person makes: they hold the rule off for manualHold
    private static final Set<ChangeSource> A_PERSON = Set.of(ChangeSource.REMOTE, ChangeSource.DASHBOARD);

    private RuleEvaluator() {
    }

    //roomTemperature null when not known (no reading, or one older than freshFor); lastChange null when not known
    public static Action decide(final AirconRule rule, final UnitState unit, final Double roomTemperature,
                                final Change lastChange, final boolean heldOff, final LocalDateTime now,
                                final Duration manualHold) {
        if (!rule.keepBelow().enabled() || roomTemperature == null || unit == null || unit.status() == null
            || Boolean.FALSE.equals(unit.reachable()) || personChangedIt(lastChange, now, manualHold)) {
            return Action.NONE;
        }
        final BigDecimal room = BigDecimal.valueOf(roomTemperature);
        final AirconRule.KeepBelow keepBelow = rule.keepBelow();
        final boolean on = Boolean.TRUE.equals(unit.status().power());
        if (room.compareTo(keepBelow.threshold()) > 0) {
            return heldOff || coolsBelowTheThreshold(unit, keepBelow) ? Action.NONE : Action.COOL;
        }
        final boolean switchedOnByTheRule = on && lastChange != null && lastChange.source() == ChangeSource.RULE;
        return room.compareTo(keepBelow.threshold().subtract(keepBelow.hysteresis())) < 0 && switchedOnByTheRule
            ? Action.OFF
            : Action.NONE;
    }

    private static boolean coolsBelowTheThreshold(final UnitState unit, final AirconRule.KeepBelow keepBelow) {
        return Boolean.TRUE.equals(unit.status().power()) && unit.status().mode() == Mode.COOL
            && unit.status().targetTemperature() != null
            && BigDecimal.valueOf(unit.status().targetTemperature()).compareTo(keepBelow.threshold()) <= 0;
    }

    static boolean personChangedIt(final Change lastChange, final LocalDateTime now, final Duration manualHold) {
        return lastChange != null && A_PERSON.contains(lastChange.source())
            && lastChange.at().isAfter(now.minus(manualHold));
    }
}
