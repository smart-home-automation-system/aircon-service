package cloud.cholewa.aircon.automation;

import cloud.cholewa.aircon.automation.AutomationEvaluator.Episode;
import cloud.cholewa.aircon.registry.AirConditioner;
import cloud.cholewa.aircon.state.UnitState;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 20, 0);
    private static final Duration AFTER = Duration.ofMinutes(10);
    private static final AirConditioner OFFICE = unit(140, RoomName.OFFICE);
    private static final AirConditioner LIVIA = unit(141, RoomName.LIVIA);
    private static final AirConditioner SANCTUM = unit(144, RoomName.SANCTUM);

    @Test
    void should_act_on_a_house_empty_for_ten_minutes() {
        assertThat(emptyDue(empty(NOW.minusMinutes(10)), null, null)).isTrue();
        assertThat(emptyDue(empty(NOW.minusMinutes(9)), null, null)).isFalse();
    }

    @Test
    void should_never_act_on_a_house_not_known_or_occupied() {
        assertThat(emptyDue(HouseOccupancy.unknown(), NOW.minusHours(1), null)).isFalse();
        assertThat(emptyDue(new HouseOccupancy(HouseOccupancy.State.OCCUPIED, null), NOW.minusHours(1), null)).isFalse();
    }

    //presence-service gives no time: the first pass that saw it empty
    @Test
    void should_count_from_the_first_empty_pass_without_the_time_of_presence_service() {
        assertThat(emptyDue(empty(null), NOW.minusMinutes(10), null)).isTrue();
        assertThat(emptyDue(empty(null), NOW.minusMinutes(5), null)).isFalse();
        assertThat(emptyDue(empty(null), null, null)).isFalse();
    }

    @Test
    void should_act_once_on_one_empty_house() {
        assertThat(emptyDue(empty(NOW.minusHours(1)), null, new Episode(NOW.minusHours(1), NOW.minusMinutes(50))))
            .isFalse();
        assertThat(emptyDue(empty(NOW.minusHours(1)), null, new Episode(NOW.minusDays(1), NOW.minusDays(1))))
            .as("another empty house").isTrue();
    }

    @Test
    void should_act_once_on_one_outage() {
        final GridState lost = new GridState("lost", NOW.minusMinutes(1));

        assertThat(AutomationEvaluator.outageBegins(lost, null)).isTrue();
        assertThat(AutomationEvaluator.outageBegins(lost, new Episode(NOW.minusMinutes(1), NOW))).isFalse();
        assertThat(AutomationEvaluator.outageBegins(lost, new Episode(NOW.minusDays(2), NOW.minusDays(2)))).isTrue();
    }

    @Test
    void should_never_act_on_a_grid_present_or_not_known() {
        assertThat(AutomationEvaluator.outageBegins(new GridState("present", NOW), null)).isFalse();
        assertThat(AutomationEvaluator.outageBegins(GridState.unknown(), null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"COOL", "DRY", "FAN", "AUTO"})
    void should_switch_off_a_cooling_unit_of_the_empty_house(final Mode mode) {
        assertThat(AutomationEvaluator.emptyHouseTargets(List.of(OFFICE), Map.of(140L, on(mode)), List.of("sanctum")))
            .containsExactly(OFFICE);
    }

    //heating, a unit off, a unit not read, the exempt room
    @Test
    void should_leave_heating_units_off_and_sanctum_to_the_empty_house() {
        assertThat(AutomationEvaluator.emptyHouseTargets(List.of(OFFICE, LIVIA, SANCTUM, unit(142, RoomName.TOBI)),
            Map.of(140L, on(Mode.HEAT), 141L, off(), 144L, on(Mode.COOL)), List.of("sanctum"))).isEmpty();
    }

    @Test
    void should_switch_off_every_unit_on_for_a_lost_grid() {
        assertThat(AutomationEvaluator.gridTargets(List.of(OFFICE, LIVIA, SANCTUM),
            Map.of(140L, on(Mode.HEAT), 141L, off(), 144L, on(Mode.COOL)))).containsExactly(OFFICE, SANCTUM);
    }

    //what a unit of the episode asks for: on and answering - the command; off - nothing more; silent or not read - wait
    @Test
    void should_send_only_to_a_unit_on_and_answering() {
        assertThat(AutomationEvaluator.pending(new UnitState(on(Mode.COOL), true, NOW, null)))
            .isEqualTo(AutomationEvaluator.Pending.SEND);
        assertThat(AutomationEvaluator.pending(new UnitState(on(Mode.COOL), null, NOW, null)))
            .as("restored, not read yet").isEqualTo(AutomationEvaluator.Pending.SEND);
        assertThat(AutomationEvaluator.pending(new UnitState(off(), true, NOW, null)))
            .isEqualTo(AutomationEvaluator.Pending.DONE);
        assertThat(AutomationEvaluator.pending(new UnitState(on(Mode.COOL), false, NOW, NOW)))
            .isEqualTo(AutomationEvaluator.Pending.WAIT);
        assertThat(AutomationEvaluator.pending(null)).isEqualTo(AutomationEvaluator.Pending.WAIT);
        assertThat(AutomationEvaluator.pending(new UnitState(null, false, null, NOW)))
            .isEqualTo(AutomationEvaluator.Pending.WAIT);
    }

    private static boolean emptyDue(final HouseOccupancy house, final LocalDateTime firstSeen, final Episode acted) {
        return AutomationEvaluator.emptyHouseBegins(house, firstSeen, acted, NOW, AFTER);
    }

    private static HouseOccupancy empty(final LocalDateTime since) {
        return new HouseOccupancy(HouseOccupancy.State.EMPTY, since);
    }

    private static UnitStatus on(final Mode mode) {
        return new UnitStatus(true, mode, 24, 26, FanSpeed.AUTO, 0, 0, false, false, false, true, null);
    }

    private static UnitStatus off() {
        return new UnitStatus(false, Mode.COOL, 24, 26, FanSpeed.AUTO, 0, 0, false, false, false, true, null);
    }

    private static AirConditioner unit(final long id, final RoomName room) {
        return new AirConditioner(id, "Air conditioner", room, "192.0.2." + id, "02005e0000" + id);
    }
}
