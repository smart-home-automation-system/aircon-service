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
    private static final RuleProperties TIMING = new RuleProperties(Duration.ofMinutes(30), Duration.ofHours(3),
        Duration.ofMinutes(30), Duration.ofMinutes(10));
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
        assertThat(RuleEvaluator.decide(SANCTUM, null, 27.0, null, null, false, NOW, TIMING)).isEqualTo(Action.NONE);
        assertThat(RuleEvaluator.decide(SANCTUM, new UnitState(COOLING_OFF, false, NOW, NOW), 27.0, null, null, false, NOW,
            TIMING)).isEqualTo(Action.NONE);
        assertThat(RuleEvaluator.decide(SANCTUM, new UnitState(null, false, null, NOW), 27.0, null, null, false, NOW, TIMING))
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
            NOW, TIMING)).isEqualTo(Action.NONE);
    }

    //keep below switches off only a unit it switched on to cool - never the heating of the schedule (HAS-387)
    @Test
    void should_not_switch_off_a_unit_the_rule_heats() {
        assertThat(decide(on(Mode.HEAT), 20.0, ruleChange(), false)).isEqualTo(Action.NONE);
    }

    //HAS-387, the frost protection: sanctum more than a degree below the 9 C of its schedule - heating, no target sent
    @Test
    void should_heat_a_room_more_than_a_degree_below_its_schedule() {
        assertThat(heat(off(), 7.9, NINE, null, false)).isEqualTo(Action.HEAT);
        assertThat(heat(off(), 8.0, NINE, null, false)).as("at the edge").isEqualTo(Action.NONE);
        assertThat(heat(on(Mode.COOL), 7.0, NINE, null, false)).as("cooling").isEqualTo(Action.HEAT);
    }

    //a unit heating already - whoever switched it on, at whatever target - is left alone
    @Test
    void should_leave_a_unit_heating_already() {
        assertThat(heat(heating(21), 5.0, NINE, null, false)).isEqualTo(Action.NONE);
        assertThat(heat(heating(16), 5.0, NINE, ruleChange(), false)).isEqualTo(Action.NONE);
    }

    //the schedule reached: off the heating the rule switched on - not before it ran 30 minutes
    @Test
    void should_switch_off_its_heating_at_the_schedule_after_thirty_minutes() {
        assertThat(heat(heating(21), 9.0, NINE, changeAgo(ChangeSource.RULE, 31), false)).isEqualTo(Action.STOP_HEATING);
        assertThat(heat(heating(21), 12.0, NINE, changeAgo(ChangeSource.RULE, 30), false)).as("30 min exactly")
            .isEqualTo(Action.STOP_HEATING);
        assertThat(heat(heating(21), 12.0, NINE, changeAgo(ChangeSource.RULE, 29), false)).as("still the minimum run")
            .isEqualTo(Action.NONE);
        assertThat(heat(heating(21), 8.9, NINE, changeAgo(ChangeSource.RULE, 60), false)).as("below the schedule")
            .isEqualTo(Action.NONE);
    }

    @Test
    void should_switch_off_its_heating_when_no_schedule_covers_the_room() {
        assertThat(heat(heating(21), 5.0, NO_SCHEDULE, ruleChange(), false)).isEqualTo(Action.STOP_HEATING);
        assertThat(heat(heating(21), null, NO_SCHEDULE, ruleChange(), false)).as("no reading needed")
            .isEqualTo(Action.STOP_HEATING);
        assertThat(heat(heating(21), 5.0, NO_SCHEDULE, changeAgo(ChangeSource.RULE, 10), false)).as("the minimum run")
            .isEqualTo(Action.NONE);
        assertThat(heat(off(), 5.0, NO_SCHEDULE, null, false)).as("nothing to heat by").isEqualTo(Action.NONE);
    }

    //a unit the rule switched off rests 10 minutes before the rule heats it again
    @Test
    void should_rest_a_unit_it_switched_off_for_ten_minutes() {
        assertThat(heat(off(), 7.0, NINE, changeAgo(ChangeSource.RULE, 9), false)).isEqualTo(Action.NONE);
        assertThat(heat(off(), 7.0, NINE, changeAgo(ChangeSource.RULE, 10), false)).isEqualTo(Action.HEAT);
        assertThat(heat(off(), 7.0, NINE, changeAgo(ChangeSource.WINDOW, 1), false)).as("not an off of the rule")
            .isEqualTo(Action.HEAT);
    }

    //a heating the window stopped and set back is still the rule's: the schedule reached switches it off
    @Test
    void should_switch_off_a_heating_the_window_set_back() {
        assertThat(heat(heating(21), 9.5, NINE, changeAgo(ChangeSource.WINDOW, 60), false)).isEqualTo(Action.STOP_HEATING);
        assertThat(decide(on(Mode.COOL), 23.0, new Change(ChangeSource.WINDOW, NOW.minusHours(1)), false))
            .as("the cooling as well").isEqualTo(Action.OFF);
    }

    //the comfort the owner switches on by hand is never switched off - also after the 30 minutes of the person hold
    @Test
    void should_not_switch_off_a_heating_it_did_not_switch_on() {
        assertThat(heat(heating(21), 20.0, NINE, changeAgo(ChangeSource.REMOTE, 120), false)).isEqualTo(Action.NONE);
        assertThat(heat(heating(21), 20.0, NINE, changeAgo(ChangeSource.DASHBOARD, 120), false)).isEqualTo(Action.NONE);
        assertThat(heat(heating(21), 20.0, NINE, null, false)).as("not known").isEqualTo(Action.NONE);
        assertThat(heat(on(Mode.COOL), 20.0, NINE, ruleChange(), false))
            .as("cooling: the off of keep below").isEqualTo(Action.OFF);
    }

    //no reading of the room, or heating-service not read: nothing
    @Test
    void should_never_act_without_a_reading_or_a_schedule_known() {
        assertThat(heat(off(), null, NINE, null, false)).isEqualTo(Action.NONE);
        assertThat(heat(heating(21), null, NINE, ruleChange(), false)).isEqualTo(Action.NONE);
        assertThat(heat(off(), 5.0, null, null, false)).isEqualTo(Action.NONE);
        assertThat(heat(heating(21), 12.0, null, ruleChange(), false)).isEqualTo(Action.NONE);
    }

    @Test
    void should_not_heat_while_something_holds_the_unit_off() {
        assertThat(heat(off(), 5.0, NINE, null, true)).isEqualTo(Action.NONE);
        assertThat(heat(heating(21), 9.0, NINE, ruleChange(), true)).as("the off still goes")
            .isEqualTo(Action.STOP_HEATING);
    }

    //a unit a person switched off is protected again once the 30 minutes of the hold are over
    @Test
    void should_not_heat_within_thirty_minutes_of_a_change_of_a_person() {
        assertThat(heat(off(), 7.0, NINE, changeAgo(ChangeSource.REMOTE, 29), false)).isEqualTo(Action.NONE);
        assertThat(heat(off(), 7.0, NINE, changeAgo(ChangeSource.REMOTE, 31), false)).isEqualTo(Action.HEAT);
    }

    //keep below wins: the room above its threshold is cooled, never heated - also with a schedule above it
    @Test
    void should_let_keep_below_win() {
        final Heating above = new Heating(27.0);
        assertThat(heat(off(), 25.5, above, null, false)).isEqualTo(Action.COOL);
        assertThat(heat(on(Mode.COOL), 25.5, above, null, false)).as("cooling already").isEqualTo(Action.NONE);
        assertThat(heat(off(), 25.5, above, null, true)).as("held off").isEqualTo(Action.NONE);
        assertThat(heat(off(), 24.5, above, null, false)).as("below the threshold").isEqualTo(Action.HEAT);
    }

    @Test
    void should_not_heat_by_a_rule_that_does_not() {
        assertThat(RuleEvaluator.decide(SANCTUM, off(), 5.0, NINE, null, false, NOW, TIMING)).isEqualTo(Action.NONE);
    }

    private static final UnitStatus COOLING_OFF = status(false, Mode.COOL);
    private static final AirconRule HEATING = new AirconRule(144, new AirconRule.KeepBelow(true, new BigDecimal("25.0"),
        new BigDecimal("1.0"), 22), new AirconRule.HeatBySchedule(true), NOW);
    private static final Heating NINE = new Heating(9.0);
    private static final Heating NO_SCHEDULE = new Heating(null);

    private static Action heat(final UnitState unit, final Double room, final Heating heating, final Change change,
                               final boolean heldOff) {
        return RuleEvaluator.decide(HEATING, unit, room, heating, change, heldOff, NOW, TIMING);
    }

    private static Change changeAgo(final ChangeSource source, final int minutes) {
        return new Change(source, NOW.minusMinutes(minutes));
    }

    private static UnitState heating(final int target) {
        return new UnitState(new UnitStatus(true, Mode.HEAT, target, 15, FanSpeed.AUTO, 0, 0, false, false, false, true,
            null), true, NOW, null);
    }

    private static Action decide(final UnitState unit, final Double room, final Change change, final boolean heldOff) {
        return RuleEvaluator.decide(SANCTUM, unit, room, null, change, heldOff, NOW, TIMING);
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
