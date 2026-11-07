package cloud.cholewa.aircon.window;

import cloud.cholewa.aircon.state.ChangeSource;
import cloud.cholewa.aircon.state.StateStore.Change;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.aircon.window.WindowHoldEvaluator.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WindowHoldEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 18, 30);
    private static final Duration WITHIN = Duration.ofHours(1);
    private static final LocalDateTime OPENED = NOW.minusMinutes(4);

    private static final UnitStatus COOLING = new UnitStatus(true, Mode.COOL, 24, 26, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);
    private static final UnitStatus OFF = new UnitStatus(false, Mode.COOL, 24, 26, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);

    private static final RoomWindow HOLDING = new RoomWindow("open", OPENED, true);
    private static final RoomWindow CLOSED = new RoomWindow("closed", null, false);

    @Test
    void should_stop_a_unit_on_in_a_room_whose_window_holds() {
        assertThat(decide(HOLDING, COOLING, null, null)).isEqualTo(Action.HOLD);
    }

    @Test
    void should_leave_a_unit_that_is_off_or_not_known() {
        assertThat(decide(HOLDING, OFF, null, null)).isEqualTo(Action.NONE);
        assertThat(decide(HOLDING, null, null, null)).isEqualTo(Action.NONE);
    }

    //the window not known - heating-service silent, a sensor unknown, no sensor, a window open less than 3 min - never holds
    @Test
    void should_never_stop_a_unit_by_a_window_that_does_not_hold() {
        assertThat(decide(null, COOLING, null, null)).isEqualTo(Action.NONE);
        assertThat(decide(new RoomWindow("unknown", null, false), COOLING, null, null)).isEqualTo(Action.NONE);
        assertThat(decide(new RoomWindow("no sensor", null, false), COOLING, null, null)).isEqualTo(Action.NONE);
        assertThat(decide(new RoomWindow("open", NOW.minusMinutes(1), false), COOLING, null, null)).isEqualTo(Action.NONE);
        assertThat(decide(new RoomWindow("open", NOW.minusMinutes(1), null), COOLING, null, null)).isEqualTo(Action.NONE);
    }

    //a stop given up on, or ended, is never repeated for the same opening - a person switched it on with the window open
    @ParameterizedTest
    @EnumSource(HoldOutcome.class)
    void should_not_stop_a_unit_twice_for_one_opening(final HoldOutcome outcome) {
        assertThat(decide(HOLDING, COOLING, closed(OPENED, outcome), null)).isEqualTo(Action.NONE);
    }

    @Test
    void should_stop_a_unit_again_for_a_new_opening() {
        assertThat(decide(HOLDING, COOLING, closed(OPENED.minusHours(2), HoldOutcome.RESTORED), null))
            .isEqualTo(Action.HOLD);
    }

    @Test
    void should_wait_while_the_window_holds_a_unit_that_took_the_stop() {
        assertThat(decide(HOLDING, OFF, open(NOW.minusMinutes(10)), windowChange(NOW.minusMinutes(10))))
            .isEqualTo(Action.NONE);
    }

    //the stop was not taken: on again, and nobody changed it since
    //a unit that does not answer is neither stopped nor sent the stop again every minute; one not read since a start is
    @Test
    void should_not_send_a_stop_to_a_unit_that_does_not_answer() {
        final WindowHoldEvaluator.Unit out = new WindowHoldEvaluator.Unit(COOLING, false);

        assertThat(WindowHoldEvaluator.decide(HOLDING, out, null, null, NOW, WITHIN)).isEqualTo(Action.NONE);
        assertThat(WindowHoldEvaluator.decide(HOLDING, out, open(NOW.minusMinutes(1)), null, NOW, WITHIN))
            .isEqualTo(Action.NONE);
        assertThat(WindowHoldEvaluator.decide(HOLDING, new WindowHoldEvaluator.Unit(COOLING, null), null, null, NOW,
            WITHIN)).isEqualTo(Action.HOLD);
    }

    @Test
    void should_stop_again_a_unit_that_is_still_on() {
        assertThat(decide(HOLDING, COOLING, open(NOW.minusMinutes(1)), null)).isEqualTo(Action.STOP_AGAIN);
    }

    @Test
    void should_set_back_a_unit_when_the_windows_close_in_time() {
        assertThat(decide(CLOSED, OFF, open(NOW.minusMinutes(59)), windowChange(NOW.minusMinutes(59))))
            .isEqualTo(Action.RESTORE);
        assertThat(decide(CLOSED, OFF, open(NOW.minus(WITHIN)), null)).as("the last moment counts").isEqualTo(Action.RESTORE);
    }

    @Test
    void should_leave_a_unit_off_when_the_windows_close_too_late() {
        assertThat(decide(CLOSED, OFF, open(NOW.minusMinutes(61)), null)).isEqualTo(Action.EXPIRE);
    }

    //a window not known, or open again but not held yet, never sets a unit back - and the stop still expires
    @Test
    void should_wait_for_a_window_known_to_be_closed() {
        assertThat(decide(null, OFF, open(NOW.minusMinutes(10)), null)).isEqualTo(Action.NONE);
        assertThat(decide(new RoomWindow("unknown", null, false), OFF, open(NOW.minusMinutes(10)), null))
            .isEqualTo(Action.NONE);
        assertThat(decide(new RoomWindow("open", NOW.minusMinutes(1), false), OFF, open(NOW.minusMinutes(10)), null))
            .isEqualTo(Action.NONE);
        assertThat(decide(null, OFF, open(NOW.minusMinutes(61)), null)).isEqualTo(Action.EXPIRE);
    }

    @ParameterizedTest
    @EnumSource(value = ChangeSource.class, names = {"REMOTE", "DASHBOARD", "RULE", "EMPTY_HOUSE", "GRID_OUTAGE",
        "MINIMUM_RESTORED"})
    void should_give_up_a_stop_the_unit_was_changed_in_by_somebody(final ChangeSource source) {
        final Change change = new Change(source, NOW.minusMinutes(2));

        assertThat(decide(HOLDING, COOLING, open(NOW.minusMinutes(5)), change)).isEqualTo(Action.CANCEL);
        assertThat(decide(CLOSED, OFF, open(NOW.minusMinutes(5)), change)).isEqualTo(Action.CANCEL);
    }

    //the stop itself, the first read and a change older than the stop are nobody's
    @Test
    void should_not_give_up_a_stop_for_its_own_change_or_an_older_one() {
        assertThat(decide(CLOSED, OFF, open(NOW.minusMinutes(5)), windowChange(NOW.minusMinutes(5))))
            .isEqualTo(Action.RESTORE);
        assertThat(decide(CLOSED, OFF, open(NOW.minusMinutes(5)), new Change(ChangeSource.FIRST_READ, NOW.minusMinutes(1))))
            .isEqualTo(Action.RESTORE);
        assertThat(decide(CLOSED, OFF, open(NOW.minusMinutes(5)), new Change(ChangeSource.REMOTE, NOW.minusMinutes(6))))
            .isEqualTo(Action.RESTORE);
    }

    private static Action decide(final RoomWindow window, final UnitStatus status, final WindowHold latest,
                                 final Change change) {
        return WindowHoldEvaluator.decide(window, new WindowHoldEvaluator.Unit(status, true), latest, change, NOW, WITHIN);
    }

    private static WindowHold open(final LocalDateTime heldSince) {
        return new WindowHold(1L, 140, OPENED, heldSince, COOLING, null, null);
    }

    private static WindowHold closed(final LocalDateTime openSince, final HoldOutcome outcome) {
        return new WindowHold(1L, 140, openSince, NOW.minusMinutes(1), COOLING, NOW.minusSeconds(30), outcome);
    }

    private static Change windowChange(final LocalDateTime at) {
        return new Change(ChangeSource.WINDOW, at.plusSeconds(3));
    }
}
