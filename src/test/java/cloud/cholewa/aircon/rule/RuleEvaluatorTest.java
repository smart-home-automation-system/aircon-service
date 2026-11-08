package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.rule.RuleEvaluator.Action;
import cloud.cholewa.aircon.rule.RuleEvaluator.Heating;
import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore.Change;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 15, 0);
    private static final Duration HOLD = Duration.ofMinutes(30);
    private static final AirconRule SANCTUM = rule(true);

    @Test
    void should_cool_a_room_above_the_threshold() {
        assertThat(decide(off(), 25.5, null, false)).isEqualTo(Action.COOL);
        assertThat(decide(on(Mode.HEAT), 25.5, null, false)).as("heating").isEqualTo(Action.COOL);
    }

    @Test
    void should_leave_a_room_at_the_threshold_or_a_unit_cooling_already() {
        assertThat(decide(off(), 25.0, null, false)).isEqualTo(Action.NONE);
        assertThat(decide(on(Mode.COOL), 27.0, null, false)).isEqualTo(Action.NONE);
    }

    //cooling at a target above the threshold never keeps the room below it
    @Test
    void should_lower_a_cooling_target_above_the_threshold() {
        final UnitState coolingAt26 = new UnitState(new UnitStatus(true, Mode.COOL, 26, 15, FanSpeed.AUTO, 0, 0, false,
            false, false, true, null), true, NOW, null);

        assertThat(decide(coolingAt26, 26.5, new Change(ChangeSource.REMOTE, NOW.minusHours(1)), false))
            .isEqualTo(Action.COOL);
    }

    @Test
    void should_switch_off_below_the_hysteresis_a_unit_the_rule_switched_on() {
        assertThat(decide(on(Mode.COOL), 23.9, ruleChange(), false)).isEqualTo(Action.OFF);
        assertThat(decide(on(Mode.COOL), 24.0, ruleChange(), false)).as("at the edge").isEqualTo(Action.NONE);
    }

    //a unit a person switched on is never switched off by the rule
    @Test
    void should_not_switch_off_a_unit_the_rule_did_not_switch_on() {
        assertThat(decide(on(Mode.COOL), 20.0, new Change(ChangeSource.REMOTE, NOW.minusHours(2)), false))
            .isEqualTo(Action.NONE);
        assertThat(decide(on(Mode.COOL), 20.0, null, false)).isEqualTo(Action.NONE);
    }

    @ParameterizedTest
    @EnumSource(value = ChangeSource.class, names = {"REMOTE", "DASHBOARD"})
    void should_hold_off_within_thirty_minutes_of_a_change_of_a_person(final ChangeSource person) {
        assertThat(decide(off(), 27.0, new Change(person, NOW.minusMinutes(29)), false)).isEqualTo(Action.NONE);
        assertThat(decide(off(), 27.0, new Change(person, NOW.minusMinutes(31)), false)).isEqualTo(Action.COOL);
    }

    //the empty house, the window and a lost grid are no person
    @ParameterizedTest
    @EnumSource(value = ChangeSource.class, names = {"EMPTY_HOUSE", "WINDOW", "GRID_OUTAGE", "MINIMUM_RESTORED",
        "FIRST_READ"})
    void should_not_hold_off_for_a_change_of_the_service(final ChangeSource source) {
        assertThat(decide(off(), 27.0, new Change(source, NOW.minusMinutes(1)), false)).isEqualTo(Action.COOL);
    }

    @Test
    void should_never_decide_without_a_reading_or_a_unit_answering() {
        assertThat(decide(off(), null, null, false)).isEqualTo(Action.NONE);
        assertThat(RuleEvaluator.decide(SANCTUM, null, 27.0, null, null, false, NOW, HOLD)).isEqualTo(Action.NONE);
        assertThat(RuleEvaluator.decide(SANCTUM, new UnitState(COOLING_OFF, false, NOW, NOW), 27.0, null, null, false, NOW,
            HOLD)).isEqualTo(Action.NONE);
        assertThat(RuleEvaluator.decide(SANCTUM, new UnitState(null, false, null, NOW), 27.0, null, null, false, NOW, HOLD))
            .isEqualTo(Action.NONE);
    }

    @Test
    void should_not_cool_while_something_holds_the_unit_off() {
        assertThat(decide(off(), 27.0, null, true)).isEqualTo(Action.NONE);
        assertThat(decide(on(Mode.COOL), 20.0, ruleChange(), true)).as("the off still goes").isEqualTo(Action.OFF);
    }

    @Test
    void should_do_nothing_for_a_rule_that_is_off() {
        assertThat(RuleEvaluator.decide(rule(false), new UnitState(COOLING_OFF, true, NOW, null), 27.0, null, null, false,
            NOW, HOLD)).isEqualTo(Action.NONE);
    }

    //keep below switches off only a unit it switched on to cool - never the heating of the schedule (HAS-387)
    @Test
    void should_not_switch_off_a_unit_the_rule_heats() {
        assertThat(decide(on(Mode.HEAT), 20.0, ruleChange(), false)).isEqualTo(Action.NONE);
    }

    //HAS-387: the schedule asks for 21 C while the house is on - heat at 21 C, no reading of the room needed
    @Test
    void should_heat_at_what_the_schedule_asks_for() {
        assertThat(heat(off(), null, HOUSE_ON_21, null, false)).isEqualTo(Action.HEAT);
        assertThat(heat(on(Mode.COOL), 22.0, HOUSE_ON_21, null, false)).as("cooling below the threshold")
            .isEqualTo(Action.HEAT);
        assertThat(RuleEvaluator.heatTarget(HEATING, HOUSE_ON_21)).contains(21);
    }

    @Test
    void should_leave_a_unit_heating_at_the_target_already() {
        assertThat(heat(heating(21), null, HOUSE_ON_21, null, false)).isEqualTo(Action.NONE);
        assertThat(heat(heating(23), null, HOUSE_ON_21, null, false)).as("another target").isEqualTo(Action.HEAT);
    }

    //a schedule by half a degree, rounded half up to the whole degree of the unit
    @Test
    void should_round_a_schedule_to_the_whole_degree() {
        assertThat(RuleEvaluator.heatTarget(HEATING, new Heating(true, 20.5))).contains(21);
        assertThat(RuleEvaluator.heatTarget(HEATING, new Heating(true, 20.0))).contains(20);
    }

    //below 16 C the unit cannot heat: "do not heat" (owner 2026-11-08) - the base level of 9 C of sanctum
    @Test
    void should_not_heat_for_a_schedule_below_sixteen() {
        assertThat(RuleEvaluator.heatTarget(HEATING, new Heating(true, 9.0))).isEmpty();
        assertThat(RuleEvaluator.heatTarget(HEATING, new Heating(true, 15.5))).contains(16);
        assertThat(RuleEvaluator.heatTarget(HEATING, new Heating(true, 15.0))).isEmpty();
        assertThat(heat(off(), null, new Heating(true, 9.0), null, false)).isEqualTo(Action.NONE);
    }

    //while keep below is on, the heating never goes above its threshold minus the hysteresis (25 - 1)
    @Test
    void should_heat_at_most_to_the_threshold_minus_the_hysteresis() {
        assertThat(RuleEvaluator.heatTarget(HEATING, new Heating(true, 26.0))).contains(24);
        assertThat(RuleEvaluator.heatTarget(new AirconRule(144, new AirconRule.KeepBelow(false, new BigDecimal("25.0"),
            new BigDecimal("1.0"), 22), new AirconRule.HeatBySchedule(true), NOW), new Heating(true, 26.0))).contains(26);
    }

    @Test
    void should_switch_off_the_heating_it_switched_on_when_the_schedule_ends() {
        assertThat(heat(heating(21), null, new Heating(true, null), ruleChange(), false)).as("no schedule now")
            .isEqualTo(Action.STOP_HEATING);
        assertThat(heat(heating(21), null, new Heating(false, 21.0), ruleChange(), false)).as("the house off")
            .isEqualTo(Action.STOP_HEATING);
        assertThat(heat(heating(21), null, new Heating(true, 9.0), ruleChange(), false)).as("below 16")
            .isEqualTo(Action.STOP_HEATING);
    }

    //a heating the window stopped and set back is still the rule's: its schedule ending switches it off
    @Test
    void should_switch_off_a_heating_the_window_set_back() {
        assertThat(heat(heating(21), null, new Heating(true, null), new Change(ChangeSource.WINDOW, NOW.minusHours(1)),
            false)).isEqualTo(Action.STOP_HEATING);
        assertThat(decide(on(Mode.COOL), 23.0, new Change(ChangeSource.WINDOW, NOW.minusHours(1)), false))
            .as("the cooling as well").isEqualTo(Action.OFF);
    }

    //the rule switches off only what it switched on to heat
    @Test
    void should_not_switch_off_a_heating_it_did_not_switch_on() {
        assertThat(heat(heating(21), null, new Heating(true, null), new Change(ChangeSource.REMOTE, NOW.minusHours(2)),
            false)).isEqualTo(Action.NONE);
        assertThat(heat(on(Mode.COOL), null, new Heating(true, null), ruleChange(), false)).as("cooling")
            .isEqualTo(Action.NONE);
        assertThat(heat(off(), null, new Heating(true, null), ruleChange(), false)).as("off").isEqualTo(Action.NONE);
    }

    //heating-service not read: nothing, neither the heating nor its off
    @Test
    void should_never_act_on_a_heating_not_known() {
        assertThat(heat(off(), null, null, null, false)).isEqualTo(Action.NONE);
        assertThat(heat(heating(21), null, null, ruleChange(), false)).isEqualTo(Action.NONE);
    }

    @Test
    void should_not_heat_while_something_holds_the_unit_off() {
        assertThat(heat(off(), null, HOUSE_ON_21, null, true)).isEqualTo(Action.NONE);
        assertThat(heat(heating(21), null, new Heating(true, null), ruleChange(), true)).as("the off still goes")
            .isEqualTo(Action.STOP_HEATING);
    }

    @Test
    void should_not_heat_within_thirty_minutes_of_a_change_of_a_person() {
        assertThat(heat(off(), null, HOUSE_ON_21, new Change(ChangeSource.REMOTE, NOW.minusMinutes(29)), false))
            .isEqualTo(Action.NONE);
        assertThat(heat(off(), null, HOUSE_ON_21, new Change(ChangeSource.REMOTE, NOW.minusMinutes(31)), false))
            .isEqualTo(Action.HEAT);
    }

    //keep below wins: the room above its threshold is cooled, never heated - also while the unit cools already
    @Test
    void should_let_keep_below_win() {
        assertThat(heat(off(), 25.5, HOUSE_ON_21, null, false)).isEqualTo(Action.COOL);
        assertThat(heat(on(Mode.COOL), 25.5, HOUSE_ON_21, null, false)).as("cooling already").isEqualTo(Action.NONE);
        assertThat(heat(off(), 25.5, HOUSE_ON_21, null, true)).as("held off").isEqualTo(Action.NONE);
        assertThat(heat(off(), 24.5, HOUSE_ON_21, null, false)).as("below the threshold").isEqualTo(Action.HEAT);
    }

    @Test
    void should_not_heat_by_a_rule_that_does_not() {
        assertThat(RuleEvaluator.decide(SANCTUM, off(), null, HOUSE_ON_21, null, false, NOW, HOLD))
            .isEqualTo(Action.NONE);
    }

    private static final UnitStatus COOLING_OFF = status(false, Mode.COOL);
    private static final AirconRule HEATING = new AirconRule(144, new AirconRule.KeepBelow(true, new BigDecimal("25.0"),
        new BigDecimal("1.0"), 22), new AirconRule.HeatBySchedule(true), NOW);
    private static final Heating HOUSE_ON_21 = new Heating(true, 21.0);

    private static Action heat(final UnitState unit, final Double room, final Heating heating, final Change change,
                               final boolean heldOff) {
        return RuleEvaluator.decide(HEATING, unit, room, heating, change, heldOff, NOW, HOLD);
    }

    private static UnitState heating(final int target) {
        return new UnitState(new UnitStatus(true, Mode.HEAT, target, 15, FanSpeed.AUTO, 0, 0, false, false, false, true,
            null), true, NOW, null);
    }

    private static Action decide(final UnitState unit, final Double room, final Change change, final boolean heldOff) {
        return RuleEvaluator.decide(SANCTUM, unit, room, null, change, heldOff, NOW, HOLD);
    }

    private static Change ruleChange() {
        return new Change(ChangeSource.RULE, NOW.minusHours(1));
    }

    private static UnitState off() {
        return new UnitState(status(false, Mode.HEAT), true, NOW, null);
    }

    private static UnitState on(final Mode mode) {
        return new UnitState(status(true, mode), true, NOW, null);
    }

    private static UnitStatus status(final boolean power, final Mode mode) {
        return new UnitStatus(power, mode, 22, 15, FanSpeed.AUTO, 0, 0, false, false, false, true, null);
    }

    private static AirconRule rule(final boolean enabled) {
        return new AirconRule(144, new AirconRule.KeepBelow(enabled, new BigDecimal("25.0"), new BigDecimal("1.0"), 22),
            new AirconRule.HeatBySchedule(false), NOW);
    }
}
