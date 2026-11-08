package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.command.CommandRules;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore.Change;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.Mode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

//What the rule of a unit asks of it this minute (HAS-386, HAS-387) - pure.
//Keep below: the room above the threshold - cool at the target, unless the unit cools already at a target not above the
//threshold (one cooling at 26 C never keeps 25); below the threshold minus the hysteresis - off, but only a unit the rule
//switched on to cool (its last change the rule's, still on in cool - a target the rule lowered makes the unit the
//rule's). Needs a reading of the room (heating-service, not older than freshFor - the own sensor of a unit reads low,
//owner 2026-11-07).
//Heat by schedule (HAS-387): the house switch of heating-service on and the schedule of the room asking for a temperature
//the unit can hold (16 C or more once rounded - below it is "do not heat", owner 2026-11-08) - heat at that temperature,
//unless it heats at it already; otherwise - off, but only a unit the rule switched on to heat. The unit holds its own
//target, so no reading of the room is needed - and the inSchedule of heating-service, which goes once the room is warm,
//is not read. Not known (heating-service not read) is never acted on. While keep below is on, the target never goes
//above its threshold minus the hysteresis - heating the room into the cooling would swing it between the two.
//Keep below wins: while it is on and the room is above its threshold, the heating asks for nothing.
//Never: for a unit not answering or not read, within manualHold of a change a person made (the remote, the dashboard),
//nor - for the cooling and the heating - while something else holds the unit off (the grid lost, the window of its
//room); the offs still go. Independent of who is at home
public final class RuleEvaluator {

    public enum Action { NONE, COOL, OFF, HEAT, STOP_HEATING }

    //what heating-service says of the room of a unit: the house switch, and what its schedule asks for now - null while
    //no schedule covers the room
    public record Heating(boolean houseOn, Double scheduledTemperature) {
    }

    //the changes a person makes: they hold the rule off for manualHold
    private static final Set<ChangeSource> A_PERSON = Set.of(ChangeSource.REMOTE, ChangeSource.DASHBOARD);

    //the last changes that leave a unit the rule's: its own, and the window setting back what it stopped (HAS-387) - or a
    //unit heated by the schedule, stopped by a window and set back, would heat on after its schedule ended. A unit a person
    //switched on and the window set back is then the rule's too (accepted, as the target the rule lowers)
    private static final Set<ChangeSource> THE_RULES = Set.of(ChangeSource.RULE, ChangeSource.WINDOW);

    private RuleEvaluator() {
    }

    //roomTemperature null when not known (no reading, or one older than freshFor); heating null when not known (a read of
    //heating-service failed); lastChange null when not known
    public static Action decide(final AirconRule rule, final UnitState unit, final Double roomTemperature,
                                final Heating heating, final Change lastChange, final boolean heldOff,
                                final LocalDateTime now, final Duration manualHold) {
        if (unit == null || unit.status() == null || Boolean.FALSE.equals(unit.reachable())
            || personChangedIt(lastChange, now, manualHold)) {
            return Action.NONE;
        }
        final Action keepBelow = keepBelow(rule, unit, roomTemperature, lastChange, heldOff);
        if (keepBelow != Action.NONE || roomAboveTheThreshold(rule, roomTemperature)) {
            return keepBelow;
        }
        return heatBySchedule(rule, unit, heating, lastChange, heldOff);
    }

    //the temperature the unit heats at for the schedule, or nothing when it does not heat: the house on, a schedule
    //asking for 16 C or more (rounded half up to the whole degree of the unit), at most the threshold of keep below
    //minus its hysteresis while keep below is on
    public static Optional<Integer> heatTarget(final AirconRule rule, final Heating heating) {
        return Optional.ofNullable(heating)
            .filter(Heating::houseOn)
            .map(Heating::scheduledTemperature)
            .map(scheduled -> BigDecimal.valueOf(scheduled).setScale(0, RoundingMode.HALF_UP).intValue())
            .map(target -> Math.min(target, ceiling(rule)))
            .filter(target -> target >= CommandRules.TARGET_MIN);
    }

    private static int ceiling(final AirconRule rule) {
        return rule.keepBelow().enabled()
            ? rule.keepBelow().threshold().subtract(rule.keepBelow().hysteresis()).setScale(0, RoundingMode.FLOOR).intValue()
            : CommandRules.TARGET_MAX;
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

    private static Action heatBySchedule(final AirconRule rule, final UnitState unit, final Heating heating,
                                         final Change lastChange, final boolean heldOff) {
        if (!rule.heatBySchedule().enabled() || heating == null) {
            return Action.NONE;
        }
        return heatTarget(rule, heating)
            .map(target -> heldOff || heatsAt(unit, target) ? Action.NONE : Action.HEAT)
            .orElseGet(() -> switchedOnByTheRule(unit, lastChange, Mode.HEAT) ? Action.STOP_HEATING : Action.NONE);
    }

    private static boolean roomAboveTheThreshold(final AirconRule rule, final Double roomTemperature) {
        return rule.keepBelow().enabled() && roomTemperature != null
            && BigDecimal.valueOf(roomTemperature).compareTo(rule.keepBelow().threshold()) > 0;
    }

    private static boolean switchedOnByTheRule(final UnitState unit, final Change lastChange, final Mode mode) {
        return Boolean.TRUE.equals(unit.status().power()) && unit.status().mode() == mode
            && lastChange != null && THE_RULES.contains(lastChange.source());
    }

    private static boolean heatsAt(final UnitState unit, final int target) {
        return Boolean.TRUE.equals(unit.status().power()) && unit.status().mode() == Mode.HEAT
            && Integer.valueOf(target).equals(unit.status().targetTemperature());
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
