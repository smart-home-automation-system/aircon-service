package cloud.cholewa.aircon.rule;

import cloud.cholewa.aircon.api.model.AirconRule;
import cloud.cholewa.aircon.api.model.RuleBody;
import cloud.cholewa.aircon.command.CommandRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

//The rules of a rule (HAS-386) - pure, and copied by the dashboard: a rule changed here is changed there too. The
//threshold 20 to 30 C by 0.5, the hysteresis 0.5 to 3 C by 0.5 (1 when left out), the target a whole degree from the
//minimum of cooling (22 C, CommandRules) to 30 and never above the threshold - a target above it would cool a room that
//never gets below the threshold minus the hysteresis
public final class RuleRules {

    public static final BigDecimal THRESHOLD_MIN = new BigDecimal("20");
    public static final BigDecimal THRESHOLD_MAX = new BigDecimal("30");
    public static final BigDecimal HYSTERESIS_MIN = new BigDecimal("0.5");
    public static final BigDecimal HYSTERESIS_MAX = new BigDecimal("3");
    public static final BigDecimal HYSTERESIS_DEFAULT = BigDecimal.ONE;
    public static final int TARGET_MIN = CommandRules.COOLING_MIN;
    public static final int TARGET_MAX = CommandRules.TARGET_MAX;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private RuleRules() {
    }

    //what is wrong with a rule, or nothing
    public static Optional<String> problem(final RuleBody body) {
        if (body == null || body.keepBelow() == null) {
            return Optional.of("A rule has keepBelow");
        }
        final RuleBody.KeepBelow keepBelow = body.keepBelow();
        final BigDecimal hysteresis = Optional.ofNullable(keepBelow.hysteresis()).orElse(HYSTERESIS_DEFAULT);
        if (keepBelow.enabled() == null) {
            return Optional.of("keepBelow.enabled is true or false");
        }
        if (outside(keepBelow.threshold(), THRESHOLD_MIN, THRESHOLD_MAX)) {
            return Optional.of("The threshold is " + THRESHOLD_MIN + " to " + THRESHOLD_MAX + " C by 0.5, not "
                + keepBelow.threshold());
        }
        if (outside(hysteresis, HYSTERESIS_MIN, HYSTERESIS_MAX)) {
            return Optional.of("The hysteresis is " + HYSTERESIS_MIN + " to " + HYSTERESIS_MAX + " C by 0.5, not "
                + hysteresis);
        }
        if (keepBelow.target() == null || keepBelow.target() < TARGET_MIN || keepBelow.target() > TARGET_MAX) {
            return Optional.of("The target is " + TARGET_MIN + " to " + TARGET_MAX + " C, not " + keepBelow.target());
        }
        if (BigDecimal.valueOf(keepBelow.target()).compareTo(keepBelow.threshold()) > 0) {
            return Optional.of("The target is not above the threshold");
        }
        if (body.heatBySchedule() != null && body.heatBySchedule().enabled() == null) {
            return Optional.of("heatBySchedule.enabled is true or false");
        }
        return Optional.empty();
    }

    //a rule the rules took, as stored - the defaults filled in
    public static AirconRule of(final long unitId, final RuleBody body, final LocalDateTime at) {
        final RuleBody.KeepBelow keepBelow = body.keepBelow();
        return new AirconRule(unitId,
            new AirconRule.KeepBelow(keepBelow.enabled(), keepBelow.threshold().setScale(1, RoundingMode.UNNECESSARY),
                Optional.ofNullable(keepBelow.hysteresis()).orElse(HYSTERESIS_DEFAULT).setScale(1, RoundingMode.UNNECESSARY),
                keepBelow.target()),
            new AirconRule.HeatBySchedule(body.heatBySchedule() != null && Boolean.TRUE.equals(body.heatBySchedule().enabled())),
            at);
    }

    private static boolean outside(final BigDecimal value, final BigDecimal min, final BigDecimal max) {
        return value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0
            || value.remainder(HALF).compareTo(BigDecimal.ZERO) != 0;
    }
}
