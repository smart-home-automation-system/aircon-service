package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.api.model.RuleBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//Rule by rule - the dashboard copies RuleRules, so a rule changed here is changed there too
class RuleRulesTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 7, 20, 0);

    @Test
    void should_take_the_rule_of_sanctum() {
        assertThat(RuleRules.problem(body(true, "25", "1", 22))).isEmpty();
    }

    @Test
    void should_fill_in_the_defaults() {
        final AirconRule rule = RuleRules.of(144, new RuleBody(
            new RuleBody.KeepBelow(true, new BigDecimal("25"), null, 22), null), AT);

        assertThat(rule).isEqualTo(new AirconRule(144, new AirconRule.KeepBelow(true, new BigDecimal("25.0"),
            new BigDecimal("1.0"), 22), new AirconRule.HeatBySchedule(false), AT));
    }

    @Test
    void should_refuse_a_rule_without_keep_below() {
        assertThat(RuleRules.problem(null)).contains("A rule has keepBelow");
        assertThat(RuleRules.problem(new RuleBody(null, null))).contains("A rule has keepBelow");
        assertThat(RuleRules.problem(new RuleBody(new RuleBody.KeepBelow(null, new BigDecimal("25"), null, 22), null)))
            .contains("keepBelow.enabled is true or false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"19.5", "30.5", "25.2", "24.75"})
    void should_refuse_a_threshold_outside_20_to_30_by_half_a_degree(final String threshold) {
        assertThat(RuleRules.problem(body(true, threshold, "1", 22)))
            .contains("The threshold is 20 to 30 C by 0.5, not " + threshold);
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "30", "27.5"})
    void should_take_a_threshold_inside(final String threshold) {
        assertThat(RuleRules.problem(body(true, threshold, "1", 22))).isEmpty();
    }

    //a threshold of 20 is a valid threshold with a target of 22 refused: the target is never above it
    @Test
    void should_refuse_a_threshold_below_the_target() {
        assertThat(RuleRules.problem(body(true, "20", "1", 22))).contains("The target is not above the threshold");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.4", "3.5", "1.2"})
    void should_refuse_a_hysteresis_outside_half_to_3_by_half_a_degree(final String hysteresis) {
        assertThat(RuleRules.problem(body(true, "25", hysteresis, 22)))
            .contains("The hysteresis is 0.5 to 3 C by 0.5, not " + hysteresis);
    }

    @Test
    void should_refuse_a_target_below_the_minimum_of_cooling() {
        assertThat(RuleRules.problem(body(true, "25", "1", 21))).contains("The target is 22 to 30 C, not 21");
        assertThat(RuleRules.problem(body(true, "25", "1", null))).contains("The target is 22 to 30 C, not null");
    }

    @Test
    void should_refuse_a_target_above_the_threshold() {
        assertThat(RuleRules.problem(body(true, "23", "1", 24))).contains("The target is not above the threshold");
        assertThat(RuleRules.problem(body(true, "24", "1", 24))).isEmpty();
    }

    //a switched-off rule is checked all the same: switching it on is no write of its numbers
    @Test
    void should_check_a_rule_that_is_off() {
        assertThat(RuleRules.problem(body(false, "35", "1", 22))).isPresent();
    }

    @Test
    void should_refuse_heat_by_schedule_without_its_switch() {
        assertThat(RuleRules.problem(new RuleBody(new RuleBody.KeepBelow(true, new BigDecimal("25"), null, 22),
            new RuleBody.HeatBySchedule(null)))).contains("heatBySchedule.enabled is true or false");
    }

    private static RuleBody body(final Boolean enabled, final String threshold, final String hysteresis,
                                 final Integer target) {
        return new RuleBody(new RuleBody.KeepBelow(enabled, new BigDecimal(threshold), new BigDecimal(hysteresis), target),
            new RuleBody.HeatBySchedule(false));
    }
}
