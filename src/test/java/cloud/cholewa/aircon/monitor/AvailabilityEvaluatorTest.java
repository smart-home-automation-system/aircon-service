package cloud.cholewa.aircon.monitor;

import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.aircon.monitor.AvailabilityEvaluator.Watched;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 15, 30);
    private static final MonitorProperties PROPERTIES = new MonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1));

    @Test
    void should_alert_a_unit_that_has_not_answered_for_five_minutes() {
        final Decision decision = decide(List.of(unit(140, NOW.minusMinutes(5))), Map.of());

        assertThat(decision.alerts()).containsExactly(new Alert(140, "Air conditioner", "office", NOW.minusMinutes(5), NOW));
    }

    @Test
    void should_wait_until_a_unit_is_out() {
        assertThat(decide(List.of(unit(140, NOW.minusMinutes(4))), Map.of()).nothing()).isTrue();
    }

    @Test
    void should_remind_every_hour_while_the_unit_stays_out() {
        final Alert open = new Alert(140, "Air conditioner", "office", NOW.minusHours(2), NOW.minusHours(1));
        final Alert recent = new Alert(141, "Air conditioner", "livia", NOW.minusHours(2), NOW.minusMinutes(59));

        final Decision decision = decide(List.of(unit(140, NOW.minusHours(2)), unit(141, NOW.minusHours(2))),
            Map.of(140L, open, 141L, recent));

        assertThat(decision.reminders()).containsExactly(open);
        assertThat(decision.alerts()).isEmpty();
    }

    @Test
    void should_tell_the_return_of_a_unit_answering_again() {
        final Alert open = new Alert(140, "Air conditioner", "office", NOW.minusHours(2), NOW.minusHours(1));

        final Decision decision = decide(List.of(new Watched(140, "Air conditioner", "office", null, true)),
            Map.of(140L, open));

        assertThat(decision.returns()).containsExactly(open);
    }

    //after a restart a unit not read yet is not back - the alert stays until a read answers
    @Test
    void should_not_tell_a_return_of_a_unit_not_known_yet() {
        final Alert open = new Alert(140, "Air conditioner", "office", NOW.minusHours(2), NOW.minusHours(1));

        final Decision decision = decide(List.of(new Watched(140, "Air conditioner", "office", null, false)),
            Map.of(140L, open));

        assertThat(decision.nothing()).isTrue();
    }

    @Test
    void should_forget_the_alert_of_a_unit_removed_from_the_registry() {
        final Alert open = new Alert(144, "Air conditioner", "sanctum", NOW.minusHours(2), NOW.minusHours(1));

        assertThat(decide(List.of(), Map.of(144L, open)).forgotten()).containsExactly(open);
    }

    private static Decision decide(final List<Watched> watched, final Map<Long, Alert> open) {
        return AvailabilityEvaluator.decide(watched, open, NOW, PROPERTIES);
    }

    private static Watched unit(final long id, final LocalDateTime unreachableSince) {
        return new Watched(id, "Air conditioner", id == 141 ? "livia" : "office", unreachableSince, true);
    }
}
