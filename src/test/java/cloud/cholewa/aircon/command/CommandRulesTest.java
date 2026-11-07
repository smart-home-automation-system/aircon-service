package cloud.cholewa.aircon.command;

import cloud.cholewa.aircon.api.model.CommandRequest;
import cloud.cholewa.aircon.unit.FanSpeed;
import cloud.cholewa.aircon.unit.Mode;
import cloud.cholewa.aircon.unit.UnitStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//The rules of the owner (2026-11-04), copied by the dashboard: the numbers are pinned here
class CommandRulesTest {

    private static final UnitStatus COOLING_AT_24 = new UnitStatus(true, Mode.COOL, 24, 22, FanSpeed.AUTO, 0, 0, false,
        false, false, true, null);
    private static final UnitStatus HEATING_AT_20 = new UnitStatus(true, Mode.HEAT, 20, 18, FanSpeed.AUTO, 0, 0, false,
        false, false, true, null);
    private static final UnitStatus DRYING = new UnitStatus(true, Mode.DRY, 24, 22, FanSpeed.AUTO, 0, 0, false, false,
        false, true, null);

    @Test
    void should_pin_the_numbers_of_the_rules() {
        assertThat(CommandRules.TARGET_MIN).isEqualTo(16);
        assertThat(CommandRules.TARGET_MAX).isEqualTo(30);
        assertThat(CommandRules.COOLING_MIN).isEqualTo(22);
        assertThat(CommandRules.SWING_VERTICAL_MAX).isEqualTo(11);
        assertThat(CommandRules.SWING_HORIZONTAL_MAX).isEqualTo(6);
    }

    @Test
    void should_let_a_command_within_the_rules_pass() {
        assertThat(CommandRules.problem(command(true, null, 23, null, null), COOLING_AT_24)).isEmpty();
        assertThat(CommandRules.problem(command(null, Mode.HEAT, 16, null, null), COOLING_AT_24)).isEmpty();
        assertThat(CommandRules.problem(command(null, null, 22, null, null), COOLING_AT_24)).isEmpty();
    }

    @Test
    void should_refuse_an_empty_command() {
        assertThat(CommandRules.problem(command(null, null, null, null, null), COOLING_AT_24))
            .contains("A command sets at least one field");
    }

    @Test
    void should_refuse_a_target_outside_16_to_30() {
        assertThat(CommandRules.problem(command(null, Mode.HEAT, 15, null, null), HEATING_AT_20))
            .contains("The target temperature is 16 to 30 C, not 15");
        assertThat(CommandRules.problem(command(null, null, 31, null, null), HEATING_AT_20))
            .contains("The target temperature is 16 to 30 C, not 31");
    }

    //cooling never below 22: the target asked for in cooling, cooling asked for at the target the unit has
    @Test
    void should_refuse_cooling_below_22() {
        assertThat(CommandRules.problem(command(null, null, 21, null, null), COOLING_AT_24))
            .contains("Cooling never goes below 22 C, not 21");
        assertThat(CommandRules.problem(command(null, Mode.COOL, null, null, null), HEATING_AT_20))
            .contains("Cooling never goes below 22 C, not 20");
        assertThat(CommandRules.problem(command(null, Mode.COOL, 22, null, null), HEATING_AT_20)).isEmpty();
        assertThat(CommandRules.problem(command(null, null, 18, null, null), HEATING_AT_20)).isEmpty();
    }

    //a unit already cooling below the minimum is raised by the guard - a change of its fan alone is not refused
    @Test
    void should_not_refuse_a_fan_of_a_unit_below_the_minimum() {
        final UnitStatus coolingAt18 = new UnitStatus(true, Mode.COOL, 18, 22, FanSpeed.AUTO, 0, 0, false, false, false,
            true, null);

        assertThat(CommandRules.problem(new CommandRequest(null, null, null, FanSpeed.HIGH, null, null, null, null, null,
            null), coolingAt18)).isEmpty();
    }

    @Test
    void should_refuse_turbo_and_quiet_in_dry_and_fan() {
        assertThat(CommandRules.problem(command(null, null, null, true, null), DRYING))
            .contains("Turbo and quiet are not for the mode dry");
        assertThat(CommandRules.problem(command(null, Mode.FAN, null, null, true), COOLING_AT_24))
            .contains("Turbo and quiet are not for the mode fan");
        assertThat(CommandRules.problem(command(null, null, null, false, null), DRYING)).isEmpty();
    }

    @Test
    void should_refuse_a_swing_outside_its_codes() {
        assertThat(CommandRules.problem(new CommandRequest(null, null, null, null, 12, null, null, null, null, null),
            COOLING_AT_24)).contains("The vertical swing is 0 to 11, not 12");
        assertThat(CommandRules.problem(new CommandRequest(null, null, null, null, null, 7, null, null, null, null),
            COOLING_AT_24)).contains("The horizontal swing is 0 to 6, not 7");
    }

    //a unit never read: judged by what the command sets alone
    @Test
    void should_judge_a_unit_never_read_by_the_command_alone() {
        assertThat(CommandRules.problem(command(true, null, null, null, null), null)).isEmpty();
        assertThat(CommandRules.problem(command(null, Mode.COOL, 20, null, null), null))
            .contains("Cooling never goes below 22 C, not 20");
    }

    @Test
    void should_write_the_columns_of_the_protocol_in_order() {
        final Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("Pow", 1);
        expected.put("Mod", 1);
        expected.put("SetTem", 23);
        expected.put("TemUn", 0);
        expected.put("WdSpd", 2);
        expected.put("SwUpDn", 3);
        expected.put("SwingLfRig", 4);
        expected.put("Quiet", 0);
        expected.put("Tur", 1);
        expected.put("SwhSlp", 0);
        expected.put("Lig", 1);

        assertThat(CommandRules.wire(new CommandRequest(true, Mode.COOL, 23, FanSpeed.MEDIUM_LOW, 3, 4, false, true,
            false, true))).containsExactlyEntriesOf(expected);
        assertThat(CommandRules.wire(command(false, null, null, null, null))).containsExactlyEntriesOf(Map.of("Pow", 0));
    }

    @Test
    void should_see_a_command_taken_by_the_read_of_the_unit() {
        assertThat(CommandRules.takenBy(command(true, null, 24, null, null), COOLING_AT_24)).isTrue();
        assertThat(CommandRules.takenBy(command(true, null, 23, null, null), COOLING_AT_24)).isFalse();
        assertThat(CommandRules.takenBy(command(false, null, null, null, null), COOLING_AT_24)).isFalse();
    }

    private static CommandRequest command(final Boolean power, final Mode mode, final Integer target,
                                          final Boolean turbo, final Boolean quiet) {
        return new CommandRequest(power, mode, target, null, null, null, quiet, turbo, null, null);
    }
}
