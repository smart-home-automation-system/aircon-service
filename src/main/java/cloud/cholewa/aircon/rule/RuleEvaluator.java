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

//What the rule of a unit asks of it this minute (HAS-386, HAS-387) - pure.
//Keep below: the room above the threshold - cool at the target, unless the unit cools already at a target not above the
//threshold (one cooling at 26 C never keeps 25); below the threshold minus the hysteresis - off, but only a unit the rule
//switched on to cool (its last change the rule's, still on in cool - a target the rule lowered makes the unit the
//rule's). Needs a reading of the room (heating-service, not older than freshFor - the own sensor of a unit reads low,
//owner 2026-11-07).
//Heat by schedule (HAS-387) is a frost protection, a thermostat on the reading of the room (owner 2026-11-08: the
//comfort is switched on by hand): the room more than FROST_BAND below what the schedule of the room asks for - heat,
//the target of the unit left as it is (the owner keeps 21 C); the room at the schedule - off, but only a unit the rule
//switched on to heat, and not before it ran heatMinRun. No schedule covering the room - the heating of the rule off. A
//unit the rule switched off rests heatMinOff before it heats again. A unit heating already, whoever switched it on, is
//left alone. No reading of the room, or the schedule not known (heating-service not read) - nothing.
//Keep below wins: while it is on and the room is above its threshold, the heating asks for nothing.
//Never: for a unit not answering or not read, within manualHold of a change a person made (the remote, the dashboard),
//nor - for the cooling and the heating - while something else holds the unit off (the grid lost, the window of its
//room); the offs still go. Independent of who is at home
public final class RuleEvaluator {

    public enum Action { NONE, COOL, OFF, HEAT, STOP_HEATING }

    //what the schedule of the room of a unit asks for now, as heating-service says - null while no schedule covers it
    public record Heating(Double scheduledTemperature) {
    }

    //the room below the schedule by more than this starts the heating, the schedule reached stops it
    static final BigDecimal FROST_BAND = BigDecimal.ONE;

    //the changes a person makes: they hold the rule off for manualHold
    private static final Set<ChangeSource> A_PERSON = Set.of(ChangeSource.REMOTE, ChangeSource.DASHBOARD);

    //the last changes that leave a unit the rule's: its own, and the window setting back what it stopped - or a unit
    //heated by the rule, stopped by a window and set back, would heat on after its room was warm again. A unit a person
    //switched on and the window set back is then the rule's too (accepted, as the target the rule lowers)
    private static final Set<ChangeSource> THE_RULES = Set.of(ChangeSource.RULE, ChangeSource.WINDOW);

    private RuleEvaluator() {
    }

    //roomTemperature null when not known (no reading, or one older than freshFor); heating null when not known (a read of
    //heating-service failed, or the room not in it); lastChange null when not known
    public static Action decide(final AirconRule rule, final UnitState unit, final Double roomTemperature,
                                final Heating heating, final Change lastChange, final boolean heldOff,
                                final LocalDateTime now, final RuleProperties timing) {
        if (unit == null || unit.status() == null || Boolean.FALSE.equals(unit.reachable())
            || personChangedIt(lastChange, now, timing.manualHold())) {
            return Action.NONE;
        }
        final Action keepBelow = keepBelow(rule, unit, roomTemperature, lastChange, heldOff);
        if (keepBelow != Action.NONE || roomAboveTheThreshold(rule, roomTemperature)) {
            return keepBelow;
        }
        return frostProtection(rule, unit, roomTemperature, heating, lastChange, heldOff, now, timing);
    }

    private static Action keepBelow(final AirconRule rule, final UnitState unit, final Double roomTemperature,
                                    final Change lastChange, final boolean heldOff) {
        if (!rule.keepBelow().enabled() || roomTemperature == null) {
            return Action.NONE;
        }
        final BigDecimal room = BigDecimal.valueOf(roomTemperature);
        final AirconRule.KeepBelow keepBelow = rule.keepBelow();
        if (room.compareTo(keepBelow.threshold()) > 0) {
            return heldOff || coolsBelowTheThreshold(unit, keepBelow) ? Action.NONE : Action.COOL;
        }
        return room.compareTo(keepBelow.threshold().subtract(keepBelow.hysteresis())) < 0
            && switchedOnByTheRule(unit, lastChange, Mode.COOL)
            ? Action.OFF
            : Action.NONE;
    }

    private static Action frostProtection(final AirconRule rule, final UnitState unit, final Double roomTemperature,
                                          final Heating heating, final Change lastChange, final boolean heldOff,
                                          final LocalDateTime now, final RuleProperties timing) {
        if (!rule.heatBySchedule().enabled() || heating == null) {
            return Action.NONE;
        }
        if (heating.scheduledTemperature() == null) {
            return stopsHeating(unit, lastChange, now, timing) ? Action.STOP_HEATING : Action.NONE;
        }
        if (roomTemperature == null) {
            return Action.NONE;
        }
        final BigDecimal room = BigDecimal.valueOf(roomTemperature);
        final BigDecimal scheduled = BigDecimal.valueOf(heating.scheduledTemperature());
        if (room.compareTo(scheduled.subtract(FROST_BAND)) < 0) {
            return heldOff || heats(unit) || restsAfterTheRule(unit, lastChange, now, timing) ? Action.NONE : Action.HEAT;
        }
        return room.compareTo(scheduled) >= 0 && stopsHeating(unit, lastChange, now, timing)
            ? Action.STOP_HEATING
            : Action.NONE;
    }

    //a heating the rule switched on, run for heatMinRun since
    private static boolean stopsHeating(final UnitState unit, final Change lastChange, final LocalDateTime now,
                                        final RuleProperties timing) {
        return switchedOnByTheRule(unit, lastChange, Mode.HEAT)
            && !lastChange.at().isAfter(now.minus(timing.heatMinRun()));
    }

    //a unit off, switched off by the rule less than heatMinOff ago
    private static boolean restsAfterTheRule(final UnitState unit, final Change lastChange, final LocalDateTime now,
                                             final RuleProperties timing) {
        return !Boolean.TRUE.equals(unit.status().power())
            && lastChange != null && lastChange.source() == ChangeSource.RULE
            && lastChange.at().isAfter(now.minus(timing.heatMinOff()));
    }

    private static boolean roomAboveTheThreshold(final AirconRule rule, final Double roomTemperature) {
        return rule.keepBelow().enabled() && roomTemperature != null
            && BigDecimal.valueOf(roomTemperature).compareTo(rule.keepBelow().threshold()) > 0;
    }

    private static boolean switchedOnByTheRule(final UnitState unit, final Change lastChange, final Mode mode) {
        return Boolean.TRUE.equals(unit.status().power()) && unit.status().mode() == mode
            && lastChange != null && THE_RULES.contains(lastChange.source());
    }

    private static boolean heats(final UnitState unit) {
        return Boolean.TRUE.equals(unit.status().power()) && unit.status().mode() == Mode.HEAT;
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
