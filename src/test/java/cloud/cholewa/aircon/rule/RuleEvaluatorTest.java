package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.rule.RuleEvaluator.Action;
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
        assertThat(RuleEvaluator.decide(SANCTUM, null, 27.0, null, false, NOW, HOLD)).isEqualTo(Action.NONE);
        assertThat(RuleEvaluator.decide(SANCTUM, new UnitState(COOLING_OFF, false, NOW, NOW), 27.0, null, false, NOW,
            HOLD)).isEqualTo(Action.NONE);
        assertThat(RuleEvaluator.decide(SANCTUM, new UnitState(null, false, null, NOW), 27.0, null, false, NOW, HOLD))
            .isEqualTo(Action.NONE);
    }

    @Test
    void should_not_cool_while_something_holds_the_unit_off() {
        assertThat(decide(off(), 27.0, null, true)).isEqualTo(Action.NONE);
        assertThat(decide(on(Mode.COOL), 20.0, ruleChange(), true)).as("the off still goes").isEqualTo(Action.OFF);
    }

    @Test
    void should_do_nothing_for_a_rule_that_is_off() {
        assertThat(RuleEvaluator.decide(rule(false), new UnitState(COOLING_OFF, true, NOW, null), 27.0, null, false,
            NOW, HOLD)).isEqualTo(Action.NONE);
    }

    private static final UnitStatus COOLING_OFF = status(false, Mode.COOL);

    private static Action decide(final UnitState unit, final Double room, final Change change, final boolean heldOff) {
        return RuleEvaluator.decide(SANCTUM, unit, room, change, heldOff, NOW, HOLD);
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
